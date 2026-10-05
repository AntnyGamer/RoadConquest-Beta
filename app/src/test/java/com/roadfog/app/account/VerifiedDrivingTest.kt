package com.roadfog.app.account

import android.location.Location
import com.roadfog.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class VerifiedDrivingTest {
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        AccountClient.endpointOverrideForTests = "https://example.com"
        AccountStore.clear(context)
        Prefs.setDriveVerificationEnabled(context, true)
    }

    @After fun cleanup() {
        val context = RuntimeEnvironment.getApplication()
        AccountClient.endpointOverrideForTests = ""
        AccountStore.clear(context)
    }

    @Test fun busyVerificationBuffersTheNewestLiveFixesWithinABound() {
        val context = RuntimeEnvironment.getApplication()
        val verified = VerifiedDriving(context)
        val busy = VerifiedDriving::class.java.getDeclaredField("busy").apply { isAccessible = true }
            .get(verified) as AtomicBoolean
        busy.set(true)

        repeat(40) { index ->
            verified.offer(Location("gps").apply {
                latitude = index.toDouble()
                longitude = -74.0
                accuracy = 5f
                speed = 10f
                time = 1_000_000L + index * 3_000L
                elapsedRealtimeNanos = (1_000L + index * 3_000L) * 1_000_000L
            })
        }

        @Suppress("UNCHECKED_CAST")
        val queue = VerifiedDriving::class.java.getDeclaredField("pendingLocations").apply { isAccessible = true }
            .get(verified) as ArrayDeque<Location>
        assertEquals(32, queue.size)
        assertEquals(8.0, queue.first.latitude, 0.0)
        assertEquals(39.0, queue.last.latitude, 0.0)

        busy.set(false)
        Prefs.setDriveVerificationEnabled(context, false)
        AccountClient.endpointOverrideForTests = ""
        verified.close()
    }
}
