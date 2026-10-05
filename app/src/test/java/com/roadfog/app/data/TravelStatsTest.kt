package com.roadfog.app.data

import android.location.Location
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class TravelStatsTest {
    private lateinit var repo: TrackingRepository

    @Before fun setup() {
        repo = TrackingRepository(RuntimeEnvironment.getApplication())
        repo.readableDatabase().execSQL("DELETE FROM track_points")
        repo.readableDatabase().execSQL("DELETE FROM roads")
    }

    private fun point(lon: Double, stamp: Long) = Location("gps").apply {
        latitude = 0.0; longitude = lon; accuracy = 5f; time = stamp
    }

    @Test fun repeatDriveAddsMileageWithoutAddingTheSameNamedRoadTwice() {
        repo.insertLocations(listOf(point(0.0, 1_000), point(0.001, 11_000), point(0.0, 21_000)))
        repo.upsertRoads(listOf(
            MatchedRoad("Main St", "[[0,0],[0.001,0]]", 1, 2, 1.0),
            MatchedRoad(" main st ", "[[0.001,0],[0.002,0]]", 1, 2, 1.0),
            MatchedRoad("Oak St", "[[0,0],[0,0.001]]", 1, 2, 1.0)
        ))
        val stats = repo.getSummary()
        assertEquals(222.64, stats.distanceMeters, 1.0)
        assertEquals(2L, stats.roadsUnlockedCount)
        assertEquals(3L, stats.roadSegmentCount)
        assertEquals(1_000L, stats.firstTrackAt)
        assertEquals(21_000L, stats.lastTrackAt)
        assertEquals(stats.distanceMeters, TrackingRepository(RuntimeEnvironment.getApplication()).getSummary().distanceMeters, 0.0)
    }

    @Test fun disconnectedSameNameRoadsCountSeparatelyWhileTouchingFragmentsStayOneRoad() {
        repo.upsertRoads(listOf(
            MatchedRoad("Main St", "[[-74.003,40],[-74.002,40]]", 1, 2, 1.0),
            MatchedRoad(" main st ", "[[-74.001,40],[-74.000,40]]", 1, 2, 1.0)
        ))
        assertEquals(2L, repo.getSummary().roadsUnlockedCount)

        repo.upsertRoads(listOf(
            MatchedRoad("MAIN ST", "[[-74.002,40],[-74.001,40]]", 3, 4, 1.0)
        ))
        val connected = repo.getSummary()
        assertEquals(1L, connected.roadsUnlockedCount)
        assertEquals(3L, connected.roadSegmentCount)
    }

    @Test fun overlappingSameNameMatcherFragmentsCountAsOneRoad() {
        repo.upsertRoads(listOf(
            MatchedRoad("Broadway", "[[-74.003,40],[-74.000,40]]", 1, 2, 1.0),
            MatchedRoad("broadway", "[[-74.002,40],[-73.999,40]]", 3, 4, 1.0)
        ))
        assertEquals(1L, repo.getSummary().roadsUnlockedCount)
        assertEquals(2L, repo.getSummary().roadSegmentCount)
    }

    @Test fun separateTripsClockChangesAndImpossibleJumpsDoNotAddMiles() {
        repo.insertLocations(listOf(point(0.0, 1_000), point(0.001, 11_000), point(1.0, 71_000), point(1.001, 81_000), point(2.0, 82_000), point(3.0, 80_000)))
        assertEquals(222.64, repo.getSummary().distanceMeters, 1.0)
        assertEquals(0.0, TravelDistance.between(Double.NaN, 0.0, 1, 0.0, 0.0, 2), 0.0)
        assertEquals(0.0, TravelDistance.between(0.0, 0.0, 2, 0.0, 0.1, 1), 0.0)
    }

    @Test fun failedBatchRollsBackMileageAndPoints() {
        repo.insertLocation(point(0.0, 1_000))
        val db = repo.readableDatabase()
        db.execSQL("CREATE TEMP TRIGGER reject_second BEFORE INSERT ON track_points WHEN NEW.longitude > 0.0015 BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        try {
            assertTrue(runCatching { repo.insertLocations(listOf(point(0.001, 11_000), point(0.002, 21_000))) }.isFailure)
            assertEquals(1L, repo.getSummary().trackPointCount)
            assertEquals(0.0, repo.getSummary().distanceMeters, 0.0)
        } finally { db.execSQL("DROP TRIGGER reject_second") }
    }

}
