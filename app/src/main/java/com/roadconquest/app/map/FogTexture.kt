package com.roadconquest.app.map

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Seamless, static fog generated once and reused by the bitmap shader.
 *
 * Periodic fractal value-noise replaces the old stacked sine/cosine waves. That keeps the
 * texture inexpensive and tile-safe while removing the straight bands/intersections that could
 * read as a grid on the map.
 */
object FogTexture {
    const val SIZE = 512

    fun create(size: Int = SIZE): Bitmap {
        require(size > 0 && size and (size - 1) == 0)
        val pixels = IntArray(size * size)
        // Only 73 shades are possible. Build their exact colors once rather than calling
        // Color.rgb for every texel; output is pixel-for-pixel identical.
        val palette = IntArray(73) { shade -> Color.rgb(24 + shade, 25 + shade, 27 + shade) }
        // The final row/column intentionally sample the same mathematical point as the first,
        // so REPEAT filtering cannot expose a tile seam.
        val denominator = (size - 1).coerceAtLeast(1).toDouble()
        val samples = DoubleArray(size) { it / denominator }

        // Lattice values depend only on period + seed. Precompute them once instead of hashing
        // the same corner values millions of times while building the 512 px texture. Sampling
        // math is unchanged, so this is pixel-for-pixel identical to direct lattice evaluation.
        val warpX3 = NoiseLayer(3, 17)
        val warpX7 = NoiseLayer(7, 73)
        val warpY3 = NoiseLayer(3, 101)
        val warpY7 = NoiseLayer(7, 151)
        val cloud2 = NoiseLayer(2, 211)
        val cloud4 = NoiseLayer(4, 307)
        val cloud8 = NoiseLayer(8, 401)
        val cloud16 = NoiseLayer(16, 503)
        val cloud32 = NoiseLayer(32, 601)

        for (y in 0 until size) {
            val v = samples[y]
            for (x in 0 until size) {
                val u = samples[x]

                // Low-frequency domain warping bends the cloud masses before the finer octaves
                // are added. This avoids axis-aligned/value-noise cells looking like a grid.
                val warpX =
                    0.13 * warpX3.sample(u, v) +
                    0.05 * warpX7.sample(u, v)
                val warpY =
                    0.13 * warpY3.sample(u + 0.37, v + 0.11) +
                    0.05 * warpY7.sample(u + 0.19, v + 0.53)
                val warpedU = u + warpX
                val warpedV = v + warpY

                // Broad mist first, then progressively finer wisps. The texture stays static:
                // there is no animation/timer and therefore no ongoing fog-specific battery cost.
                val cloud =
                    0.46 * cloud2.sample(warpedU, warpedV) +
                    0.28 * cloud4.sample(warpedU, warpedV) +
                    0.15 * cloud8.sample(warpedU, warpedV) +
                    0.08 * cloud16.sample(warpedU, warpedV) +
                    0.03 * cloud32.sample(u, v)

                // Smooth the tonal response so the fog has soft cloud bodies rather than harsh
                // contour bands. Alpha remains controlled by FogBitmapRenderer.MAX_FOG_ALPHA.
                val normalized = (cloud + 0.50).coerceIn(0.0, 1.0)
                val mist = normalized * normalized * (3.0 - 2.0 * normalized)
                val shade = (mist * 72.0).roundToInt()
                pixels[y * size + x] = palette[shade]
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Smooth, deterministic value noise backed by a tiny precomputed periodic lattice. */
    private class NoiseLayer(private val period: Int, seed: Int) {
        private val values = DoubleArray(period * period) { index ->
            lattice(index % period, index / period, seed)
        }

        fun sample(u: Double, v: Double): Double {
            val px = u * period
            val py = v * period
            val x0 = floor(px).toInt()
            val y0 = floor(py).toInt()
            val tx = px - x0
            val ty = py - y0
            val sx = smooth(tx)
            val sy = smooth(ty)

            val x1 = wrap(x0 + 1, period)
            val y1 = wrap(y0 + 1, period)
            val wx0 = wrap(x0, period)
            val wy0 = wrap(y0, period)
            val n00 = values[wy0 * period + wx0]
            val n10 = values[wy0 * period + x1]
            val n01 = values[y1 * period + wx0]
            val n11 = values[y1 * period + x1]
            val top = n00 + (n10 - n00) * sx
            val bottom = n01 + (n11 - n01) * sx
            return top + (bottom - top) * sy
        }
    }

    private fun smooth(value: Double): Double = value * value * (3.0 - 2.0 * value)

    private fun wrap(value: Int, period: Int): Int {
        val remainder = value % period
        return if (remainder < 0) remainder + period else remainder
    }

    /** Stable pseudo-random lattice value in [-1, 1]. */
    private fun lattice(x: Int, y: Int, seed: Int): Double {
        var value = x.toLong() * 374_761_393L +
            y.toLong() * 668_265_263L +
            seed.toLong() * 69_069L
        value = (value xor (value ushr 13)) * 1_274_126_177L
        value = value xor (value ushr 16)
        return (value and 0xffffL).toDouble() / 32_767.5 - 1.0
    }
}
