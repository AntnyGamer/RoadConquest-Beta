package com.roadconquest.app.map

import android.graphics.Matrix
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class FogTextureTransformTest {
    private fun capture(transform: FogTextureTransform, zoom: Double, longitude: Double = -74.0,
                        bearing: Double = 0.0): Matrix {
        val matrix = Matrix()
        val centerX = 6378137.0 * Math.toRadians(longitude)
        val centerY = 6378137.0 * ln(tan(PI / 4 + Math.toRadians(40.0) / 2))
        val metersPerPixel = 2.0.pow(18.0 - zoom)
        val angle = Math.toRadians(bearing)
        assertTrue(transform.update(40.0, longitude, zoom, { coords, screen ->
            for (i in coords.indices step 2) {
                val x = (6378137.0 * Math.toRadians(coords[i + 1]) - centerX) / metersPerPixel
                val y = -(6378137.0 * ln(tan(PI / 4 + Math.toRadians(coords[i]) / 2)) - centerY) / metersPerPixel
                screen[i] = 320.0 + x * cos(angle) - y * sin(angle)
                screen[i + 1] = 300.0 + x * sin(angle) + y * cos(angle)
            }
        }, matrix))
        return matrix
    }

    private fun phase(matrix: Matrix): FloatArray {
        val inverse = Matrix()
        assertTrue(matrix.invert(inverse))
        return floatArrayOf(320f, 300f).also { inverse.mapPoints(it) }
    }

    @Test fun fractionalZoomAndRotationKeepCloudPhaseAtCameraCenter() {
        val transform = FogTextureTransform()
        val initial = phase(capture(transform, 18.0))
        for (step in 1..20) {
            assertArrayEquals(initial, phase(capture(transform, 18.0 + step * 0.125, bearing = step * 6.0)), 0.002f)
        }
    }

    @Test fun panningAdvancesTheCloudPatternWithGeographicMovement() {
        val transform = FogTextureTransform()
        val initial = phase(capture(transform, 18.0))
        val moved = phase(capture(transform, 18.0, -73.9999))
        assertTrue(abs(moved[0] - initial[0]) > 5f)
        assertEquals(initial[1], moved[1], 0.002f)
    }

    @Test fun failedProjectionDoesNotResetThePreviousPhase() {
        val transform = FogTextureTransform()
        val initial = phase(capture(transform, 18.0))
        assertFalse(transform.update(40.0, -73.999, 19.0, { _, output -> output.fill(Double.NaN) }, Matrix()))
        assertArrayEquals(initial, phase(capture(transform, 19.0)), 0.002f)
    }
}
