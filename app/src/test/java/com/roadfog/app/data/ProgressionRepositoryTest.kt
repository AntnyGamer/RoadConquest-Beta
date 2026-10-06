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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class ProgressionRepositoryTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        AppDatabase::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }
        ProgressionRepository(context).clearProgression()
        Achievements.reset(context)
    }

    @Test fun resetLocalProgressionAlsoClearsAchievementPreferenceState() {
        Achievements.progress(context, DataSummary(0, 0, null, null, 0.0, 100))
        assertTrue(
            Achievements.progress(context, DataSummary(0, 0, null, null, 0.0, 0))
                .first { it.id == "roads_10" }.unlocked
        )

        ProgressionManager.resetLocalProgression(context)

        assertFalse(
            Achievements.progress(context, DataSummary(0, 0, null, null, 0.0, 0))
                .first { it.id == "roads_10" }.unlocked
        )
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

    @Test fun firstResolvedPlacesAfterResetBecomeUnrewardedBaseline() {
        val progression = ProgressionRepository(context)
        val candidate = PendingPlaceCandidate(10, 20, 39.7, -75.1, 1_000L, 0)
        val baseline = listOf(
            PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States", visitedAt = 1_000L, latitude = 39.7, longitude = -75.1),
            PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States", 1_000L, 39.7, -75.1),
            PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|glassboro", "Glassboro", "New Jersey", "United States", 1_000L, 39.7, -75.1)
        )

        assertEquals(0, progression.resolveCandidate(candidate, baseline))
        progression.snapshot().let {
            assertEquals(0L, it.balance)
            assertEquals(0L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
        }
        assertEquals(listOf("United States"), progression.visitedPlaces(PlaceKind.COUNTRY).map { it.displayName })
        assertEquals(listOf("New Jersey"), progression.visitedPlaces(PlaceKind.STATE).map { it.displayName })
        assertEquals(listOf("Glassboro"), progression.visitedPlaces(PlaceKind.TOWN).map { it.displayName })

        val nextTown = baseline.take(2) + PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|pitman", "Pitman", "New Jersey", "United States",
            2_000L, 39.73, -75.13
        )
        assertEquals(
            1,
            progression.resolveCandidate(
                candidate.copy(cellX = 11, visitedAt = 2_000L, latitude = 39.73, longitude = -75.13),
                nextTown
            )
        )
        progression.snapshot().let {
            assertEquals(100L, it.balance)
            assertEquals(1L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
        }
    }

    @Test fun stalePreResetWorkCannotRestoreProgressAfterDeletion() {
        val tracking = TrackingRepository(context)
        tracking.upsertRoads(
            listOf(
                MatchedRoad(
                    "Before reset",
                    "[[-74.0,40.0],[-74.001,40.0]]",
                    1_000L,
                    2_000L,
                    1.0
                )
            )
        )
        val staleSummary = tracking.getSummary()
        assertEquals(1L, staleSummary.roadsUnlockedCount)

        // Simulate a long-lived/queued progression writer that existed before deletion.
        val staleProgression = ProgressionRepository(context)
        tracking.clearHistory()

        ProgressionManager.sync(context, staleSummary)
        assertEquals(0L, ProgressionRepository(context).snapshot().balance)
        assertEquals(0L, ProgressionRepository(context).snapshot().rewardedRoads)

        val queuedLocation = android.location.Location("gps").apply {
            latitude = 39.9
            longitude = -75.0
            accuracy = 5f
            time = 3_000L
        }
        assertFalse(staleProgression.recordBaselineCandidate(queuedLocation))
        assertTrue(ProgressionRepository(context).pendingPlaceCandidates(nowMillis = 4_000L).isEmpty())
    }

    @Test fun failedBaselineGeocodingRetriesPromptly() {
        val progression = ProgressionRepository(context)
        val fix = android.location.Location("gps").apply {
            latitude = 39.9
            longitude = -75.0
            accuracy = 5f
            time = 1_000L
        }
        assertTrue(progression.recordBaselineCandidate(fix))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 2_000L).single()

        progression.deferCandidate(baseline, nowMillis = 10_000L)

        assertTrue(progression.pendingPlaceCandidates(nowMillis = 69_999L).isEmpty())
        val retry = progression.pendingPlaceCandidates(nowMillis = 70_000L).single()
        assertEquals(1, retry.attempts)
        assertEquals(baseline.latitude, retry.latitude, 0.0)
        assertEquals(baseline.longitude, retry.longitude, 0.0)
    }

    @Test fun freshBaselineUsesExactFirstLiveFixAndBlocksOrdinaryCandidatesUntilThen() {
        val progression = ProgressionRepository(context)

        // Ordinary sparse discoveries may already exist, but they cannot decide a fresh baseline.
        val later = android.location.Location("gps").apply {
            latitude = 40.1234
            longitude = -75.1234
            time = 2_000L
            accuracy = 5f
        }
        assertTrue(progression.recordPlaceCandidate(later))
        assertTrue(progression.pendingPlaceCandidates(nowMillis = 3_000L).isEmpty())

        // The first good live fix is stored exactly rather than snapped to the 2 km candidate grid.
        val startFix = android.location.Location("gps").apply {
            latitude = 39.987654
            longitude = -74.876543
            time = 3_000L
            accuracy = 5f
        }
        assertTrue(progression.recordBaselineCandidate(startFix))
        val baseline = progression.pendingPlaceCandidates(nowMillis = 4_000L).single()
        assertEquals(startFix.latitude, baseline.latitude, 0.0)
        assertEquals(startFix.longitude, baseline.longitude, 0.0)
        assertEquals(startFix.time, baseline.visitedAt)

        assertEquals(
            0,
            progression.resolveCandidate(
                baseline,
                listOf(
                    PlaceDiscovery(PlaceKind.COUNTRY, "us", "United States",
                        visitedAt = baseline.visitedAt, latitude = baseline.latitude, longitude = baseline.longitude),
                    PlaceDiscovery(PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
                        baseline.visitedAt, baseline.latitude, baseline.longitude),
                    PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|start", "Start", "New Jersey", "United States",
                        baseline.visitedAt, baseline.latitude, baseline.longitude)
                )
            )
        )
        progression.snapshot().let {
            assertEquals(0L, it.balance)
            assertEquals(0L, it.towns)
            assertEquals(0L, it.states)
            assertEquals(0L, it.countries)
        }

        // Once the exact baseline exists, an ordinary later place can earn discovery credit.
        val next = progression.pendingPlaceCandidates(nowMillis = 4_000L).single()
        assertEquals(
            1,
            progression.resolveCandidate(
                next,
                listOf(
                    PlaceDiscovery(PlaceKind.TOWN, "us|new jersey|later", "Later", "New Jersey", "United States",
                        next.visitedAt, next.latitude, next.longitude)
                )
            )
        )
        assertEquals(100L, progression.snapshot().balance)
        assertEquals(1L, progression.snapshot().towns)
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
