package com.roadfog.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    internal val historyLock = Any()
    internal var historyGeneration = 0L
    internal var roadGroupsReady = false

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE track_points (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                accuracy_m REAL NOT NULL,
                speed_mps REAL NOT NULL,
                bearing_deg REAL NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                matched INTEGER NOT NULL DEFAULT 0,
                next_match_attempt_ms INTEGER NOT NULL DEFAULT 0,
                distance_m REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_track_time ON track_points(timestamp_ms)")
        db.execSQL("CREATE INDEX idx_track_bounds ON track_points(latitude, longitude)")
        db.execSQL("CREATE INDEX idx_track_match_queue ON track_points(matched, next_match_attempt_ms, id)")

        db.execSQL(
            """
            CREATE TABLE roads (
                segment_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                geometry_json TEXT NOT NULL,
                first_unlocked_at INTEGER NOT NULL,
                last_driven_at INTEGER NOT NULL,
                drive_count INTEGER NOT NULL DEFAULT 1,
                drive_count_exact INTEGER NOT NULL DEFAULT 1,
                road_group_id TEXT NOT NULL DEFAULT '',
                min_lat REAL NOT NULL,
                max_lat REAL NOT NULL,
                min_lon REAL NOT NULL,
                max_lon REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_roads_last_driven ON roads(last_driven_at)")
        db.execSQL("CREATE INDEX idx_roads_bounds ON roads(min_lat, max_lat, min_lon, max_lon)")
        db.execSQL("CREATE INDEX idx_roads_group ON roads(road_group_id)")

        db.execSQL(
            "CREATE TABLE road_visits (" +
                "segment_id TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL, " +
                "PRIMARY KEY (segment_id, started_at, ended_at), " +
                "FOREIGN KEY (segment_id) REFERENCES roads(segment_id) ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX idx_road_visits_segment_time " +
                "ON road_visits(segment_id, started_at, ended_at)"
        )

        db.execSQL(
            "CREATE TABLE explored_places (" +
                "cell_x INTEGER NOT NULL, cell_y INTEGER NOT NULL, latitude REAL NOT NULL, " +
                "longitude REAL NOT NULL, PRIMARY KEY (cell_x, cell_y))"
        )
        db.execSQL("CREATE INDEX idx_explored_bounds ON explored_places(latitude, longitude)")
        createProgressionTables(db)
    }

    private fun createProgressionTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS visited_places (
                kind TEXT NOT NULL,
                place_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                parent_name TEXT NOT NULL DEFAULT '',
                country_name TEXT NOT NULL DEFAULT '',
                first_visited_at INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                PRIMARY KEY (kind, place_key)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visited_places_kind ON visited_places(kind, first_visited_at)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS place_candidates (
                cell_x INTEGER NOT NULL,
                cell_y INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                first_seen_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (cell_x, cell_y)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_place_candidates_retry ON place_candidates(next_attempt_ms)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progression_rewards (
                reward_key TEXT PRIMARY KEY,
                points INTEGER NOT NULL CHECK(points > 0),
                awarded_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progression_purchases (
                item_id TEXT PRIMARY KEY,
                points_spent INTEGER NOT NULL CHECK(points_spent >= 0),
                purchased_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS progression_counters (
                counter_key TEXT PRIMARY KEY,
                value INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Database upgrade $oldVersion -> $newVersion is unsupported")
    }

    companion object {
        private const val DB_NAME = "roadfog.db"
        private const val DB_VERSION = 10

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: AppDatabase(context.applicationContext).also { instance = it }
        }
    }
}
