package com.roadfog.app.matching

import com.roadfog.app.data.TrackPoint
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class OsrmNetworkTest {
    private val points = listOf(
        TrackPoint(1, 40.0, -74.0, 3f, 5f, 0f, 100_000, false),
        TrackPoint(2, 40.0, -74.001, 99f, 5f, 0f, 100_000, false)
    )
    private val valid = """{"code":"Ok","tracepoints":[{"matchings_index":0,"waypoint_index":0,"alternatives_count":0,"location":[-74,40]},{"matchings_index":0,"waypoint_index":1,"alternatives_count":0,"location":[-74.001,40]}],"matchings":[{"confidence":0.9,"legs":[{"steps":[{"name":"Rue cité","distance":100,"geometry":{"type":"LineString","coordinates":[[-74,40],[-74.001,40]]}}]}]}]}"""

    @Test fun requestNormalizesTimesAndRadiusAndParsesUtf8RoadNames() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.start()
            val result = requireNotNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
            assertEquals("Rue cité", result.roads.single().name)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("100;101", request.queryParameter("timestamps"))
            assertEquals("5;75", request.queryParameter("radiuses"))
            assertEquals("0;1", request.queryParameter("waypoints"))
            assertEquals("false", request.queryParameter("tidy"))
            assertEquals("ignore", request.queryParameter("gaps"))
        }
    }

    @Test fun everyFixIsARequestedWaypointSoAmbiguousTailsCanBeWithheld() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"NoMatch"}"""))
            server.start()
            OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points + points.last().copy(id = 3))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)).requestUrl!!
            assertEquals("0;1;2", request.queryParameter("waypoints"))
        }
    }

    @Test fun rateLimitAndInvalidJsonAreRecoverableFailures() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody("not json"))
            server.start()
            val matcher = OsrmMatcher(server.url("/").toString().trimEnd('/'))
            assertNull(matcher.match(points))
            assertNull(matcher.match(points))
        }
    }

    @Test fun oversizedResponseIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(4 * 1024 * 1024 + 1) + valid))
            server.start()
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
        }
    }

    @Test fun incompletePositiveDistanceStepIsRejectedInsteadOfUnlockingPartialRoute() {
        MockWebServer().use { server ->
            val root = org.json.JSONObject(valid)
            root.getJSONArray("matchings").getJSONObject(0).getJSONArray("legs").getJSONObject(0)
                .getJSONArray("steps").put(org.json.JSONObject().put("distance", 100).put("name", "Missing geometry"))
            server.enqueue(MockResponse().setBody(root.toString()))
            server.start()
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(points))
        }
    }

    @Test fun oversizedTraceCannotBeSentToThePublicServer() {
        MockWebServer().use { server ->
            server.start()
            val tooMany = (1L..11L).map { points.first().copy(id = it) }
            assertNull(OsrmMatcher(server.url("/").toString().trimEnd('/')).match(tooMany))
            assertEquals(0, server.requestCount)
        }
    }
}
