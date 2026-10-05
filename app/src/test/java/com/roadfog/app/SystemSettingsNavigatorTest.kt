package com.roadfog.app

import android.content.ActivityNotFoundException
import android.provider.Settings
import com.roadfog.app.util.SystemSettingsNavigator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
class SystemSettingsNavigatorTest {
    private val navigator = SystemSettingsNavigator
    @Test fun notificationsTargetThisAppDirectly() {
        val intent = navigator.intents(SystemSettingsNavigator.Destination.NOTIFICATIONS, "com.roadfog.app").first()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("com.roadfog.app", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
    @Test fun inaccessibleBatteryScreenFallsBackToThisAppsPage() {
        val candidates = navigator.intents(SystemSettingsNavigator.Destination.BATTERY, "com.roadfog.app")
        val attempted = mutableListOf<String?>()
        val result = navigator.launchFirst(candidates) {
            attempted += it.action
            if (it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                throw SecurityException("Battery-optimization screen is unavailable")
            }
        }
        assertEquals(2, attempted.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, result?.action)
        assertEquals("package:com.roadfog.app", result?.data.toString())
    }
    @Test fun missingScreensDoNotCrashAndEventuallyReturnNull() {
        assertNull(navigator.launchFirst(navigator.intents(SystemSettingsNavigator.Destination.BATTERY, "com.roadfog.app")) {
            throw ActivityNotFoundException()
        })
    }
    @Test fun locationAndBatteryUsePublicAndroidSettingsWithSafeFallbacks() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, navigator.intents(SystemSettingsNavigator.Destination.LOCATION_PERMISSION, "com.roadfog.app").first().action)
        val battery = navigator.intents(SystemSettingsNavigator.Destination.BATTERY, "com.roadfog.app")
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.first().action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery[1].action)
        assertEquals("package:com.roadfog.app", battery[1].data.toString())
    }
}
