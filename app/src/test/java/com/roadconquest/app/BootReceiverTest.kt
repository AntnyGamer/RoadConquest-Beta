package com.roadconquest.app

import android.Manifest
import android.content.Intent
import android.location.LocationManager
import com.roadconquest.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 35, 37], manifest = Config.NONE)
class BootReceiverTest {
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("roadfog_preferences", 0).edit().clear().commit()
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    @Test fun bootAndPackageUpdateStartOnlyAfterTheUserHasStartedTracking() {
        val app = RuntimeEnvironment.getApplication()
        val receiver = BootReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(Shadows.shadowOf(app).nextStartedService)
        Prefs.markEverStarted(app)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            receiver.onReceive(app, Intent(action))
            assertEquals(TrackingService::class.java.name, Shadows.shadowOf(app).nextStartedService.component!!.className)
        }
    }

    @Test fun manualModeAndMissingPermissionsPreventBackgroundStartupButLocationOffDoesNot() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.markEverStarted(app)
        val receiver = BootReceiver()
        fun bootBlocked() {
            receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
            assertNull(Shadows.shadowOf(app).nextStartedService)
        }
        Prefs.setManualOnly(app, true); bootBlocked()
        Prefs.setManualOnly(app, false)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION); bootBlocked()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION); bootBlocked()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        // Automatic mode stays armed through a reboot even if Android Location is currently
        // off, so the service can take the first good fix after the user enables Location.
        Shadows.shadowOf(app.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(
            TrackingService::class.java.name,
            Shadows.shadowOf(app).nextStartedService.component!!.className
        )
    }

    @Test fun unrelatedBroadcastCannotStartTracking() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.markEverStarted(app)
        BootReceiver().onReceive(app, Intent("unrelated"))
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }
}
