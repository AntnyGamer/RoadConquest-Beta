package com.roadconquest.app.map

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FogTextureTest {
    @Test fun patternContainsCloudVariationAndTilesWithoutSharpSeams() {
        val bitmap = FogTexture.create()
        val shades = (0 until 256 step 8).flatMap { y -> (0 until 256 step 8).map { x -> Color.red(bitmap.getPixel(x, y)) } }
        assertTrue("Cloud shading must remain visibly textured", shades.max() - shades.min() > 50)
        for (i in 0 until 256) {
            assertTrue(abs(Color.red(bitmap.getPixel(0, i)) - Color.red(bitmap.getPixel(255, i))) <= 3)
            assertTrue(abs(Color.red(bitmap.getPixel(i, 0)) - Color.red(bitmap.getPixel(i, 255))) <= 3)
            assertEquals(255, Color.alpha(bitmap.getPixel(i, i)))
        }
    }
}
