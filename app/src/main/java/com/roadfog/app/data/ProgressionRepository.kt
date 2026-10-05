package com.roadfog.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

enum class PlaceKind(val points: Long) {
    TOWN(100L),
    STATE(500L),
    COUNTRY(1_500L)
}

data class PlaceDiscovery(
    val kind: PlaceKind,
    val key: String,
    val displayName: String,
    val parentName: String = "",
    val countryName: String = "",
    val visitedAt: Long,
    val latitude: Double,
    val longitude: Double
)

data class PendingPlaceCandidate(
    val cellX: Long,
    val cellY: Long,
    val latitude: Double,
    val longitude: Double,
    val visitedAt: Long,
    val attempts: Int
)

data class ProgressionSnapshot(
    val balance: Long,
    val lifetimeEarned: Long,
    val pointsSpent: Long,
    val rewardedRoads: Long,
    val towns: Long,
    val states: Long,
    val countries: Long,
    val adsWatched: Long,
    val lowestBatteryPercent: Int,
    val purchasedItems: Set<String>,
    val rewardedAchievements: Set<String>
)

enum class PurchaseResult { PURCHASED, OWNED, INSUFFICIENT_POINTS }

class ProgressionRepository(context: Context) {
    private val dbHelper = AppDatabase.get(context.applicationContext)

