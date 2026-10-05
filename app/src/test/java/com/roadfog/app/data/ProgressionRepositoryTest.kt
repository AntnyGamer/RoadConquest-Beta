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

    @Test fun freshBaselineWaitsForOldestRecordedLocation() {
        val progression = ProgressionRepository(context)
        val db = TrackingRepository(context).readableDatabase()
        fun candidate(x: Long, seen: Long, retryAt: Long) {
            db.execSQL(
                """INSERT INTO place_candidates(
                    cell_x,cell_y,latitude,longitude,first_seen_at,attempts,next_attempt_ms
                ) VALUES(?,?,?,?,?,0,?)""",
                arrayOf(x, x, 39.7 + x / 10_000.0, -75.1, seen, retryAt)
            )
        }
        candidate(10, 1_000L, 5_000L)
        candidate(20, 2_000L, 0L)

        // A later eligible location cannot become the zero-point baseline while the actual
        // starting location is waiting for its retry.
        assertTrue(progression.pendingPlaceCandidates(nowMillis = 3_000L).isEmpty())

        val oldest = progression.pendingPlaceCandidates(nowMillis = 6_000L).single()
        assertEquals(10L, oldest.cellX)
        progression.resolveCandidate(
            oldest,
            listOf(
                PlaceDiscovery(
                    PlaceKind.COUNTRY, "us", "United States",
                    visitedAt = oldest.visitedAt, latitude = oldest.latitude, longitude = oldest.longitude
                )
            )
        )

        // Once the baseline exists, later candidates are free to resolve normally.
        assertEquals(20L, progression.pendingPlaceCandidates(nowMillis = 6_000L).single().cellX)
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
