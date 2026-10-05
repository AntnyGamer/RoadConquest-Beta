package com.roadfog.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AppDatabaseMigrationTest {
    @Test fun beta2SchemaNineUpgradesWithoutLosingRoadHistory() {
        val context: android.content.Context = RuntimeEnvironment.getApplication()
        resetSingleton()
        val repository = TrackingRepository(context)
        repository.clearHistory()
        val fresh = TrackingRepository(context)
        fresh.upsertRoads(listOf(
            MatchedRoad("Migration Road", "[[-75.1,39.7],[-75.099,39.7]]", 100L, 200L, 1.0)
        ))
        assertEquals(1L, fresh.getSummary().roadsUnlockedCount)

        val helper = AppDatabase.get(context)
        val db = helper.writableDatabase
        for (table in listOf(
            "place_candidates", "visited_places", "progression_rewards",
            "progression_purchases", "progression_counters"
        )) {
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        db.execSQL("PRAGMA user_version = 9")
        helper.close()
        resetSingleton()

        val upgraded = TrackingRepository(context)
        assertEquals(1L, upgraded.getSummary().roadsUnlockedCount)
        val names = mutableSetOf<String>()
        upgraded.readableDatabase().rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table'", null
        ).use { cursor -> while (cursor.moveToNext()) names += cursor.getString(0) }
        assertTrue("progression tables are created during the v9 to v10 upgrade",
            setOf("visited_places", "place_candidates", "progression_rewards",
                "progression_purchases", "progression_counters").all(names::contains))
    }

    private fun resetSingleton() {
        AppDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
    }
}
