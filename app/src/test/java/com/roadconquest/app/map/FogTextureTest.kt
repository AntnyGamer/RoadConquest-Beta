package com.roadconquest.app.map

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FogTextureTest {
    @Test fun patternContainsSoftCloudVariationAndTilesWithoutSharpSeams() {
        val bitmap = FogTexture.create()
        val size = FogTexture.SIZE
        val sampleStep = (size / 32).coerceAtLeast(1)
        val shades = (0 until size step sampleStep).flatMap { y ->
            (0 until size step sampleStep).map { x -> Color.red(bitmap.getPixel(x, y)) }
        }

        assertTrue("Cloud shading must remain visibly textured", shades.max() - shades.min() > 50)

        for (i in 0 until size) {
            assertTrue(abs(Color.red(bitmap.getPixel(0, i)) - Color.red(bitmap.getPixel(size - 1, i))) <= 2)
            assertTrue(abs(Color.red(bitmap.getPixel(i, 0)) - Color.red(bitmap.getPixel(i, size - 1))) <= 2)
            assertEquals(255, Color.alpha(bitmap.getPixel(i, i)))
        }

        // Natural fog should change gradually rather than forming hard checker/grid boundaries.
        var maxNeighborDelta = 0
        for (y in 0 until size step 4) {
            for (x in 0 until size - 1 step 4) {
                maxNeighborDelta = maxOf(
                    maxNeighborDelta,
                    abs(Color.red(bitmap.getPixel(x, y)) - Color.red(bitmap.getPixel(x + 1, y)))
                )
            }
        }
        for (y in 0 until size - 1 step 4) {
            for (x in 0 until size step 4) {
                maxNeighborDelta = maxOf(
                    maxNeighborDelta,
                    abs(Color.red(bitmap.getPixel(x, y)) - Color.red(bitmap.getPixel(x, y + 1)))
                )
            }
        }
        assertTrue("Fog texture should stay softly varying", maxNeighborDelta <= 8)
    }
    @Test fun precomputedNoiseIsPixelIdenticalToDirectLatticeEvaluation() {
        val size = 64
        val bitmap = FogTexture.create(size)
        val denominator = (size - 1).toDouble()

        fun wrap(value: Int, period: Int): Int {
            val remainder = value % period
            return if (remainder < 0) remainder + period else remainder
        }
        fun lattice(x: Int, y: Int, seed: Int): Double {
            var value = x.toLong() * 374_761_393L +
                y.toLong() * 668_265_263L +
                seed.toLong() * 69_069L
            value = (value xor (value ushr 13)) * 1_274_126_177L
            value = value xor (value ushr 16)
            return (value and 0xffffL).toDouble() / 32_767.5 - 1.0
        }
        fun smooth(value: Double): Double = value * value * (3.0 - 2.0 * value)
        fun noise(u: Double, v: Double, period: Int, seed: Int): Double {
            val px = u * period
            val py = v * period
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val sx = smooth(px - x0)
            val sy = smooth(py - y0)
            val n00 = lattice(wrap(x0, period), wrap(y0, period), seed)
            val n10 = lattice(wrap(x0 + 1, period), wrap(y0, period), seed)
            val n01 = lattice(wrap(x0, period), wrap(y0 + 1, period), seed)
            val n11 = lattice(wrap(x0 + 1, period), wrap(y0 + 1, period), seed)
            val top = n00 + (n10 - n00) * sx
            val bottom = n01 + (n11 - n01) * sx
            return top + (bottom - top) * sy
        }

        for (y in 0 until size) {
            val v = y / denominator
            for (x in 0 until size) {
                val u = x / denominator
                val warpX = 0.13 * noise(u, v, 3, 17) + 0.05 * noise(u, v, 7, 73)
                val warpY =
                    0.13 * noise(u + 0.37, v + 0.11, 3, 101) +
                    0.05 * noise(u + 0.19, v + 0.53, 7, 151)
                val warpedU = u + warpX
                val warpedV = v + warpY
                val cloud =
                    0.46 * noise(warpedU, warpedV, 2, 211) +
                    0.28 * noise(warpedU, warpedV, 4, 307) +
                    0.15 * noise(warpedU, warpedV, 8, 401) +
                    0.08 * noise(warpedU, warpedV, 16, 503) +
                    0.03 * noise(u, v, 32, 601)
                val normalized = (cloud + 0.50).coerceIn(0.0, 1.0)
                val mist = normalized * normalized * (3.0 - 2.0 * normalized)
                val shade = (mist * 72.0).roundToInt()
                assertEquals(
                    "Fog optimization must not change pixel ($x,$y)",
                    Color.rgb(14 + shade, 22 + shade, 34 + shade),
                    bitmap.getPixel(x, y)
                )
            }
        }
    }

}
