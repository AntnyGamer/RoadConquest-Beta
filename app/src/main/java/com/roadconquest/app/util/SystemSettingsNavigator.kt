package com.roadconquest.app.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object SystemSettingsNavigator {
    enum class Destination { LOCATION_PERMISSION, DEVICE_LOCATION, NOTIFICATIONS, BATTERY }

    fun intents(destination: Destination, packageName: String): List<Intent> {
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        val specific = when (destination) {
            // Android protects direct per-app permission screens with a system permission.
            Destination.LOCATION_PERMISSION -> emptyList()
            Destination.DEVICE_LOCATION -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            Destination.NOTIFICATIONS -> listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            Destination.BATTERY -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        return specific + app + Intent(Settings.ACTION_SETTINGS)
    }

    // Attempt launching rather than resolveActivity: Android package visibility can hide
    // an activity that is still legal to open. Guard OEM missing/private destinations.
    fun launchFirst(intents: List<Intent>, launch: (Intent) -> Unit): Intent? {
        for (intent in intents) {
            try {
                launch(intent)
                return intent
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return null
    }

    fun open(activity: Activity, destination: Destination) {
        val launched = launchFirst(intents(destination, activity.packageName), activity::startActivity)
        val message = when {
            launched == null -> "Open your device settings manually."
            launched.action == Settings.ACTION_SETTINGS -> "Find RoadConquest under Apps, then choose the required setting."
            destination == Destination.LOCATION_PERMISSION -> "Choose Permissions → Location, then enable Precise and Allow all the time."
            destination == Destination.BATTERY && launched.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> "Find RoadConquest and allow background battery use if your device offers that option."
            launched.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS && destination == Destination.BATTERY -> "Choose Battery, then allow background use or Unrestricted if your device offers it."
            else -> null
        }
        message?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
    }
}
