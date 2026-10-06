package com.roadfog.app

import android.view.View
import android.app.AlertDialog
import android.os.Looper
import android.location.Location
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.roadfog.app.account.AccountClient
import com.roadfog.app.account.AccountStore
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.data.AppDatabase
import com.roadfog.app.data.LocalDataReset
import com.roadfog.app.util.Prefs
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AccountActivityTest {
    @Before fun clearAccountState() {
        AccountClient.endpointOverrideForTests = ""
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("roadconquest_account", 0)
            .edit().clear().commit()
        Prefs.setDriveVerificationEnabled(RuntimeEnvironment.getApplication(), false)
    }

    @Test fun disablingAndReenablingVerificationInvalidatesPreviouslyCollectedEvidence() {
        val context = RuntimeEnvironment.getApplication()
        val initial = Prefs.driveVerificationConsentVersion(context)
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.isDriveVerificationEnabled(context))
        val collecting = Prefs.driveVerificationConsentVersion(context)
        Prefs.setDriveVerificationEnabled(context, false)
        assertFalse(Prefs.isDriveVerificationEnabled(context))
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.driveVerificationConsentVersion(context) > collecting)
        assertTrue(collecting > initial)
    }

    @Test fun clearingAnySessionAlsoClearsVerifiedDriveConsent() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.setDriveVerificationEnabled(context, true)
        assertTrue(Prefs.isDriveVerificationEnabled(context))
        AccountStore.clear(context)
        assertFalse(Prefs.isDriveVerificationEnabled(context))
    }

    @Test fun sessionStringNeverContainsBearerToken() {
        val token = "secret-bearer-token-that-must-not-be-logged"
        val text = AccountStore.Session("DriverOne", token, false).toString()
        assertFalse(text.contains(token))
        assertTrue(text.contains("<redacted>"))
    }

    @Test fun settingsLinksToAccountAndAlwaysExposesLeaderboardPage() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val privacy = activity.findViewById<Switch>(R.id.leaderboardPrivacySwitch)
            assertFalse(privacy.isChecked)
            assertFalse(privacy.isEnabled)
            assertFalse(activity.findViewById<Switch>(R.id.verifyDrivesSwitch).isChecked)
            assertFalse(activity.findViewById<Switch>(R.id.verifyDrivesSwitch).isEnabled)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.leaderboardsButton).visibility)

            activity.findViewById<Button>(R.id.accountButton).performClick()
            val launched = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(AccountActivity::class.java.name, launched.component?.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun passwordEyesToggleVisibilityWithoutClearingText() {
        AccountClient.endpointOverrideForTests = "https://example.com"
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val password = activity.findViewById<EditText>(R.id.accountPassword)
            password.setText("abcdefgh")
            password.setSelection(3)
            assertNotNull(password.transformationMethod)
            activity.findViewById<View>(R.id.accountPasswordVisibility).performClick()
            assertNull(password.transformationMethod)
            assertEquals("abcdefgh", password.text.toString())
            assertEquals(3, password.selectionStart)
            activity.findViewById<View>(R.id.accountPasswordVisibility).performClick()
            assertNotNull(password.transformationMethod)
            assertEquals("abcdefgh", password.text.toString())
            assertEquals(3, password.selectionStart)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun liveAccountEndpointIsConfiguredByDefault() {
        AccountClient.endpointOverrideForTests = null
        try {
            assertTrue(AccountClient.isConfigured())
        } finally {
            AccountClient.endpointOverrideForTests = ""
        }
    }

    @Test fun unconfiguredEndpointDoesNotAcceptCredentialsOrExposeLeaderboardControls() {
        val controller = Robolectric.buildActivity(AccountActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            assertTrue(
                activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            )
            assertFalse(activity.findViewById<Button>(R.id.accountSignupButton).isEnabled)
            assertFalse(activity.findViewById<Button>(R.id.accountLoginButton).isEnabled)
            assertFalse(activity.findViewById<EditText>(R.id.accountPassword).isEnabled)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.accountSignedInGroup).visibility)
            assertFalse(activity.findViewById<Switch>(R.id.accountLeaderboardSwitch).isChecked)
            assertTrue(
                activity.findViewById<TextView>(R.id.accountStatusText).text
                    .toString().contains("not configured", ignoreCase = true)
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun settingsDeviceDataDeletionWorksOfflineRequiresConfirmationAndStopsAutomaticTracking() {
        // Robolectric resets legacy SQLite pointers between tests, but the app's
        // process-wide helper survives. Start this database test with a fresh helper.
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, null)
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        })
        Prefs.setManualOnly(context, false)
        Prefs.setDriveVerificationEnabled(context, true)
        File(context.cacheDir, "roadconquest-export-stale.db").writeText("stale snapshot")
        File(context.cacheDir, "roadconquest-export-stale.db-wal").writeText("stale sidecar")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        try {
            val activity = controller.get()
            val control = activity.findViewById<Button>(R.id.deleteDeviceDataButton)
            val manual = activity.findViewById<Switch>(R.id.manualOnlySwitch)
            assertTrue("Local deletion works without an account service", control.isEnabled)
            assertTrue(manual.isEnabled)
            val before = repository.getSummary().trackPointCount
            control.performClick()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(before, repository.getSummary().trackPointCount)
            assertFalse(Prefs.isManualOnly(context))

            control.performClick()
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            do {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (control.isEnabled) break
                Thread.sleep(10)
            } while (System.nanoTime() < deadline)
            assertTrue("Deletion finishes", control.isEnabled)
            assertTrue("Tracking mode can be changed again after deletion", manual.isEnabled)
            assertEquals(0L, repository.getSummary().trackPointCount)
            assertTrue(Prefs.isManualOnly(context))
            assertFalse(Prefs.isTrackingPaused(context))
            assertFalse(Prefs.isDeviceDataDeletionPending(context))
            assertFalse(Prefs.isDriveVerificationEnabled(context))
            assertTrue(context.cacheDir.listFiles().orEmpty().none {
                it.name.startsWith("roadconquest-export-")
            })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun confirmedDeviceDeletionFinishesAfterSettingsCloses() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, null)
        val context = RuntimeEnvironment.getApplication()
        val repository = TrackingRepository(context)
        repository.insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        })
        Prefs.setManualOnly(context, false)
        Prefs.setDriveVerificationEnabled(context, true)

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create().start().resume()
        val activity = controller.get()
        val executor = SettingsActivity::class.java.getDeclaredField("dataExecutor").let { field ->
            field.isAccessible = true
            field.get(activity) as ExecutorService
        }
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

        // Queue the same authorized deletion work the Settings confirmation uses, but do
        // it directly so this test measures executor teardown rather than Robolectric dialog timing.
        LocalDataReset.stopTracking(context)
        executor.execute { LocalDataReset.clearStoppedData(context) }

        // Close Settings while the authorized deletion is deliberately queued behind work.
        // Graceful executor shutdown must keep that queued deletion alive.
        controller.pause().stop().destroy()
        releaseBlocker.countDown()

        assertTrue(
            "Queued deletion finishes during graceful Settings teardown",
            executor.awaitTermination(5, TimeUnit.SECONDS)
        )
        assertEquals(0L, repository.getSummary().trackPointCount)
        assertTrue(Prefs.isManualOnly(context))
        assertFalse(Prefs.isTrackingPaused(context))
        assertFalse(Prefs.isDeviceDataDeletionPending(context))
        assertFalse(Prefs.isDriveVerificationEnabled(context))
    }


    @Test fun deviceDeletionMarkerIsDurableBeforeCleanupAndBlocksResume() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(null, null)
        val context = RuntimeEnvironment.getApplication()
        TrackingRepository(context).insertLocation(Location("gps").apply {
            latitude = 40.0; longitude = -74.0; accuracy = 5f; time = 1_000_000L
        })
        Prefs.setManualOnly(context, false)
        Prefs.setTrackingPaused(context, false)
        Prefs.setDriveVerificationEnabled(context, true)

        LocalDataReset.stopTracking(context)

        assertTrue(Prefs.isDeviceDataDeletionPending(context))
        assertTrue(Prefs.isManualOnly(context))
        assertTrue(Prefs.isTrackingPaused(context))
        assertFalse(Prefs.shouldResumePausedTracking(context))
        assertFalse(Prefs.isDriveVerificationEnabled(context))

        LocalDataReset.clearStoppedData(context)
        assertFalse(Prefs.isDeviceDataDeletionPending(context))
        assertFalse(Prefs.isTrackingPaused(context))
        assertTrue(Prefs.isManualOnly(context))
        assertEquals(0L, TrackingRepository(context).getSummary().trackPointCount)
    }

}
