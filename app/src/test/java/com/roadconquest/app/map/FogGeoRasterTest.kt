package com.roadconquest.app.map

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FogGeoRasterTest {
    private fun phase(position: Double, translation: Float, scale: Float): Double {
        val textureX = (position - translation) / scale
        return ((textureX % FogTexture.SIZE + FogTexture.SIZE) % FogTexture.SIZE)
    }

    private fun longitudeDelta(a: Double, b: Double): Double =
        ((a - b + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun phaseDelta(a: Double, b: Double): Double =
        ((a - b + FogTexture.SIZE / 2) % FogTexture.SIZE + FogTexture.SIZE) %
            FogTexture.SIZE - FogTexture.SIZE / 2

    @Test fun exploringAtSameLocationNeverTeleportsWhenRasterRepositions() {
        val corner = doubleArrayOf(40.0, -74.0, 40.0, -73.99,
            39.99, -73.99, 39.99, -74.0)
        val rasterA = FogGeoRaster.around(40.0, -74.0, 13.0, 896)
        val rasterB = FogGeoRaster.around(40.005, -74.005, 13.0, 896)
        val rasterC = FogGeoRaster.around(40.0, -74.0, 13.8, 896)
        for (raster in listOf(rasterA, rasterB, rasterC)) {
            val projected = raster.project(corner)
            val bounds = raster.corners()
            val left = bounds[1]
            val top = bounds[0]
            val metersPerPixel = raster.metersPerPixel
            assertTrue(metersPerPixel > 0)
            // Invert the raster projection, simulating MapLibre's attachment
            // of each image to the exact same real-world fog coordinates.
            for (i in projected.indices step 2) {
                val lng = left + Math.toDegrees(projected[i] * metersPerPixel / 6378137.0)
                val mercatorTop = 6378137.0 * ln(tan(PI / 4 + Math.toRadians(top) / 2))
                val northMeters = mercatorTop - projected[i + 1] * metersPerPixel
                val lat = Math.toDegrees(atan(sinh(northMeters / 6378137.0)))
                assertEquals(corner[i], lat, 1e-7)
                assertEquals(0.0, longitudeDelta(corner[i + 1], lng), 1e-7)
            }
        }
    }

    @Test fun fogTexturePhaseRemainsConstantAtSameWorldPointWhenPanning() {
        val a = FogGeoRaster.around(40.0, -74.0, 16.0, 1024)
        val b = FogGeoRaster.around(40.015, -73.99, 16.0, 1024)
        val location = doubleArrayOf(40.004, -74.002, 40.004, -74.002,
            40.004, -74.002, 40.004, -74.002)
        for (cloudMeters in listOf(
            FogBitmapRenderer.CLOUD_DETAIL_METERS,
            FogBitmapRenderer.CLOUD_MEDIUM_METERS,
            FogBitmapRenderer.CLOUD_BROAD_METERS
        )) {
            val mA = a.textureMatrix(cloudMeters)
            val mB = b.textureMatrix(cloudMeters)
            val xA = a.project(location)[0]
            val xB = b.project(location)[0]
            val yA = a.project(location)[1]
            val yB = b.project(location)[1]
            assertEquals("Cloud X must not drift when the camera pans",
                0.0, phaseDelta(phase(xA, mA[2], mA[0]), phase(xB, mB[2], mB[0])), 0.03)
            assertEquals("Cloud Y must not drift when the camera pans",
                0.0, phaseDelta(phase(yA, mA[5], mA[4]), phase(yB, mB[5], mB[4])), 0.03)
        }
    }

    @Test fun portraitAndRotatedViewportRemainInsideDetailedFogRaster() {
        for ((width, height) in listOf(720 to 1600, 1080 to 2400, 1600 to 720)) {
            val metersPerPixel = 2.4
            val fog = FogGeoRaster.around(
                40.0, -74.0, 16.0, 1024, width, height, metersPerPixel
            )
            val diagonal = hypot(width.toDouble(), height.toDouble()) * metersPerPixel
            val rasterSide = 1024 * fog.metersPerPixel
            val padding = 32 * fog.metersPerPixel
            // A rectangular viewport rotated by any bearing fits inside the
            // square raster even when its center is snapped by 32 pixels.
            assertTrue("All rotated phone corners remain covered by fog",
                rasterSide - 2 * padding > diagonal)
            val center = FogGeoRaster.around(
                40.0, -74.0, 16.15, 1024, width, height, metersPerPixel * 0.90
            )
            assertEquals("No raster LOD jitter for small fractional camera gestures",
                fog.metersPerPixel, center.metersPerPixel, 1e-9)
        }
    }

    @Test fun globalBitmapDoesNotMagicallyClearAnAreaWiderThanAMile() {
        val world = FogGeoRaster.fullWorld(512)
        val oneMile = FogGrid.corners(39.98, -75.02)
        val xy = world.project(oneMile)
        val minX = listOf(xy[0], xy[2], xy[4], xy[6]).min()
        val maxX = listOf(xy[0], xy[2], xy[4], xy[6]).max()
        assertTrue("A mile is far below one 512px world texel", maxX - minX < 0.1)
        assertTrue(world.metersPerPixel > 70_000)
    }
}
