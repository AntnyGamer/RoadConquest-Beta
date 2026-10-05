package com.roadfog.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadfog.app.account.AccountClient
import com.roadfog.app.account.AccountStore
import com.roadfog.app.achievements.Achievements
import com.roadfog.app.progression.ProgressionManager
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.util.Appearance
import com.roadfog.app.util.ForegroundSession
import com.roadfog.app.util.Prefs
import java.util.concurrent.Executors

class AccountActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var authGroup: View
    private lateinit var signedInGroup: View
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmPasswordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var signupButton: Button
    private lateinit var signedInText: TextView
    private lateinit var leaderboardSwitch: Switch
    private lateinit var logoutButton: Button
    private lateinit var changeUsernameButton: Button
    private lateinit var deleteAccountButton: Button
    private lateinit var deleteDeviceDataButton: Button
    private var rendering = false
    private var busy = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Appearance.isDark(this@AccountActivity)
            isAppearanceLightNavigationBars = !Appearance.isDark(this@AccountActivity)
        }
        setContentView(R.layout.activity_account)
        applySafeAreaInsets()

        statusText = findViewById(R.id.accountStatusText)
        authGroup = findViewById(R.id.accountAuthGroup)
        signedInGroup = findViewById(R.id.accountSignedInGroup)
        usernameInput = findViewById(R.id.accountUsername)
        passwordInput = findViewById(R.id.accountPassword)
        confirmPasswordInput = findViewById(R.id.accountConfirmPassword)
        setupPasswordEye(passwordInput, findViewById(R.id.accountPasswordVisibility))
        setupPasswordEye(confirmPasswordInput, findViewById(R.id.accountConfirmPasswordVisibility))
        loginButton = findViewById(R.id.accountLoginButton)
        signupButton = findViewById(R.id.accountSignupButton)
        signedInText = findViewById(R.id.accountSignedInText)
        leaderboardSwitch = findViewById(R.id.accountLeaderboardSwitch)
        logoutButton = findViewById(R.id.accountLogoutButton)
        changeUsernameButton = findViewById(R.id.accountChangeUsernameButton)
        deleteAccountButton = findViewById(R.id.accountDeleteButton)
        deleteDeviceDataButton = findViewById(R.id.accountDeleteDeviceDataButton)

        loginButton.setOnClickListener { authenticate(signup = false) }
        signupButton.setOnClickListener { authenticate(signup = true) }
        logoutButton.setOnClickListener { logout() }
        changeUsernameButton.setOnClickListener { showUsernameDialog() }
        deleteAccountButton.setOnClickListener { showDeleteAccountDialog() }
        deleteDeviceDataButton.setOnClickListener { showDeleteDeviceDataDialog() }
        leaderboardSwitch.setOnCheckedChangeListener { _, visible ->
            if (!rendering) updateLeaderboardPrivacy(visible)
        }

        val session = AccountStore.load(this)
        render(session)
        if (!AccountClient.isConfigured()) {
            statusText.text = "Account service is not configured in this build."
            setAuthEnabled(false)
        } else if (session != null) {
            refreshAccount(session)
        }
    }

    private fun applySafeAreaInsets() {
        val root = findViewById<View>(R.id.accountRoot)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun authenticate(signup: Boolean) {
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (!USERNAME.matches(username)) {
            statusText.text = "Username must be 3-24 letters, numbers, or underscores."
            return
        }
        val passwordLength = password.codePointCount(0, password.length)
        if (passwordLength !in 8..128) {
            statusText.text = "Password must be 8-128 characters."
            return
        }
        if (signup && password != confirmPasswordInput.text.toString()) {
            statusText.text = "Passwords do not match."
            return
        }

        setAuthEnabled(false)
        statusText.text = if (signup) "Creating account…" else "Signing in…"
        executor.execute {
            val result = runCatching {
                if (signup) AccountClient.signup(username, password)
                else AccountClient.login(username, password)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { account ->
                        val token = requireNotNull(account.token)
                        val session = AccountStore.Session(
                            account.username,
                            token,
                            account.leaderboardVisible
                        )
                        AccountStore.save(this, session)
                        if (signup) Prefs.setDriveVerificationEnabled(this, true)
                        passwordInput.text.clear()
                        confirmPasswordInput.text.clear()
                        statusText.text = "Signed in."
                        render(session)
                        if (intent.getBooleanExtra(EXTRA_ONBOARDING, false)) finish()
                    },
                    onFailure = {
                        statusText.text = it.message ?: "Account request failed."
                        setAuthEnabled(true)
                    }
                )
            }
        }
    }

    private fun refreshAccount(session: AccountStore.Session) {
        statusText.text = "Checking account…"
        executor.execute {
            val result = runCatching { AccountClient.me(session.token) }
            runOnUiThread {
                if (isDestroyed || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                result.fold(
                    onSuccess = { account ->
                        val updated = AccountStore.updateIfToken(this, session.token) { current ->
                            current.copy(
                                username = if (current.username == session.username) account.username else current.username,
                                leaderboardVisible = if (current.leaderboardVisible == session.leaderboardVisible)
                                    account.leaderboardVisible else current.leaderboardVisible
                            )
                        } ?: return@runOnUiThread
                        statusText.text = "Signed in."
                        render(updated)
                    },
                    onFailure = { error ->
                        if (error is AccountClient.ApiException && error.status == 401) {
                            AccountStore.clear(this)
                            statusText.text = "Your session expired. Sign in again."
                            render(null)
                        } else {
                            statusText.text = "Could not refresh account. Your saved sign-in is unchanged."
                        }
                    }
                )
            }
        }
    }

    private fun updateLeaderboardPrivacy(visible: Boolean) {
        val session = AccountStore.load(this) ?: return
        leaderboardSwitch.isEnabled = false
        executor.execute {
            val result = runCatching { AccountClient.setLeaderboardVisible(session.token, visible) }
            runOnUiThread {
                if (isDestroyed || AccountStore.load(this)?.token != session.token) return@runOnUiThread
                result.fold(
                    onSuccess = { saved ->
                        AccountStore.updateIfToken(this, session.token) { it.copy(leaderboardVisible = saved) }
                        rendering = true
                        leaderboardSwitch.isChecked = saved
                        rendering = false
                        leaderboardSwitch.isEnabled = !busy
                        statusText.text = "Leaderboard privacy saved."
                    },
                    onFailure = { error ->
                        rendering = true
                        leaderboardSwitch.isChecked = session.leaderboardVisible
                        rendering = false
                        leaderboardSwitch.isEnabled = !busy
                        statusText.text = error.message ?: "Could not save leaderboard privacy."
                    }
                )
            }
        }
    }

    private fun logout() {
        val session = AccountStore.load(this)
        AccountStore.clear(this)
        render(null)
        statusText.text = "Signed out."
        if (session != null && AccountClient.isConfigured()) {
            executor.execute { runCatching { AccountClient.logout(session.token) } }
        }
    }

    private fun dialogFields(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, 0)
    }

    private fun passwordField(): EditText = EditText(this).apply {
        hint = "Current password"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
        isSaveEnabled = false
        filters = arrayOf(android.text.InputFilter.LengthFilter(128))
    }

    private fun showUsernameDialog() {
        if (busy) return
        val session = AccountStore.load(this) ?: return
        val fields = dialogFields()
        val username = EditText(this).apply {
            hint = "New username"
            isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            setText(session.username)
        }
        val password = passwordField()
        val error = TextView(this)
        fields.addView(username); fields.addView(password); fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("Change username").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnDismissListener { password.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = username.text.toString().trim()
                val currentPassword = password.text.toString()
                if (!USERNAME.matches(name) || currentPassword.isEmpty()) {
                    error.text = "Enter 3-24 letters, numbers or underscores and your current password."
                    return@setOnClickListener
                }
                dialog.dismiss()
                setBusy(true)
                statusText.text = "Changing username…"
                executor.execute {
                    val result = runCatching { AccountClient.changeUsername(session.token, name, currentPassword) }
                    result.onSuccess { account ->
                        AccountStore.updateIfToken(applicationContext, session.token) {
                            it.copy(username = account.username, leaderboardVisible = account.leaderboardVisible)
                        }
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        render(AccountStore.load(this))
                        statusText.text = result.fold({ "Username changed." }, { it.message ?: "Could not change username." })
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteAccountDialog() {
        if (busy) return
        val session = AccountStore.load(this) ?: return
        val fields = dialogFields()
        fields.addView(TextView(this).apply {
            text = "Permanently delete your cloud account, all sessions and all leaderboard data. This cannot be undone. Exported files and data on other devices must be deleted separately."
        })
        val password = passwordField()
        val local = CheckBox(this).apply {
            text = "Also delete this phone's saved history and stop tracking"
            isChecked = true
        }
        val error = TextView(this)
        fields.addView(password); fields.addView(local); fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle("Delete account?").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Delete account", null).create()
        dialog.setOnDismissListener { password.text.clear() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val currentPassword = password.text.toString()
                if (currentPassword.isEmpty()) {
                    error.text = "Enter your current password to delete your account."
                    return@setOnClickListener
                }
                val removeLocal = local.isChecked
                dialog.dismiss()
                setBusy(true)
                statusText.text = "Deleting account…"
                executor.execute {
                    var accountDeleted = false
                    val result = runCatching {
                        AccountClient.deleteAccount(session.token, currentPassword)
                        accountDeleted = true
                        synchronized(AccountStore) {
                            if (AccountStore.load(applicationContext)?.token == session.token) {
                                AccountStore.clear(applicationContext)
                            }
                        }
                        if (removeLocal) {
                            stopForDataDeletion()
                            TrackingRepository(applicationContext).clearHistory()
                            Achievements.reset(applicationContext)
                            ProgressionManager.resetLocalProgression(applicationContext)
                            Prefs.setTrackingPaused(applicationContext, false)
                            notifyDataDeleted()
                        }
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        render(AccountStore.load(this))
                        statusText.text = result.fold(
                            { if (removeLocal) "Account and saved device data deleted. Tracking is off." else "Account deleted. Saved device history was kept." },
                            { if (accountDeleted) "Account deleted. Device data could not be cleared; use Delete data on this device to retry."
                              else it.message ?: "Could not delete account. Your saved data is unchanged." }
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteDeviceDataDialog() {
        if (busy) return
        AlertDialog.Builder(this).setTitle("Delete device data?")
            .setMessage("Delete all saved trips, mileage, roads, explored places, points, purchases and local progression on this phone and stop tracking. Your cloud account and leaderboard scores stay. Exported files must be deleted separately. This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete device data") { _, _ ->
                stopForDataDeletion()
                setBusy(true)
                statusText.text = "Deleting device data…"
                executor.execute {
                    val result = runCatching {
                        TrackingRepository(applicationContext).clearHistory()
                        Achievements.reset(applicationContext)
                        ProgressionManager.resetLocalProgression(applicationContext)
                        Prefs.setTrackingPaused(applicationContext, false)
                        notifyDataDeleted()
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        statusText.text = result.fold({ "Saved device data deleted. Tracking is off." },
                            { "Could not delete device data. Tracking is off; try again." })
                    }
                }
            }.show()
    }

    private fun stopForDataDeletion() {
        Prefs.setManualOnly(applicationContext, true)
        Prefs.setTrackingPaused(applicationContext, true)
        runOnUiThread { stopService(Intent(applicationContext, TrackingService::class.java)) }
    }

    private fun notifyDataDeleted() {
        for (action in listOf(
            TrackingService.ACTION_STATS_UPDATED,
            TrackingService.ACTION_ROADS_UPDATED,
            TrackingService.ACTION_EXPLORATION_UPDATED
        )) {
            applicationContext.sendBroadcast(Intent(action).setPackage(packageName))
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        val configured = AccountClient.isConfigured()
        logoutButton.isEnabled = !value
        changeUsernameButton.isEnabled = !value && configured
        deleteAccountButton.isEnabled = !value && configured
        deleteDeviceDataButton.isEnabled = !value
        leaderboardSwitch.isEnabled = !value && configured
        setAuthEnabled(!value && configured)
    }

    private fun render(session: AccountStore.Session?) {
        rendering = true
        authGroup.visibility = if (session == null) View.VISIBLE else View.GONE
        signedInGroup.visibility = if (session == null) View.GONE else View.VISIBLE
        if (session != null) {
            signedInText.text = "Signed in as " + session.username
            leaderboardSwitch.isChecked = session.leaderboardVisible
            leaderboardSwitch.isEnabled = AccountClient.isConfigured()
        }
        rendering = false
        setBusy(busy)
    }

    private fun setupPasswordEye(input: EditText, button: ImageButton) {
        button.setOnClickListener {
            val start = input.selectionStart
            val end = input.selectionEnd
            val showing = input.transformationMethod == null
            input.transformationMethod = if (showing) PasswordTransformationMethod.getInstance() else null
            button.setImageResource(if (showing) R.drawable.ic_visibility else R.drawable.ic_visibility_off)
            button.contentDescription = if (showing) "Show password" else "Hide password"
            if (start >= 0 && end >= 0) input.setSelection(start, end)
        }
    }

    private fun setAuthEnabled(enabled: Boolean) {
        usernameInput.isEnabled = enabled
        passwordInput.isEnabled = enabled
        confirmPasswordInput.isEnabled = enabled
        loginButton.isEnabled = enabled
        signupButton.isEnabled = enabled
    }

    override fun onStart() {
        super.onStart()
        ForegroundSession.app.onStart()
    }

    override fun onStop() {
        passwordInput.text.clear()
        confirmPasswordInput.text.clear()
        ForegroundSession.app.onStop(isChangingConfigurations)
        super.onStop()
    }

    override fun onDestroy() {
        // Finish an authorized deletion even if the activity closes during the request.
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ONBOARDING = "com.roadfog.app.ACCOUNT_ONBOARDING"
        private val USERNAME = Regex("[A-Za-z0-9_]{3,24}")
    }
}
