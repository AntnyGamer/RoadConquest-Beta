package com.roadfog.app.data

import com.roadfog.app.achievements.Achievements
import com.roadfog.app.progression.AdRewardBridge
import com.roadfog.app.progression.ProgressionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ProgressionRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication<android.content.Context>()

    @Before fun reset() {
        ProgressionRepository(context).clearProgression()
        Achievements.reset(context)
    }

    @Test fun roadAndAchievementPointsAreIdempotentAndNeverRetract() {
        val progression = ProgressionRepository(context)
        progression.syncRoadRewards(10)
        assertEquals(50L, progression.snapshot().balance)

        progression.syncRoadRewards(10)
        progression.syncRoadRewards(8)
        assertEquals(50L, progression.snapshot().balance)
        assertEquals(10L, progression.snapshot().rewardedRoads)

        assertTrue(progression.awardAchievement("test", 100))
        assertFalse(progression.awardAchievement("test", 100))
        assertEquals(150L, progression.snapshot().balance)
    }

    @Test fun newPlacesPayOnceAndPurchasesCannotOverspend() {
        val progression = ProgressionRepository(context)
        val town = PlaceDiscovery(
            PlaceKind.TOWN, "us|nj|glassboro", "Glassboro", "New Jersey", "United States",
            1_000L, 39.7, -75.1
        )
        assertTrue(progression.recordPlace(town))
        assertFalse(progression.recordPlace(town))
        assertEquals(100L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().towns)

        assertEquals(PurchaseResult.INSUFFICIENT_POINTS, progression.purchase("too_expensive", 101))
        assertEquals(PurchaseResult.PURCHASED, progression.purchase("affordable", 80))
        assertEquals(PurchaseResult.OWNED, progression.purchase("affordable", 80))
        assertEquals(20L, progression.snapshot().balance)
        assertEquals(80L, progression.snapshot().pointsSpent)
    }

    @Test fun batteryAchievementsAwardAtFiveAndOnePercentOnlyOnce() {
        assertTrue(ProgressionManager.recordBatteryPercent(context, 5))
        assertEquals(300L, ProgressionRepository(context).snapshot().balance)
        assertFalse(ProgressionManager.recordBatteryPercent(context, 5))
        assertEquals(300L, ProgressionRepository(context).snapshot().balance)

        assertTrue(ProgressionManager.recordBatteryPercent(context, 1))
        assertEquals(1_300L, ProgressionRepository(context).snapshot().balance)
        assertTrue(ProgressionManager.recordBatteryPercent(context, 0))
        assertEquals(1_300L, ProgressionRepository(context).snapshot().balance)
        assertEquals(0, ProgressionRepository(context).snapshot().lowestBatteryPercent)
    }

    @Test fun completedAdHookUnlocksRequestedFiveAndTenViewMilestones() {
        repeat(4) { AdRewardBridge.onCompletedAd(context) }
        assertEquals(0L, ProgressionRepository(context).snapshot().balance)
        assertEquals(5L, AdRewardBridge.onCompletedAd(context))
        assertEquals(100L, ProgressionRepository(context).snapshot().balance)

        repeat(5) { AdRewardBridge.onCompletedAd(context) }
        val snapshot = ProgressionRepository(context).snapshot()
        assertEquals(10L, snapshot.adsWatched)
        assertEquals(300L, snapshot.balance)
    }
}
