package com.roadconquest.app

import android.content.ActivityNotFoundException
import android.provider.Settings
import com.roadconquest.app.util.SystemSettingsNavigator
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
        val intent = navigator.intents(SystemSettingsNavigator.Destination.NOTIFICATIONS, "com.roadconquest.app").first()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals("com.roadconquest.app", intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
    @Test fun inaccessibleBatteryScreenFallsBackToThisAppsPage() {
        val candidates = navigator.intents(SystemSettingsNavigator.Destination.BATTERY, "com.roadconquest.app")
        val attempted = mutableListOf<String?>()
        val result = navigator.launchFirst(candidates) {
            attempted += it.action
            if (it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                throw SecurityException("Battery-optimization screen is unavailable")
            }
        }
        assertEquals(2, attempted.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, result?.action)
        assertEquals("package:com.roadconquest.app", result?.data.toString())
    }
    @Test fun missingScreensDoNotCrashAndEventuallyReturnNull() {
        assertNull(navigator.launchFirst(navigator.intents(SystemSettingsNavigator.Destination.BATTERY, "com.roadconquest.app")) {
            throw ActivityNotFoundException()
        })
    }
    @Test fun locationAndBatteryUsePublicAndroidSettingsWithSafeFallbacks() {
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, navigator.intents(SystemSettingsNavigator.Destination.LOCATION_PERMISSION, "com.roadconquest.app").first().action)
        val battery = navigator.intents(SystemSettingsNavigator.Destination.BATTERY, "com.roadconquest.app")
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, battery.first().action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery[1].action)
        assertEquals("package:com.roadconquest.app", battery[1].data.toString())
    }
}
