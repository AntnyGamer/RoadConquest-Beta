package com.roadfog.app.achievements

import android.content.Context
import com.roadfog.app.data.DataSummary

data class AchievementProgress(
    val id: String,
    val title: String,
    val description: String,
    val progress: Double,
    val goal: Double,
    val unit: String
) {
    val unlocked: Boolean get() = progress >= goal
}

object Achievements {
    private const val PREFS = "roadfog_achievements"
    private const val KEY_INITIALIZED = "announcements_initialized"
    private const val KEY_ANNOUNCED = "announced_ids"
    private const val KEY_MAX_ROADS = "max_roads_seen"
    private const val METERS_PER_MILE = 1609.344

    fun progress(summary: DataSummary): List<AchievementProgress> {
        val miles = summary.distanceMeters / METERS_PER_MILE
        val roads = summary.roadsUnlockedCount.toDouble()
        return listOf(
            AchievementProgress("roads_10", "Newbie Explorer", "Unlock 10 roads", roads, 10.0, "roads"),
            AchievementProgress("roads_100", "Casual Explorer", "Unlock 100 roads", roads, 100.0, "roads"),
            AchievementProgress("roads_1000", "Road Conquerer", "Unlock 1,000 roads", roads, 1000.0, "roads"),
            AchievementProgress("miles_10", "Beginner Driver", "Travel 10 miles", miles, 10.0, "mi"),
            AchievementProgress("miles_100", "Average Driver", "Travel 100 miles", miles, 100.0, "mi"),
            AchievementProgress("miles_1000", "Expert Driver", "Travel 1,000 miles", miles, 1000.0, "mi")
        )
    }

    fun progress(context: Context, summary: DataSummary): List<AchievementProgress> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val highestRoads = maxOf(summary.roadsUnlockedCount, prefs.getLong(KEY_MAX_ROADS, 0L))
        if (highestRoads > prefs.getLong(KEY_MAX_ROADS, 0L)) {
            prefs.edit().putLong(KEY_MAX_ROADS, highestRoads).apply()
        }
        return progress(summary.copy(roadsUnlockedCount = highestRoads))
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun newlyUnlocked(context: Context, summary: DataSummary): List<AchievementProgress> {
        val current = progress(context, summary).filter { it.unlocked }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val announced = prefs.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty().toMutableSet()
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            announced += current.map { it.id }
            prefs.edit().putBoolean(KEY_INITIALIZED, true).putStringSet(KEY_ANNOUNCED, announced).apply()
            return emptyList()
        }
        val fresh = current.filter { it.id !in announced }
        if (fresh.isNotEmpty()) {
            announced += fresh.map { it.id }
            prefs.edit().putStringSet(KEY_ANNOUNCED, announced).apply()
        }
        return fresh
    }
}
