package com.roadconquest.app.data

import android.content.Context
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
class TrackingRepositoryTest {
    private lateinit var repository: TrackingRepository

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        repository = TrackingRepository(context)
        repository.readableDatabase().execSQL("DELETE FROM track_points")
        repository.readableDatabase().execSQL("DELETE FROM road_visits")
        repository.readableDatabase().execSQL("DELETE FROM roads")
    }

    private fun point(time: Long, lat: Double = 40.0, lon: Double = -74.0, speed: Float = 5f): Long =
        repository.insertLocation(Location("gps").apply {
            latitude = lat
            longitude = lon
            accuracy = 5f
            this.speed = speed
            this.time = time
        })

    @Test fun deletingHistoryClearsEverythingAndPreventsOldWorkersFromRestoringIt() {
        val context = RuntimeEnvironment.getApplication()
        val fix = Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        }
        val road = MatchedRoad("Deleted road", "[[-74,40],[-74,40.0002]]", 1, 2, 1.0)
        val oldId = repository.insertLocation(fix)
        repository.recordExploredPlace(fix)
        repository.upsertRoads(listOf(road))
        TrackingRepository(context).clearHistory()

        // These represent a GPS queue and a matcher returning after the delete finished.
        assertEquals(-1L, repository.insertLocation(fix))
        repository.insertLocations(listOf(fix))
        assertFalse(repository.recordExploredPlace(fix))
        repository.upsertRoads(listOf(road))
        repository.completeMatch(listOf(road), listOf(oldId))
        repository.markMatched(listOf(oldId))
        repository.deferMatching(listOf(oldId), 2_000_000L)
        assertEquals(0L, repository.getSummary().trackPointCount)
        assertEquals(0L, repository.getSummary().roadsUnlockedCount)
        assertEquals(0.0, repository.getSummary().distanceMeters, 0.0)
        assertTrue(repository.getExploredPlacesInBounds(90.0, 180.0, -90.0, -180.0).isEmpty())

        val restarted = TrackingRepository(context)
        assertTrue(restarted.insertLocation(fix) > oldId)
        restarted.upsertRoads(listOf(road))
        assertTrue(restarted.recordExploredPlace(fix))
        assertEquals(1L, restarted.getSummary().trackPointCount)
        assertEquals(1L, restarted.getSummary().roadsUnlockedCount)
    }

    @Test fun batchesKeepFullSegmentOverlapAndRetryContext() {
        val ids = (0..5).map { point(1_000_000L + it * 3_000L) }
        val first = repository.loadMatchingWindow(limit = 4)
        assertEquals(ids.takeLast(4), first.points.map { it.id })
        assertEquals(ids.takeLast(2).toSet(), first.markableIds)
        repository.markMatched(first.markableIds.toList())

        val middle = repository.loadMatchingWindow(limit = 4)
        assertEquals(ids.subList(1, 5), middle.points.map { it.id })
        assertEquals(ids.subList(2, 4).toSet(), middle.markableIds)
        repository.markMatched(middle.markableIds.toList())

        val oldest = repository.loadMatchingWindow(limit = 4)
        assertEquals(listOf(ids[0], ids[1], ids[2]), oldest.points.map { it.id })
        assertEquals(ids.take(2).toSet(), oldest.markableIds)
    }

    @Test fun pendingGpsIntervalsRemainContinuousUntilMatchingCompletes() {
        val ids = (0..4).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(listOf(ids.first(), ids.last()))
        val pending = repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(1, pending.size)
        assertEquals(5, org.json.JSONArray(pending.single().geometryJson).length())
        repository.markMatched(ids)
        assertTrue(repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0).isEmpty())
    }

    @Test fun recordedRouteNeverConnectsSeparateTripsOrMissingRawSamples() {
        point(1_000_000L)
        point(1_003_000L, lon = -73.9999)
        val removed = point(1_006_000L, lon = -73.9998)
        point(1_009_000L, lon = -73.9997)
        point(2_000_000L, lon = -73.9996)
        repository.readableDatabase().delete("track_points", "id = ?", arrayOf(removed.toString()))
        val pending = repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(1, pending.size)
        assertEquals(2, org.json.JSONArray(pending.single().geometryJson).length())
    }

    @Test fun overlappingMatcherWindowsNeverDeleteEarlierGeometry() {
        val ids = (0..3).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.completeMatch(
            listOf(MatchedRoad("Main", "[[-74,40],[-73.9999,40]]", 1_000_000L, 1_003_000L, 1.0)),
            ids.take(2)
        )
        // This mimics an overlapping matcher window. The second completion must not erase
        // the first piece merely because their time/anchor ranges touch.
        repository.completeMatch(
            listOf(MatchedRoad("Main", "[[-73.9999,40],[-73.9998,40]]", 1_003_000L, 1_006_000L, 1.0)),
            ids.subList(1, 3)
        )
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(2, roads.size)
        assertEquals(2, roads.map { it.geometryJson }.toSet().size)
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
    }

    @Test fun turnRetryGetsTwoNewerContextPointsAtProductionBatchSize() {
        val ids = (0..6).map { point(1_000_000L + it * 3_000L, lon = -74.0 + it * 0.0001) }
        repository.markMatched(listOf(ids[0], ids[1], ids[3], ids[4], ids[5], ids[6]))

        val retry = repository.loadMatchingWindow(limit = com.roadconquest.app.matching.OsrmMatcher.MAX_MATCH_POINTS)
        assertEquals(setOf(ids[2]), retry.markableIds)
        assertEquals(listOf(ids[1], ids[2], ids[3], ids[4]), retry.points.map { it.id })
    }

    @Test fun finalizationMakesDeferredTurnPointsImmediatelyEligible() {
        val ids = (0..2).map { point(1_000_000L + it * 3_000L) }
        repository.deferMatching(ids, System.currentTimeMillis() + 300_000L)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())

        repository.makePendingMatchingEligibleNow()
        assertEquals(ids, repository.loadMatchingWindow().points.map { it.id })
    }

    @Test fun separatedUnmatchedIslandsRetryWithResolvedNeighbors() {
        val ids = (0..5).map { point(1_000_000L + it * 3_000L) }
        repository.markMatched(listOf(ids[1], ids[3], ids[5]))

        val newest = repository.loadMatchingWindow(limit = 4)
        assertEquals(listOf(ids[3], ids[4], ids[5]), newest.points.map { it.id })
        assertEquals(setOf(ids[4]), newest.markableIds)
        repository.markMatched(newest.markableIds.toList())

        val middle = repository.loadMatchingWindow(limit = 4)
        assertEquals(listOf(ids[1], ids[2], ids[3]), middle.points.map { it.id })
        assertEquals(setOf(ids[2]), middle.markableIds)
    }

    @Test fun publicServerSizeBatchesDrainBacklogWithoutMissingBoundaryEdges() {
        val ids = (0 until 27).map { point(1_000_000L + it * 3_000L) }
        val covered = mutableSetOf<Pair<Long, Long>>()
        var batches = 0
        while (true) {
            val window = repository.loadMatchingWindow(limit = com.roadconquest.app.matching.OsrmMatcher.MAX_MATCH_POINTS)
            if (window.points.isEmpty()) break
            assertTrue(window.points.size in 2..10)
            covered += window.points.map { it.id }.zipWithNext()
            repository.markMatched(window.markableIds.toList())
            assertTrue("Backlog must make progress", ++batches <= 5)
        }
        assertEquals(4, batches)
        assertEquals(ids.zipWithNext().toSet(), covered)
    }

    @Test fun separateTripsAreNeverJoined() {
        point(1_000_000)
        point(1_003_000)
        val current = listOf(point(2_000_000), point(2_003_000))
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
    }

    @Test fun multiMinuteMovingGapNearTheSameAreaDoesNotInventAConnection() {
        val old = point(1_000_000L, 40.0000, speed = 5f)
        val current = listOf(
            point(1_120_000L, 40.0005, speed = 5f),
            point(1_123_000L, 40.0007, speed = 5f)
        )

        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertFalse(old in window.markableIds)

        val pending = repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(1, pending.size)
        assertEquals(
            2,
            org.json.JSONArray(pending.single().geometryJson).length()
        )
    }

    @Test fun nearbySavedStopAnchorReconnectsWithinTheSameParkingPause() {
        val ids = listOf(
            point(1_000_000L),
            point(1_600_000L, 40.0004, speed = 0f),
            point(1_603_000L, 40.0006)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(ids, window.points.map { it.id })
        assertEquals(ids.toSet(), window.markableIds)
    }

    @Test fun veryLongStopStartsANewMatchingSessionEvenWhenNearbyAndStationary() {
        point(1_000_000L, speed = 0f)
        val current = listOf(
            point(2_000_000L, 40.0004, speed = 0f),
            point(2_003_000L, 40.0006)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
    }

    @Test fun longGapDoesNotBridgeASeparateDistantDrive() {
        point(1_000_000L)
        val current = listOf(
            point(1_120_000L, 40.01),
            point(1_123_000L, 40.0102)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
    }

    @Test fun fortyFiveSecondGpsBlackoutCannotInventAConnectingRoad() {
        point(1_000_000L, 40.0)
        val current = listOf(
            point(1_045_000L, 40.01),
            point(1_048_000L, 40.0102)
        )
        val window = repository.loadMatchingWindow()
        assertEquals(current, window.points.map { it.id })
        assertEquals(current.toSet(), window.markableIds)
        assertEquals(1, repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0).size)
        assertEquals(2, org.json.JSONArray(
            repository.getPendingRouteInBounds(41.0, -73.0, 39.0, -75.0).single().geometryJson
        ).length())
    }

    @Test fun deferredNewestBatchDoesNotStarveOlderTrip() {
        val older = listOf(point(1_000_000), point(1_003_000))
        val newer = listOf(point(2_000_000), point(2_003_000))
        repository.deferMatching(newer, 10_000_000)
        assertEquals(older, repository.loadMatchingWindow(nowMillis = 5_000_000).points.map { it.id })
        assertEquals(newer, repository.loadMatchingWindow(nowMillis = 11_000_000).points.map { it.id })
    }

    @Test fun expiredRetryCanBeSelectedWithoutGivingUpFreshFirstBatchOrdering() {
        val ids = (0..5).map { point(1_000_000L + it * 3_000L) }
        repository.markMatched(listOf(ids[0], ids[2], ids[3], ids[5]))
        repository.deferMatching(listOf(ids[1]), 100L)

        val fresh = repository.loadMatchingWindow(limit = 4, nowMillis = 200L)
        assertEquals(setOf(ids[4]), fresh.markableIds)

        val retryCap = repository.oldestEligibleRetryId(nowMillis = 200L)
        assertEquals(ids[1], retryCap)
        val retry = repository.loadMatchingWindow(limit = 4, nowMillis = 200L, maxPendingId = retryCap)
        assertEquals(setOf(ids[1]), retry.markableIds)
        assertEquals(listOf(ids[0], ids[1], ids[2]), retry.points.map { it.id })
    }

    @Test fun reverseDriveUpdatesTheSameSegmentAndKeepsFirstUnlockTime() {
        repository.upsertRoads(listOf(MatchedRoad("Main St", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)))
        repository.upsertRoads(listOf(MatchedRoad("Main St", "[[-74.001,40],[-74,40]]", 300, 400, 1.0)))
        val road = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single()
        assertEquals("52f290b759a36294152d6bac", road.segmentId)
        assertEquals(100L, road.firstUnlockedAt)
        assertEquals(400L, road.lastDrivenAt)
        assertEquals(2, road.timesDriven)
        assertTrue(road.timesDrivenExact)
    }

    @Test fun sameNamedRoadsWithTheSameEndpointsDoNotOverwriteDifferentGeometry() {
        repository.upsertRoads(listOf(
            MatchedRoad("Loop Road", "[[-74,40],[-73.9995,40.0005],[-74.001,40]]", 100, 200, 1.0),
            MatchedRoad("Loop Road", "[[-74,40],[-74.0005,39.9995],[-74.001,40]]", 300, 400, 1.0)
        ))
        val roads = repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0)
        assertEquals(2, roads.size)
        assertEquals(2, roads.map { it.segmentId }.toSet().size)
        assertEquals(2, roads.map { it.geometryJson }.toSet().size)
        assertEquals(1L, repository.getSummary().roadsUnlockedCount)
    }

    @Test fun overlappingMatcherRetryDoesNotInflateTimesDriven() {
        val geometry = "[[-74,40],[-74.001,40]]"
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 100, 200, 1.0)))
        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 150, 250, 1.0)))
        assertEquals(1, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().timesDriven)

        repository.upsertRoads(listOf(MatchedRoad("Main St", geometry, 300, 400, 1.0)))
        assertEquals(2, repository.getRoadsInBounds(41.0, -73.0, 39.0, -75.0).single().timesDriven)
    }

    @Test fun roadHitTestingReturnsOnlyNearbySavedRoads() {
        repository.upsertRoads(listOf(
            MatchedRoad("Tap Me", "[[-74.001,40],[-74.000,40]]", 100, 200, 1.0)
        ))
        val road = repository.findRoadNear(40.0, -74.0005, 30.0)
        assertNotNull(road)
        assertEquals("Tap Me", road!!.name)
        assertEquals(85.0, repository.roadLengthMeters(road), 5.0)
        assertNull(repository.findRoadNear(40.01, -74.0005, 30.0))
    }

    @Test fun backwardWallClockChangeBreaksTheMatchingWindow() {
        point(2_000_000)
        point(2_003_000)
        val newId = point(1_000_000)
        assertEquals(listOf(newId), repository.loadMatchingWindow().points.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedRoadCannotSilentlyReportSuccessfulPersistence() {
        repository.upsertRoads(listOf(MatchedRoad("bad", "[[181,40],[182,40]]", 100, 200, 1.0)))
    }

    @Test fun completeMatchRollsBackRoadsWhenPointUpdateFails() {
        val id = point(1_000_000)
        val db = repository.readableDatabase()
        db.execSQL("CREATE TRIGGER fail_resolution BEFORE UPDATE OF matched ON track_points BEGIN SELECT RAISE(ABORT, 'injected update failure'); END")
        try {
            try {
                repository.completeMatch(listOf(MatchedRoad("Main", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)), listOf(id))
                fail("Expected the injected update failure")
            } catch (_: android.database.sqlite.SQLiteException) {
                assertEquals(0L, repository.getSummary().roadSegmentCount)
                assertEquals(setOf(id), repository.loadMatchingWindow().markableIds)
            }
        } finally {
            db.execSQL("DROP TRIGGER fail_resolution")
        }
    }

    @Test fun validCompleteMatchCommitsRoadAndResolutionTogether() {
        val id = point(1_000_000)
        repository.completeMatch(listOf(MatchedRoad("Main", "[[-74,40],[-74.001,40]]", 100, 200, 1.0)), listOf(id))
        assertEquals(1L, repository.getSummary().roadSegmentCount)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())
    }

    @Test fun deferredDeadlineIgnoresResolvedAndAlreadyEligiblePoints() {
        val ids = (0..2).map { point(1_000_000L + it * 3_000L) }
        repository.deferMatching(listOf(ids[0]), 5_000)
        repository.deferMatching(listOf(ids[1]), 10_000)
        repository.deferMatching(listOf(ids[2]), 15_000)
        assertEquals(10_000L, repository.nextDeferredMatchAttempt(6_000))
        repository.markMatched(listOf(ids[1]))
        assertEquals(15_000L, repository.nextDeferredMatchAttempt(6_000))
        assertNull(repository.nextDeferredMatchAttempt(16_000))
    }

    @Test fun resolutionHandlesMoreThanOneSqlParameterChunk() {
        val ids = (0..450).map { point(1_000_000L + it * 3_000L) }
        repository.markMatched(ids)
        assertTrue(repository.loadMatchingWindow().points.isEmpty())
        assertEquals(451L, repository.getSummary().trackPointCount)
    }

    @Test fun nonFiniteOptionalSensorFieldsDoNotTurnIntoNullDatabaseValues() {
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
            // Newer Android setters reject NaN themselves; older versions allow it.
            try { speed = Float.NaN } catch (_: IllegalArgumentException) { }
            try { bearing = Float.NaN } catch (_: IllegalArgumentException) { }
        })
        repository.readableDatabase().rawQuery("SELECT speed_mps,bearing_deg FROM track_points", null).use {
            assertTrue(it.moveToFirst())
            assertEquals(0f, it.getFloat(0), 0f)
            assertEquals(0f, it.getFloat(1), 0f)
        }
    }
    @Test fun boundedRoadQueryReportsWhetherViewportCoverageIsComplete() {
        repository.upsertRoads(listOf(
            MatchedRoad("One", "[[-74.0000,40.0000],[-74.0001,40.0000]]", 100, 200, 1.0),
            MatchedRoad("Two", "[[-74.0000,40.0001],[-74.0001,40.0001]]", 200, 300, 1.0),
            MatchedRoad("Three", "[[-74.0000,40.0002],[-74.0001,40.0002]]", 300, 400, 1.0)
        ))
        val truncated = repository.getRoadsInBoundsResult(40.01, -73.99, 39.99, -74.01, limit = 2)
        assertEquals(2, truncated.roads.size)
        assertFalse(truncated.complete)

        val complete = repository.getRoadsInBoundsResult(40.01, -73.99, 39.99, -74.01, limit = 3)
        assertEquals(3, complete.roads.size)
        assertTrue(complete.complete)

        val uncapped = repository.getRoadsInBoundsResult(
            40.01, -73.99, 39.99, -74.01, limit = Int.MAX_VALUE
        )
        assertEquals(3, uncapped.roads.size)
        assertTrue(uncapped.complete)
    }

    @Test fun pendingLineCrossingViewportRendersEvenWhenBothEndpointsAreOutside() {
        point(1_000_000L, lat = 40.0, lon = -74.01)
        point(1_030_000L, lat = 40.0, lon = -73.99)
        val pending = repository.getPendingRouteInBounds(
            north = 40.0005,
            east = -73.9999,
            south = 39.9995,
            west = -74.0001
        )
        assertEquals(1, pending.size)
        assertEquals(2, org.json.JSONArray(pending.single().geometryJson).length())
    }

}
