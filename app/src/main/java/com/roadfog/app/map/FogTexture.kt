package com.roadfog.app.map

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
            val u = x.toDouble() / size * 2.0 * PI
            val v = y.toDouble() / size * 2.0 * PI
            val cloud = (0.46 * sin(u + 0.8 * cos(v)) +
                0.29 * cos(2.0 * v + sin(u)) +
                0.16 * sin(3.0 * u - 2.0 * v + 0.5) +
                0.09 * cos(7.0 * u + 5.0 * v)).coerceIn(-1.0, 1.0)
            val shade = ((cloud + 1.0) * 42.0).roundToInt()
            pixels[y * size + x] = Color.rgb(18 + shade, 27 + shade, 39 + shade)
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
