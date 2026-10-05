package com.roadfog.app.progression

import android.content.Context
import android.os.BatteryManager
import com.roadfog.app.achievements.AchievementMetrics
import com.roadfog.app.achievements.Achievements
import com.roadfog.app.data.DataSummary
import com.roadfog.app.data.ProgressionRepository
import com.roadfog.app.data.ProgressionSnapshot
import com.roadfog.app.map.PlaceOverlayCache

object ProgressionManager {
    fun sync(context: Context, summary: DataSummary): ProgressionSnapshot {
        val repository = ProgressionRepository(context)
        var snapshot = repository.snapshot()
        val expectedGeneration = summary.historyGeneration.takeIf { it >= 0L }
        if (summary.roadsUnlockedCount > snapshot.rewardedRoads) {
            repository.syncRoadRewards(summary.roadsUnlockedCount, expectedGeneration)
            snapshot = repository.snapshot()
        }
        val achievements = Achievements.progress(context, summary, metrics(snapshot))
        var changed = false
        for (achievement in achievements) {
            if (achievement.unlocked && achievement.id !in snapshot.rewardedAchievements) {
                changed = repository.awardAchievement(
                    achievement.id,
                    achievement.rewardPoints,
                    expectedGeneration
                ) || changed
            }
        }
        return if (changed) repository.snapshot() else snapshot
    }

    fun recordBatteryFromSystem(context: Context): Boolean {
        val percent = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return false
        if (percent !in 0..5) return false
        return recordBatteryPercent(context, percent)
    }

    fun recordBatteryPercent(context: Context, percent: Int): Boolean {
        val repository = ProgressionRepository(context)
        val changed = repository.recordBatteryPercent(percent)
        if (changed) {
            val metrics = metrics(repository.snapshot())
            Achievements.progress(DataSummary(0, 0, null, null), metrics)
                .asSequence()
                .filter { it.unlocked && it.id.startsWith("battery_") }
                .forEach { repository.awardAchievement(it.id, it.rewardPoints) }
        }
        return changed
    }

    fun recordCompletedAd(context: Context): Long {
        val repository = ProgressionRepository(context)
        val count = repository.recordCompletedAd()
        val metrics = metrics(repository.snapshot())
        Achievements.progress(DataSummary(0, 0, null, null), metrics)
            .asSequence()
            .filter { it.unlocked && it.id.startsWith("ads_") }
            .forEach { repository.awardAchievement(it.id, it.rewardPoints) }
        return count
    }

    fun resetLocalProgression(context: Context) {
        ProgressionRepository(context).clearProgression()
        PlaceOverlayCache.clear(context)
        com.roadfog.app.util.Prefs.resetCosmetics(context)
        com.roadfog.app.util.Prefs.setPlaceOverlayMode(context, com.roadfog.app.map.PlaceOverlayMode.NONE)
        LauncherIcon.apply(context, false)
    }

    fun resolvePendingPlaces(context: Context, limit: Int = 6): Int {
        require(limit > 0)
        val repository = ProgressionRepository(context)
        var added = 0
        var processed = 0
        while (processed < limit) {
            val candidates = repository.pendingPlaceCandidates(limit - processed)
            if (candidates.isEmpty()) break
            for (candidate in candidates) {
                val discoveries = PlaceResolver.resolve(context, candidate)
                if (discoveries.isNullOrEmpty()) {
                    repository.deferCandidate(candidate)
                } else {
                    added += repository.resolveCandidate(candidate, discoveries)
                }
                processed++
                if (processed >= limit) break
            }
        }
        return added
    }

    fun metrics(snapshot: ProgressionSnapshot) = AchievementMetrics(
        towns = snapshot.towns,
        states = snapshot.states,
        countries = snapshot.countries,
        adsWatched = snapshot.adsWatched,
        lowestBatteryPercent = snapshot.lowestBatteryPercent
    )
}

/**
 * Integration point for a future rewarded-ad SDK. Call only from the SDK's confirmed-completion
 * callback; opening or dismissing an ad must never increment progress.
 */
object AdRewardBridge {
    fun onCompletedAd(context: Context): Long = ProgressionManager.recordCompletedAd(context.applicationContext)
}