    fun syncRoadRewards(roadsUnlocked: Long): Long = synchronized(dbHelper.historyLock) {
        val target = roadsUnlocked.coerceAtLeast(0L)
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val previous = counter(db, COUNTER_REWARDED_ROADS)
            if (target > previous) {
                val delta = target - previous
                awardOnce(db, "roads:$previous-$target", delta * POINTS_PER_ROAD)
                putCounter(db, COUNTER_REWARDED_ROADS, target)
            }
            db.setTransactionSuccessful()
            target.coerceAtLeast(previous)
        } finally {
            db.endTransaction()
        }
    }

    fun awardAchievement(id: String, points: Long): Boolean {
        require(points > 0L)
        return synchronized(dbHelper.historyLock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val inserted = awardOnce(db, "achievement:$id", points)
                db.setTransactionSuccessful()
                inserted
            } finally {
                db.endTransaction()
            }
        }
    }

    fun recordPlace(discovery: PlaceDiscovery): Boolean = synchronized(dbHelper.historyLock) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict(
                "visited_places",
                null,
                ContentValues().apply {
                    put("kind", discovery.kind.name)
                    put("place_key", discovery.key)
                    put("display_name", discovery.displayName)
                    put("parent_name", discovery.parentName)
                    put("country_name", discovery.countryName)
                    put("first_visited_at", discovery.visitedAt)
                    put("latitude", discovery.latitude)
                    put("longitude", discovery.longitude)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            ) != -1L
            if (inserted) {
                awardOnce(
                    db,
                    "place:${discovery.kind.name.lowercase()}:${discovery.key}",
                    discovery.kind.points
                )
            }
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Save a sparse reverse-geocoding candidate locally. Background tracking never has to make
     * a geocoder/network request; candidates are resolved later while the app is foregrounded.
     */
    fun recordPlaceCandidate(location: Location): Boolean = synchronized(dbHelper.historyLock) {
        if (location.latitude !in -85.0..85.0 || location.longitude !in -180.0..180.0) return@synchronized false
        val radius = 6_378_137.0
        val longitude = ((location.longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val x = floor(radius * Math.toRadians(longitude) / PLACE_CANDIDATE_CELL_M).toLong()
        val y = floor(
            radius * ln(tan(PI / 4 + Math.toRadians(location.latitude.coerceIn(-85.0, 85.0)) / 2)) /
                PLACE_CANDIDATE_CELL_M
        ).toLong()
        val latitude = Math.toDegrees(atan(sinh((y + 0.5) * PLACE_CANDIDATE_CELL_M / radius)))
        val cellLongitude = Math.toDegrees((x + 0.5) * PLACE_CANDIDATE_CELL_M / radius)
        dbHelper.writableDatabase.insertWithOnConflict(
            "place_candidates",
            null,
            ContentValues().apply {
                put("cell_x", x)
                put("cell_y", y)
                put("latitude", latitude)
                put("longitude", cellLongitude)
                put("first_seen_at", location.time.takeIf { it > 0L } ?: System.currentTimeMillis())
                put("attempts", 0)
                put("next_attempt_ms", 0)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun pendingPlaceCandidates(
        limit: Int = 8,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PendingPlaceCandidate> {
        require(limit in 1..50)
        val result = ArrayList<PendingPlaceCandidate>(limit)
        dbHelper.readableDatabase.query(
            "place_candidates",
            arrayOf("cell_x", "cell_y", "latitude", "longitude", "first_seen_at", "attempts"),
            "next_attempt_ms <= ?",
            arrayOf(nowMillis.toString()),
            null,
            null,
            "attempts ASC, next_attempt_ms ASC, cell_x, cell_y",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PendingPlaceCandidate(
                    cursor.getLong(0),
                    cursor.getLong(1),
                    cursor.getDouble(2),
                    cursor.getDouble(3),
                    cursor.getLong(4),
                    cursor.getInt(5)
                )
            }
        }
        return result
    }

    fun resolveCandidate(candidate: PendingPlaceCandidate, discoveries: List<PlaceDiscovery>): Int =
        synchronized(dbHelper.historyLock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                var added = 0
                for (discovery in discoveries) {
                    val inserted = db.insertWithOnConflict(
                        "visited_places",
                        null,
                        ContentValues().apply {
                            put("kind", discovery.kind.name)
                            put("place_key", discovery.key)
                            put("display_name", discovery.displayName)
                            put("parent_name", discovery.parentName)
                            put("country_name", discovery.countryName)
                            put("first_visited_at", discovery.visitedAt)
                            put("latitude", discovery.latitude)
                            put("longitude", discovery.longitude)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE
                    ) != -1L
                    if (inserted) {
                        awardOnce(
                            db,
                            "place:${discovery.kind.name.lowercase()}:${discovery.key}",
                            discovery.kind.points
                        )
                        added++
                    }
                }
                db.delete(
                    "place_candidates",
                    "cell_x = ? AND cell_y = ?",
                    arrayOf(candidate.cellX.toString(), candidate.cellY.toString())
                )
                db.setTransactionSuccessful()
                added
            } finally {
                db.endTransaction()
            }
        }

    fun deferCandidate(candidate: PendingPlaceCandidate, nowMillis: Long = System.currentTimeMillis()) =
        synchronized(dbHelper.historyLock) {
            val attempts = (candidate.attempts + 1).coerceAtMost(10)
            val delay = (30L * 60_000L * (1L shl attempts.coerceAtMost(5))).coerceAtMost(24L * 60L * 60_000L)
            dbHelper.writableDatabase.update(
                "place_candidates",
                ContentValues().apply {
                    put("attempts", attempts)
                    put("next_attempt_ms", nowMillis + delay)
                },
                "cell_x = ? AND cell_y = ?",
                arrayOf(candidate.cellX.toString(), candidate.cellY.toString())
            )
        }

    fun recordBatteryPercent(percent: Int): Boolean = synchronized(dbHelper.historyLock) {
        if (percent !in 0..100) return@synchronized false
        val db = dbHelper.writableDatabase
        val previous = counterOrNull(db, COUNTER_LOWEST_BATTERY) ?: 101L
        if (percent >= previous) return@synchronized false
        putCounter(db, COUNTER_LOWEST_BATTERY, percent.toLong())
        true
    }

    /** Future rewarded-ad SDK callbacks should call this only after a completed view is confirmed. */
    fun recordCompletedAd(): Long = synchronized(dbHelper.historyLock) {
        val db = dbHelper.writableDatabase
        val next = counter(db, COUNTER_ADS_WATCHED) + 1L
        putCounter(db, COUNTER_ADS_WATCHED, next)
        next
    }

    fun purchase(itemId: String, cost: Long): PurchaseResult {
        require(itemId.isNotBlank() && cost >= 0L)
        return synchronized(dbHelper.historyLock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val owned = db.rawQuery(
                    "SELECT 1 FROM progression_purchases WHERE item_id = ? LIMIT 1",
                    arrayOf(itemId)
                ).use { it.moveToFirst() }
                if (owned) {
                    db.setTransactionSuccessful()
                    return@synchronized PurchaseResult.OWNED
                }
                if (currentBalance(db) < cost) {
                    db.setTransactionSuccessful()
                    return@synchronized PurchaseResult.INSUFFICIENT_POINTS
                }
                db.insertOrThrow(
                    "progression_purchases",
                    null,
                    ContentValues().apply {
                        put("item_id", itemId)
                        put("points_spent", cost)
                        put("purchased_at", System.currentTimeMillis())
                    }
                )
                db.setTransactionSuccessful()
                PurchaseResult.PURCHASED
            } finally {
                db.endTransaction()
            }
        }
    }

    fun isPurchased(itemId: String): Boolean = dbHelper.readableDatabase.rawQuery(
        "SELECT 1 FROM progression_purchases WHERE item_id = ? LIMIT 1",
        arrayOf(itemId)
    ).use { it.moveToFirst() }

    fun snapshot(): ProgressionSnapshot {
        val db = dbHelper.readableDatabase
        val earned = db.rawQuery("SELECT COALESCE(SUM(points), 0) FROM progression_rewards", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        val rewardedAchievements = linkedSetOf<String>()
        db.query(
            "progression_rewards",
            arrayOf("reward_key"),
            "reward_key LIKE ?",
            arrayOf("achievement:%"),
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rewardedAchievements += cursor.getString(0).removePrefix("achievement:")
            }
        }
        val spent = db.rawQuery("SELECT COALESCE(SUM(points_spent), 0) FROM progression_purchases", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        val counts = mutableMapOf<PlaceKind, Long>()
        db.rawQuery("SELECT kind, COUNT(*) FROM visited_places GROUP BY kind", null).use { cursor ->
            while (cursor.moveToNext()) {
                PlaceKind.entries.firstOrNull { it.name == cursor.getString(0) }?.let {
                    counts[it] = cursor.getLong(1)
                }
            }
        }
        val purchases = linkedSetOf<String>()
        db.query("progression_purchases", arrayOf("item_id"), null, null, null, null, "purchased_at ASC")
            .use { cursor -> while (cursor.moveToNext()) purchases += cursor.getString(0) }
        return ProgressionSnapshot(
            balance = (earned - spent).coerceAtLeast(0L),
            lifetimeEarned = earned,
            pointsSpent = spent,
            rewardedRoads = counter(db, COUNTER_REWARDED_ROADS),
            towns = counts[PlaceKind.TOWN] ?: 0L,
            states = counts[PlaceKind.STATE] ?: 0L,
            countries = counts[PlaceKind.COUNTRY] ?: 0L,
            adsWatched = counter(db, COUNTER_ADS_WATCHED),
            lowestBatteryPercent = (counterOrNull(db, COUNTER_LOWEST_BATTERY) ?: 101L)
                .toInt().coerceIn(0, 101),
            purchasedItems = purchases,
            rewardedAchievements = rewardedAchievements
        )
    }

    fun clearProgression() = synchronized(dbHelper.historyLock) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf(
                "place_candidates",
                "visited_places",
                "progression_rewards",
                "progression_purchases",
                "progression_counters"
            )) db.delete(table, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun currentBalance(db: SQLiteDatabase): Long {
        val earned = db.rawQuery("SELECT COALESCE(SUM(points), 0) FROM progression_rewards", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        val spent = db.rawQuery("SELECT COALESCE(SUM(points_spent), 0) FROM progression_purchases", null)
            .use { check(it.moveToFirst()); it.getLong(0) }
        return earned - spent
    }

    private fun awardOnce(db: SQLiteDatabase, key: String, points: Long): Boolean =
        db.insertWithOnConflict(
            "progression_rewards",
            null,
            ContentValues().apply {
                put("reward_key", key)
                put("points", points)
                put("awarded_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L

    private fun counter(db: SQLiteDatabase, key: String): Long = counterOrNull(db, key) ?: 0L

    private fun counterOrNull(db: SQLiteDatabase, key: String): Long? =
        db.rawQuery(
            "SELECT value FROM progression_counters WHERE counter_key = ?",
            arrayOf(key)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun putCounter(db: SQLiteDatabase, key: String, value: Long) {
        db.insertWithOnConflict(
            "progression_counters",
            null,
            ContentValues().apply {
                put("counter_key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    companion object {
        const val POINTS_PER_ROAD = 5L
        private const val PLACE_CANDIDATE_CELL_M = 2_000.0
        private const val COUNTER_REWARDED_ROADS = "rewarded_roads"
        private const val COUNTER_ADS_WATCHED = "ads_watched"
        private const val COUNTER_LOWEST_BATTERY = "lowest_battery_percent"
    }
}
