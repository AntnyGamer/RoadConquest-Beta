package com.roadfog.app.map

import com.roadfog.app.data.PlaceDiscovery
import com.roadfog.app.data.PlaceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val data = assertNotNull(PlaceOverlayClient.parseResponse(place, body))
        data!!
        assertEquals("United States", data.name)
        assertEquals(331_000_000L, data.population)
        assertTrue(data.areaSquareKilometers > 8_000.0)
        assertEquals("Polygon", JSONObject(data.geometryJson).getString("type"))
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
