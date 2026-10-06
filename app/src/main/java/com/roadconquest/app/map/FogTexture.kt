package com.roadconquest.app.map

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A seamless, static cloud pattern: generated once, with no animation/battery loop. */
object FogTexture {
    const val SIZE = 256

    fun create(size: Int = SIZE): Bitmap {
        require(size > 0 && size and (size - 1) == 0)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            // Duplicate the mathematical edge so BitmapShader repetition has no seam.
            val denominator = (size - 1).coerceAtLeast(1).toDouble()
            val u = x.toDouble() / denominator * 2.0 * PI
            val v = y.toDouble() / denominator * 2.0 * PI
            // Layer broad cloud masses with progressively finer static detail. Integer
            // frequencies keep every layer periodic, so the texture remains seamless while
            // looking textured at both driving and regional zooms.
            val cloud = (0.39 * sin(u + 0.8 * cos(v)) +
                0.25 * cos(2.0 * v + sin(u)) +
                0.15 * sin(3.0 * u - 2.0 * v + 0.5) +
                0.09 * cos(7.0 * u + 5.0 * v) +
                0.07 * sin(13.0 * u - 11.0 * v + 0.35 * sin(3.0 * v)) +
                0.05 * cos(23.0 * u + 17.0 * v + 0.25 * cos(5.0 * u))).coerceIn(-1.0, 1.0)
            val shade = ((cloud + 1.0) * 50.0).roundToInt()
            pixels[y * size + x] = Color.rgb(12 + shade, 20 + shade, 32 + shade)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
