package com.roadfog.app

import android.content.Context
import android.os.SystemClock
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roadfog.app.account.AccountStore
import com.roadfog.app.account.AccountClient
import com.roadfog.app.account.AccountOnboarding
import com.roadfog.app.util.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountStoreDeviceTest {
    @Test fun verificationCanBeTurnedOffWhenServiceStatusCannotBeChecked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AccountStore.save(context, AccountStore.Session("OfflineDriver", "test-only-expired-token", true))
        Prefs.setDriveVerificationEnabled(context, true)
        AccountClient.endpointOverrideForTests = "https://127.0.0.1:1"
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 10_000L
                var checkFailed = false
                while (!checkFailed && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        checkFailed = activity.findViewById<TextView>(R.id.verificationStatusText).text.toString()
                            .contains("Could not check verified scoring.")
                    }
                    if (!checkFailed) SystemClock.sleep(50)
                }
                assertTrue("Status-check failure is displayed without claiming scoring is unavailable", checkFailed)
                scenario.onActivity { activity ->
                    Prefs.setAccountPromptShown(context, false)
                    assertNull("Saved accounts skip the first-launch invitation", AccountOnboarding.showIfNeeded(activity) { fail("Already signed in") })
                    assertTrue(Prefs.isAccountPromptShown(context))
                    val control = activity.findViewById<Switch>(R.id.verifyDrivesSwitch)
                    assertTrue("Availability must not block opt-out", control.isEnabled)
                    assertTrue(control.isChecked)
                    control.performClick()
                    assertFalse(Prefs.isDriveVerificationEnabled(context))
                }
            }
        } finally {
            AccountClient.endpointOverrideForTests = null
            Prefs.setDriveVerificationEnabled(context, false)
            AccountStore.clear(context)
        }
    }

    @Test fun sessionTokenIsEncryptedByAndroidKeystoreAndRoundTrips() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AccountStore.clear(context)
        val session = AccountStore.Session(
            username = "DriverOne",
            token = "test-session-token-that-must-never-be-stored-in-plaintext",
            leaderboardVisible = false
        )

        try {
            AccountStore.save(context, session)
            val prefs = context.getSharedPreferences("roadconquest_account", Context.MODE_PRIVATE)
            val firstCiphertext = requireNotNull(prefs.getString("token", null))
            assertFalse(firstCiphertext.contains(session.token))
            assertEquals(session, AccountStore.load(context))

            assertNull(AccountStore.updateIfToken(context, "an-old-session-token") { it.copy(username = "WrongDriver") })
            assertEquals(session, AccountStore.load(context))
            val renamed = AccountStore.updateIfToken(context, session.token) { it.copy(username = "NewDriver") }
            assertEquals("NewDriver", renamed?.username)
            assertEquals(session.token, AccountStore.load(context)?.token)

            AccountStore.save(context, session)
            val secondCiphertext = requireNotNull(prefs.getString("token", null))
            assertNotEquals("AES-GCM must use a fresh IV for every save", firstCiphertext, secondCiphertext)
            assertEquals(session, AccountStore.load(context))
        } finally {
            AccountStore.clear(context)
        }
    }
}
