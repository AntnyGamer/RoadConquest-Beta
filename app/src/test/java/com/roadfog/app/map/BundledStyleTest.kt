package com.roadfog.app.map

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class BundledStyleTest {
    @Test fun everyMapModeLoadsALocalStyleWithAbsoluteOnlineResourcesAndCredits() {
        val app = RuntimeEnvironment.getApplication()
        MapMode.entries.forEach { mode ->
            val json = JSONObject(app.assets.open(mode.styleAsset).bufferedReader().use { it.readText() })
            assertEquals(8, json.getInt("version"))
            assertTrue(json.getJSONArray("layers").length() > 10)
            assertTrue(json.getString("glyphs").startsWith("https://"))
            assertTrue(json.getString("sprite").startsWith("https://"))
            val source = json.getJSONObject("sources").getJSONObject("openmaptiles")
            assertTrue(source.getString("url").startsWith("https://"))
            assertTrue(source.getString("attribution").contains("OpenStreetMap"))
        }
        val credits = app.assets.open("map-credits.txt").bufferedReader().use { it.readText() }
        assertTrue(credits.contains("Mapbox"))
        assertTrue(credits.contains("CartoDB"))
        assertTrue(credits.contains("BSD"))
    }

    @Test fun satelliteUsesRealImageryBelowLabelsAndCannotBeCoveredByStreetFills() {
        val app = RuntimeEnvironment.getApplication()
        val layers = JSONObject(app.assets.open(MapMode.TERRAIN.styleAsset).bufferedReader().use { it.readText() }).getJSONArray("layers")
        assertEquals("raster", layers.getJSONObject(1).getString("type"))
        assertEquals("satellite", layers.getJSONObject(1).getString("source"))
        assertTrue((2 until layers.length()).all { layers.getJSONObject(it).getString("type") == "symbol" })
        val source = JSONObject(app.assets.open(MapMode.TERRAIN.styleAsset).bufferedReader().use { it.readText() })
            .getJSONObject("sources").getJSONObject("satellite")
        assertTrue(source.getJSONArray("tiles").getString(0).contains("World_Imagery/MapServer/tile/{z}/{y}/{x}"))
        assertTrue(source.getString("attribution").contains("Esri"))
        assertEquals("Satellite", MapMode.TERRAIN.label)
    }
}
