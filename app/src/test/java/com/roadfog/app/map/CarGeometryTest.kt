package com.roadfog.app.map

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class CarGeometryTest {
    @Test fun invalidCoordinatesCannotReachTheMapOrCamera() {
        val live = LiveLocation()
        assertFalse(live.update(Double.NaN, -74.0, 0.0, 0L, 0L))
        assertFalse(live.update(40.0, Double.POSITIVE_INFINITY, 0.0, 0L, 0L))
        assertFalse(live.update(91.0, -74.0, 0.0, 0L, 0L))
        assertFalse(live.update(40.0, -181.0, 0.0, 0L, 0L))
        assertNull(live.current(0L))
    }

    @Test fun invalidHeadingUsesZeroWithoutHidingAValidFix() {
        val live = LiveLocation()
        assertTrue(live.update(40.0, -74.0, Double.NaN, 0L, 0L))
        val fix = requireNotNull(live.current(0L))
        assertEquals(40.0, fix.latitude, 0.0)
        assertEquals(-74.0, fix.longitude, 0.0)
        assertEquals(0.0, fix.bearing, 0.0)
    }

    @Test fun headingIsNormalizedWithoutChangingCoordinates() {
        val live = LiveLocation()
        assertTrue(live.update(40.0, -74.0, -10.0, 0L, 0L))
        val fix = requireNotNull(live.current(0L))
        assertEquals(350.0, fix.bearing, 0.0)
        assertEquals(-74.0, fix.longitude, 0.0)
        assertEquals(40.0, fix.latitude, 0.0)
    }
}
