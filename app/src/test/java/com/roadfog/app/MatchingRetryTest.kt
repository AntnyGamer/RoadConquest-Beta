package com.roadfog.app

import android.location.Location
import com.roadfog.app.data.TrackingRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class MatchingRetryTest {
    private fun field(service: TrackingService, name: String) = TrackingService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun repository(): TrackingRepository = TrackingRepository(RuntimeEnvironment.getApplication()).apply {
        readableDatabase().execSQL("DELETE FROM track_points")
        readableDatabase().execSQL("DELETE FROM roads")
    }
    private fun point(repo: TrackingRepository) = repo.insertLocation(Location("gps").apply {
        latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000
    })

    @Test fun restartWithOnlyDeferredPointsArmsAPersistentRetry() {
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        val repo = repository()
        repo.deferMatching(listOf(point(repo)), System.currentTimeMillis() + 120_000)
        field(service, "repository").set(service, repo)
        field(service, "ready").setBoolean(service, true)
        val executor = field(service, "matchingExecutor").get(service) as ScheduledExecutorService
        try {
            val run = TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            run.invoke(service, true)
            executor.submit {}.get(10, TimeUnit.SECONDS)
            val retry = field(service, "deferredRetry").get(service) as ScheduledFuture<*>
            assertTrue(retry.getDelay(TimeUnit.MILLISECONDS) in 1..120_000)
        } finally {
            field(service, "ready").setBoolean(service, false)
            controller.destroy()
        }
    }

    @Test fun partialMatchRetriesOnlyTheUnmatchedPointSoon() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"code":"Ok","tracepoints":[{"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},null,{"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.001,40]}],"matchings":[{"confidence":0.95,"legs":[{"steps":[{"name":"Test road","distance":100,"geometry":{"type":"LineString","coordinates":[[-74,40],[-74.001,40]]}}]}]}]}"""
            ))
            server.start()

            val controller = Robolectric.buildService(TrackingService::class.java)
            val service = controller.get()
            val repo = repository()
            val ids = (0..2).map { index -> repo.insertLocation(Location("gps").apply {
                latitude = 40.0; longitude = -74.0 - index * 0.0005
                accuracy = 5f; time = 1_000_000L + index * 3_000L
            }) }
            field(service, "repository").set(service, repo)
            field(service, "matcher").set(service, com.roadfog.app.matching.OsrmMatcher(server.url("/").toString().trimEnd('/')))
            field(service, "ready").setBoolean(service, true)
            val executor = field(service, "matchingExecutor").get(service) as ScheduledExecutorService
            val run = TrackingService::class.java.getDeclaredMethod("maybeRunMatching", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            try {
                run.invoke(service, true)
                executor.submit {}.get(10, TimeUnit.SECONDS)

                assertEquals(1, server.requestCount)
                assertEquals(1L, repo.getSummary().roadSegmentCount)
                val now = System.currentTimeMillis()
                val retryAt = requireNotNull(repo.nextDeferredMatchAttempt(now))
                assertTrue(retryAt - now in 20_000..30_000)
                assertEquals(setOf(ids[1]), repo.loadMatchingWindow(nowMillis = retryAt).markableIds)
            } finally {
                field(service, "ready").setBoolean(service, false)
                controller.destroy()
            }
        }
    }

    @Test fun laterBatchDeadlineGetsATimerAfterEarlierBatchResolves() {
        val controller = Robolectric.buildService(TrackingService::class.java)
        val service = controller.get()
        val repo = repository()
        val first = point(repo); val second = point(repo)
        val now = System.currentTimeMillis()
        repo.deferMatching(listOf(first), now + 120_000)
        repo.deferMatching(listOf(second), now + 240_000)
        field(service, "repository").set(service, repo)
        field(service, "ready").setBoolean(service, true)
        val method = TrackingService::class.java.getDeclaredMethod("scheduleDeferredRetry", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            method.invoke(service, false)
            val firstTimer = field(service, "deferredRetry").get(service) as ScheduledFuture<*>
            repo.markMatched(listOf(first))
            method.invoke(service, false)
            val next = field(service, "deferredRetry").get(service) as ScheduledFuture<*>
            assertTrue(firstTimer.isCancelled)
            assertTrue(next.getDelay(TimeUnit.MILLISECONDS) in 180_000..240_000)
        } finally {
            field(service, "ready").setBoolean(service, false)
            controller.destroy()
        }
    }
}
