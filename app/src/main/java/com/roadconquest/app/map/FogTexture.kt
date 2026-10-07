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
        // The final row/column intentionally sample the same mathematical point as the first,
        // so REPEAT filtering cannot expose a tile seam.
        val denominator = (size - 1).coerceAtLeast(1).toDouble()

        for (y in 0 until size) {
            val v = y / denominator
            for (x in 0 until size) {
                val u = x / denominator

                // Low-frequency domain warping bends the cloud masses before the finer octaves
                // are added. This avoids axis-aligned/value-noise cells looking like a grid.
                val warpX =
                    0.13 * periodicNoise(u, v, 3, 17) +
                    0.05 * periodicNoise(u, v, 7, 73)
                val warpY =
                    0.13 * periodicNoise(u + 0.37, v + 0.11, 3, 101) +
                    0.05 * periodicNoise(u + 0.19, v + 0.53, 7, 151)
                val warpedU = u + warpX
                val warpedV = v + warpY

                // Broad mist first, then progressively finer wisps. The texture stays static:
                // there is no animation/timer and therefore no ongoing fog-specific battery cost.
                val cloud =
                    0.46 * periodicNoise(warpedU, warpedV, 2, 211) +
                    0.28 * periodicNoise(warpedU, warpedV, 4, 307) +
                    0.15 * periodicNoise(warpedU, warpedV, 8, 401) +
                    0.08 * periodicNoise(warpedU, warpedV, 16, 503) +
                    0.03 * periodicNoise(u, v, 32, 601)

                // Smooth the tonal response so the fog has soft cloud bodies rather than harsh
                // contour bands. Alpha remains controlled by FogBitmapRenderer.MAX_FOG_ALPHA.
                val normalized = (cloud + 0.50).coerceIn(0.0, 1.0)
                val mist = normalized * normalized * (3.0 - 2.0 * normalized)
                val shade = (mist * 72.0).roundToInt()
                pixels[y * size + x] = Color.rgb(14 + shade, 22 + shade, 34 + shade)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Smooth, deterministic value noise whose integer lattice wraps at [period]. */
    private fun periodicNoise(u: Double, v: Double, period: Int, seed: Int): Double {
        val px = u * period
        val py = v * period
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val tx = px - x0
        val ty = py - y0
        val sx = smooth(tx)
        val sy = smooth(ty)

        val n00 = lattice(wrap(x0, period), wrap(y0, period), seed)
        val n10 = lattice(wrap(x0 + 1, period), wrap(y0, period), seed)
        val n01 = lattice(wrap(x0, period), wrap(y0 + 1, period), seed)
        val n11 = lattice(wrap(x0 + 1, period), wrap(y0 + 1, period), seed)
        val top = n00 + (n10 - n00) * sx
        val bottom = n01 + (n11 - n01) * sx
        return top + (bottom - top) * sy
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
