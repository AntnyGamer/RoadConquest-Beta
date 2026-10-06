package com.roadconquest.app.map

import com.roadconquest.app.data.PlaceDiscovery
import com.roadconquest.app.data.PlaceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.StringReader
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class PlaceOverlayTest {
    @Test fun parserKeepsPolygonPopulationAndComputesArea() {
        val place = PlaceDiscovery(
            PlaceKind.COUNTRY, "us", "United States",
            visitedAt = 1L, latitude = 39.5, longitude = -74.5
        )
        val body = """
            [{
              "name":"United States",
              "addresstype":"country",
              "address":{"country":"United States"},
              "extratags":{"population":"331,000,000"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.0,39.0],[-74.0,39.0],[-74.0,40.0],[-75.0,40.0],[-75.0,39.0]
              ]]}
            }]
        """.trimIndent()

        val data = PlaceOverlayClient.parseResponse(place, body)
        assertNotNull(data)
        data!!
        assertEquals("United States", data.name)
        assertEquals(331_000_000L, data.population)
        assertTrue(data.areaSquareKilometers > 8_000.0)
        assertEquals("Polygon", JSONObject(data.geometryJson).getString("type"))
    }

    @Test fun providerConfigReadIsStrictlyBounded() {
        assertEquals(
            "https://example.com",
            PlaceOverlayClient.readConfigEndpoint(StringReader("  https://example.com/  \nignored"))
        )
        org.junit.Assert.assertThrows(IOException::class.java) {
            PlaceOverlayClient.readConfigEndpoint(
                StringReader("x".repeat(2_049))
            )
        }
    }

    @Test fun staleFetchCannotRecreateOverlayCacheAfterDeletion() {
        val context = RuntimeEnvironment.getApplication()
        val place = PlaceDiscovery(
            PlaceKind.TOWN, "us|new jersey|old", "Old Town", "New Jersey", "United States",
            1L, 40.0, -74.5
        )
        PlaceOverlayCache.clear(context)
        val generation = PlaceOverlayCache.generation()
        PlaceOverlayCache.clear(context)

        assertFalse(
            PlaceOverlayCache.write(
                context,
                place,
                PlaceOverlayData(
                    place.key,
                    place.displayName,
                    place.kind,
                    1_000L,
                    1.0,
                    """{"type":"Polygon","coordinates":[[[-75,39],[-74,39],[-74,40],[-75,39]]]}"""
                ),
                generation
            )
        )
        assertFalse(PlaceOverlayCache.read(context, place).cached)
    }

    @Test fun sameNamedTownUsesTheBoundaryNearestTheDiscoveredLocation() {
        val place = PlaceDiscovery(
            PlaceKind.TOWN,
            "us|new jersey|washington township",
            "Washington Township",
            "New Jersey",
            "United States",
            1L,
            40.0000,
            -75.0000
        )
        val body = """
            [
              {
                "name":"Washington Township",
                "lat":"41.0000",
                "lon":"-74.0000",
                "addresstype":"administrative",
                "address":{
                  "township":"Washington Township",
                  "state":"New Jersey",
                  "country":"United States"
                },
                "extratags":{"population":"111"},
                "geojson":{"type":"Polygon","coordinates":[[
                  [-74.01,40.99],[-73.99,40.99],[-73.99,41.01],[-74.01,41.01],[-74.01,40.99]
                ]]}
              },
              {
                "name":"Washington Township",
                "lat":"40.0100",
                "lon":"-75.0100",
                "addresstype":"administrative",
                "address":{
                  "township":"Washington Township",
                  "state":"New Jersey",
                  "country":"United States"
                },
                "extratags":{"population":"222"},
                "geojson":{"type":"Polygon","coordinates":[[
                  [-75.02,40.00],[-75.00,40.00],[-75.00,40.02],[-75.02,40.02],[-75.02,40.00]
                ]]}
              }
            ]
        """.trimIndent()

        val data = PlaceOverlayClient.parseResponse(place, body)
        assertNotNull(data)
        assertEquals(222L, data!!.population)
    }

    @Test fun wrongAdministrativeResultIsRejected() {
        val place = PlaceDiscovery(
            PlaceKind.STATE, "us|new jersey", "New Jersey", "United States", "United States",
            1L, 40.0, -74.5
        )
        val body = """
            [{
              "name":"New Jersey",
              "addresstype":"shop",
              "address":{"country":"Canada","state":"Ontario"},
              "geojson":{"type":"Polygon","coordinates":[[
                [-75.0,39.0],[-74.0,39.0],[-74.0,40.0],[-75.0,40.0],[-75.0,39.0]
              ]]}
            }]
        """.trimIndent()
        assertEquals(null, PlaceOverlayClient.parseResponse(place, body))
    }
}
