package com.roadfog.app.map

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class LiveLocationTest {
    @Test fun cachedFixExpiresAtItsOriginalAgeAndCannotBeRefreshedByReadingIt() {
        val live = LiveLocation()
        assertTrue(live.update(40.0, -74.0, 90.0, 1_000L, 55_000L))
        assertEquals(6_000L, live.current(1_000L)!!.expiresAt)
        assertNotNull(live.current(5_999L))
        assertNull(live.current(6_000L))
        assertNull(live.current(10_000L))
    }

    @Test fun freshFixExtendsExpiryButInvalidOrStaleUpdatesDoNot() {
        val live = LiveLocation()
        assertTrue(live.update(40.0, -74.0, 0.0, 0L, 0L))
        assertFalse(live.update(Double.NaN, -74.0, 0.0, 10_000L, 0L))
        assertFalse(live.update(40.0, -74.0, 0.0, 10_000L, 60_000L))
        assertFalse(live.update(40.0, -74.0, 0.0, 10_000L, -1L))
        assertEquals(60_000L, live.current(10_000L)!!.expiresAt)
        assertTrue(live.update(40.001, -74.0, 0.0, 10_000L, 0L))
        assertNotNull(live.current(69_999L))
        assertNull(live.current(70_000L))
    }

    @Test fun disablingLocationImmediatelyRemovesTheFix() {
        val live = LiveLocation()
        live.update(40.0, -74.0, 0.0, 0L, 0L)
        live.clear()
        assertNull(live.current(1L))
    }

    @Test fun stationaryFreshFixesKeepTheCarAliveAndInvalidHeadingCannotHideIt() {
        val live = LiveLocation()
        for (time in 0L..180_000L step 5_000L) {
            assertTrue(live.update(40.0, -74.0, Double.NaN, time, 0L))
            assertNotNull(live.current(time + 4_999L))
            assertEquals(0.0, live.current(time)!!.bearing, 0.0)
        }
        live.update(40.0, -74.0, -90.0, 185_000L, 0L)
        assertEquals(270.0, live.current(185_000L)!!.bearing, 0.0)
    }
}
