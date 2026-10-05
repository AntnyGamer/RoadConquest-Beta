package com.roadfog.app.progression

import android.content.Context
import android.os.BatteryManager
import com.roadfog.app.achievements.AchievementMetrics
import com.roadfog.app.achievements.Achievements
import com.roadfog.app.data.DataSummary
import com.roadfog.app.data.ProgressionRepository
import com.roadfog.app.data.ProgressionSnapshot

object ProgressionManager {
    fun sync(context: Context, summary: DataSummary): ProgressionSnapshot {
        val repository = ProgressionRepository(context)
        repository.syncRoadRewards(summary.roadsUnlockedCount)
        var snapshot = repository.snapshot()
        val achievements = Achievements.progress(context, summary, metrics(snapshot))
        for (achievement in achievements) {
            if (achievement.unlocked) {
                repository.awardAchievement(achievement.id, achievement.rewardPoints)
            }
        }
        snapshot = repository.snapshot()
        return snapshot
    }

    fun recordBatteryFromSystem(context: Context): Boolean {
        val percent = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return false
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
        val metrics = repository.snapshot().metrics()
        Achievements.progress(DataSummary(0, 0, null, null), metrics)
            .asSequence()
            .filter { it.unlocked && it.id.startsWith("ads_") }
            .forEach { repository.awardAchievement(it.id, it.rewardPoints) }
        return count
    }

    fun resolvePendingPlaces(context: Context, limit: Int = 6): Int {
        val repository = ProgressionRepository(context)
        var added = 0
        for (candidate in repository.pendingPlaceCandidates(limit)) {
            val discoveries = PlaceResolver.resolve(context, candidate)
            if (discoveries == null) {
                repository.deferCandidate(candidate)
            } else {
                added += repository.resolveCandidate(candidate, discoveries)
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
