package com.roadfog.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.data.RoadRecord
import com.roadfog.app.data.ProgressionRepository
import com.roadfog.app.account.AccountOnboarding
import com.roadfog.app.achievements.Achievements
import com.roadfog.app.map.MapRenderer
import com.roadfog.app.map.PlaceOverlayInfo
import com.roadfog.app.map.PlaceOverlayMode
import com.roadfog.app.progression.ProgressionManager
import com.roadfog.app.util.LocationProviders
import com.roadfog.app.util.Prefs
import com.roadfog.app.util.Appearance
import com.roadfog.app.util.UiTheme
import com.roadfog.app.util.SystemSettingsNavigator
import com.roadfog.app.util.StatsText
import com.roadfog.app.util.ForegroundSession
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private var appliedTheme = UiTheme.SYSTEM
    private var appliedGoldUi = false
    private var safeLeft = 0
    private var safeRight = 0
    private var mapWasCentered = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Appearance.wrap(newBase))
    }

    private lateinit var mapView: MapView
    private lateinit var enableButton: Button
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var pointsText: TextView
    private lateinit var repository: TrackingRepository
    private lateinit var locationManager: LocationManager
    private var renderer: MapRenderer? = null
    private var previewBearingDegrees = 0.0
    private var startAfterPermissionGrant = false
    private var resumed = false
    private var refreshRoadsAfterStop = false
    private var enteredForeground = false
    private var recreatingForAppearance = false
    private var accountPrompt: AlertDialog? = null
    private val summaryExecutor = Executors.newSingleThreadExecutor()
    private val discoveryExecutor = Executors.newSingleThreadExecutor()
    private val placeResolutionInFlight = AtomicBoolean(false)
    @Volatile private var summaryGeneration = 0
    @Volatile private var roadDetailsGeneration = 0
    private val statsHandler = Handler(Looper.getMainLooper())
    private var statsRefreshScheduled = false
    private val statsRefresh = Runnable {
        statsRefreshScheduled = false
        if (resumed && !isDestroyed) {
            refreshControls()
            renderer?.refreshTracking()
        }
    }

    private var lastPreviewLocation: Location? = null
    private var baselinePreviewCapturedForRegistration = false
    private val previewLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (Prefs.isTrackingPaused(this@MainActivity) || TrackingService.isRunning || !isFreshLocation(location) ||
                !location.hasAccuracy() || location.accuracy > MAX_PREVIEW_ACCURACY_M
            ) return
            if (!LocationProviders.isBetterFix(location, lastPreviewLocation)) return
            lastPreviewLocation = Location(location)
            if (!baselinePreviewCapturedForRegistration && !location.isMock &&
                location.accuracy in 0.01f..MAX_PREVIEW_ACCURACY_M
            ) {
                baselinePreviewCapturedForRegistration = true
                captureStartingPlace(Location(location))
            }
            if (location.hasBearing() && (!location.hasSpeed() || location.speed >= 0.5f)) {
                previewBearingDegrees = location.bearing.toDouble()
            }
            renderer?.updateCar(location.latitude, location.longitude, previewBearingDegrees, locationAgeMillis(location))
        }

        override fun onProviderEnabled(provider: String) {
            if (resumed) { refreshControls(); startPreviewLocation() }
        }
        override fun onProviderDisabled(provider: String) {
            if (!resumed) return
            refreshControls()
            if (locationManager.isLocationEnabled) startPreviewLocation()
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TrackingService.ACTION_LOCATION_UPDATE -> {
                    if (Prefs.isTrackingPaused(this@MainActivity)) return
                    stopPreviewLocation()
                    renderer?.updateCar(
                        intent.getDoubleExtra(TrackingService.EXTRA_LATITUDE, 0.0),
                        intent.getDoubleExtra(TrackingService.EXTRA_LONGITUDE, 0.0),
                        intent.getDoubleExtra(TrackingService.EXTRA_BEARING, 0.0),
                        intent.getLongExtra(TrackingService.EXTRA_LOCATION_AGE_MS, 0L)
                    )
                }
                TrackingService.ACTION_STATS_UPDATED -> {
                    if (resumed && !statsRefreshScheduled) {
                        statsRefreshScheduled = true
                        statsHandler.postDelayed(statsRefresh, 5_000L)
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION, LocationManager.MODE_CHANGED_ACTION -> {
                    refreshControls()
                    if (!TrackingService.isRunning && !Prefs.isManualOnly(this@MainActivity) &&
                        !Prefs.isTrackingPaused(this@MainActivity) && locationManager.isLocationEnabled
                    ) {
                        startTrackingIfPossible(requestIfMissing = false)
                    }
                    if (!TrackingService.isRunning) startPreviewLocation()
                }
                TrackingService.ACTION_ROADS_UPDATED -> {
                    renderer?.refreshRoads()
                    refreshControls()
                }
                TrackingService.ACTION_EXPLORATION_UPDATED -> {
                    renderer?.refreshExploration()
                    if (resumed) resolvePendingPlaces()
                }
                TrackingService.ACTION_TRACKING_STATE_CHANGED -> {
                    refreshControls()
                    if (TrackingService.isRunning || Prefs.isTrackingPaused(this@MainActivity)) stopPreviewLocation() else startPreviewLocation()
                }
                else -> return
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Appearance.themeRes(this))
        super.onCreate(savedInstanceState)
        appliedTheme = Prefs.uiTheme(this)
        appliedGoldUi = Prefs.isGoldUiEnabled(this)
        mapWasCentered = savedInstanceState?.getBoolean(STATE_MAP_CENTERED) ?: false
        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            // Transparent system bars sit over the dark fog, including in light UI mode.
            isAppearanceLightNavigationBars = false
        }
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        repository = TrackingRepository(this)
        startAfterPermissionGrant = savedInstanceState?.getBoolean(STATE_START_AFTER_PERMISSION) ?: false
        locationManager = getSystemService(LocationManager::class.java)
        mapView = findViewById(R.id.mapView)
        enableButton = findViewById(R.id.enableButton)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        pointsText = findViewById(R.id.pointsText)
        pointsText.setOnClickListener { startActivity(Intent(this, ShopActivity::class.java)) }
        mapView.onCreate(savedInstanceState)

        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val centerButton = findViewById<ImageButton>(R.id.centerCarButton)
        val overlayButton = findViewById<ImageButton>(R.id.overlayButton)
        val controlPanel = findViewById<View>(R.id.controlPanel)
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        centerButton.setOnClickListener {
            if (renderer?.centerOnCar() != true) {
                showFreshCachedLocation()
                if (renderer?.centerOnCar() != true) {
                    Toast.makeText(this, R.string.waiting_for_location, Toast.LENGTH_SHORT).show()
                }
            }
        }
        overlayButton.setOnClickListener { showOverlayPicker() }
        applySafeAreaInsets(settingsButton, centerButton, overlayButton, controlPanel, statsText, pointsText)
        enableButton.setOnClickListener { handleEnableButton() }

        mapView.getMapAsync { map ->
            if (isDestroyed) return@getMapAsync
            configureMapChrome(map, controlPanel)
            map.addOnMapClickListener { point ->
                val overlayInfo = renderer?.overlayInfoAt(map.projection.toScreenLocation(point))
                if (overlayInfo != null) {
                    showOverlayInfo(overlayInfo)
                    true
                } else {
                    if (map.cameraPosition.zoom >= 9.0) {
                        val radiusMeters = (
                            map.projection.getMetersPerPixelAtLatitude(point.latitude) * 16.0
                        ).coerceIn(12.0, 100.0)
                        loadRoadDetails(point.latitude, point.longitude, radiusMeters)
                    }
                    false
                }
            }
            renderer = MapRenderer(this, map, repository, mapView, mapWasCentered).also { renderer ->
                renderer.initialize { showFreshCachedLocation() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        enteredForeground = ForegroundSession.app.onStart()
        val filter = IntentFilter(TrackingService.ACTION_LOCATION_UPDATE).apply {
            addAction(TrackingService.ACTION_STATS_UPDATED)
            addAction(TrackingService.ACTION_ROADS_UPDATED)
            addAction(TrackingService.ACTION_EXPLORATION_UPDATED)
            addAction(TrackingService.ACTION_TRACKING_STATE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            locationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        // Closing the notification shade only resumes an already-started activity.
        val resumeStoppedTracking = enteredForeground && Prefs.isTrackingPaused(this)
        enteredForeground = false
        if (appliedTheme != Prefs.uiTheme(this) || appliedGoldUi != Prefs.isGoldUiEnabled(this)) {
            if (resumeStoppedTracking) {
                Prefs.setTrackingPaused(this, false)
                startTrackingIfPossible(requestIfMissing = false)
            }
            recreatingForAppearance = true
            recreate()
            return
        }
        resumed = true
        mapView.onResume()
        renderer?.setFogEnabled(Prefs.isFogEnabled(this))
        renderer?.setMapMode(Prefs.mapMode(this))
        renderer?.setPlaceOverlayMode(Prefs.placeOverlayMode(this))
        if (Prefs.placeOverlayMode(this) != PlaceOverlayMode.NONE) renderer?.refreshPlaceOverlays()
        renderer?.refreshCosmetics()
        ProgressionManager.recordBatteryFromSystem(this)
        // Road updates can finish while this activity is stopped and its receiver is
        // unregistered. A mere pause/resume keeps the receiver registered and can reuse cache.
        renderer?.resumeViewport(refreshRoadsAfterStop)
        refreshRoadsAfterStop = false
        refreshControls()
        if (accountPrompt?.isShowing == true) return
        accountPrompt = AccountOnboarding.showIfNeeded(this) {
            if (resumed && !isDestroyed) completeForegroundStart(resumeStoppedTracking)
        }
        if (accountPrompt != null) return
        completeForegroundStart(resumeStoppedTracking)
    }

    private fun completeForegroundStart(resumeStoppedTracking: Boolean) {
        if (resumeStoppedTracking) Prefs.setTrackingPaused(this, false)
        ensureBasicPermissions()
        if (!Prefs.isManualOnly(this) || resumeStoppedTracking) {
            startTrackingIfPossible(requestIfMissing = false)
            maybeExplainBackgroundLocation()
        }
        showFreshCachedLocation()
        if (!TrackingService.isRunning) startPreviewLocation()
        resolvePendingPlaces()
    }

    private fun captureStartingPlace(location: Location) {
        discoveryExecutor.execute {
            val recorded = runCatching {
                ProgressionRepository(applicationContext).recordBaselineCandidate(location)
            }.getOrDefault(false)
            if (!recorded) return@execute

            val added = runCatching {
                ProgressionManager.resolvePendingPlaces(applicationContext, 6)
            }.getOrDefault(0)
            if (!isDestroyed) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    renderer?.refreshExploration()
                    if (Prefs.placeOverlayMode(this) != PlaceOverlayMode.NONE) {
                        renderer?.refreshPlaceOverlays()
                    }
                    if (added > 0) refreshControls()
                }
            }
        }
    }

    private fun resolvePendingPlaces() {
        if (!placeResolutionInFlight.compareAndSet(false, true)) return
        discoveryExecutor.execute {
            val added = runCatching { ProgressionManager.resolvePendingPlaces(this, 6) }.getOrDefault(0)
            placeResolutionInFlight.set(false)
            if (!isDestroyed) {
                runOnUiThread {
                    if (!isDestroyed) {
                        // Baseline places are zero-point progress, but resolving them can still
                        // make a selected place overlay renderable.
                        if (Prefs.placeOverlayMode(this) != PlaceOverlayMode.NONE) {
                            renderer?.refreshPlaceOverlays()
                        }
                        if (added > 0) {
                            refreshControls()
                            Toast.makeText(
                                this,
                                if (added == 1) "New place discovered" else "$added new places discovered",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        resumed = false
        statsHandler.removeCallbacks(statsRefresh)
        statsRefreshScheduled = false
        stopPreviewLocation()
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        ForegroundSession.app.onStop(isChangingConfigurations || recreatingForAppearance)
        refreshRoadsAfterStop = true
        renderer?.cancelPlaceOverlayLoads()
        runCatching { unregisterReceiver(locationReceiver) }
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        accountPrompt?.dismiss()
        summaryGeneration++
        roadDetailsGeneration++
        statsHandler.removeCallbacksAndMessages(null)
        summaryExecutor.shutdownNow()
        discoveryExecutor.shutdownNow()
        renderer?.destroy()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_START_AFTER_PERMISSION, startAfterPermissionGrant)
        outState.putBoolean(STATE_MAP_CENTERED, renderer?.hasCentered ?: mapWasCentered)
        mapView.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BASIC_PERMISSIONS -> {
                if (hasLocationPermission()) {
                    if (!Prefs.isManualOnly(this) || startAfterPermissionGrant) {
                        startTrackingIfPossible(requestIfMissing = startAfterPermissionGrant)
                    }
                    startAfterPermissionGrant = false
                    showFreshCachedLocation()
                    if (!TrackingService.isRunning) startPreviewLocation()
                    maybeExplainBackgroundLocation()
                } else if (hasApproximateLocationPermission()) {
                    startAfterPermissionGrant = false
                    showPreciseLocationDialog()
                } else {
                    startAfterPermissionGrant = false
                }
            }
        }
    }

    private fun handleEnableButton() {
        if (Prefs.isTrackingPaused(this)) {
            Prefs.setTrackingPaused(this, false)
            startTrackingIfPossible(requestIfMissing = true)
            return
        }
        if (!Prefs.isManualOnly(this)) return
        if (TrackingService.isRunning) {
            stopService(Intent(this, TrackingService::class.java))
            refreshControls()
            enableButton.post { startPreviewLocation() }
        } else {
            startTrackingIfPossible(requestIfMissing = true)
        }
    }

    private fun startTrackingIfPossible(requestIfMissing: Boolean) {
        if (Prefs.isTrackingPaused(this)) return
        if (!hasLocationPermission()) {
            if (requestIfMissing) {
                startAfterPermissionGrant = true
                ensureBasicPermissions()
            }
            return
        }
        if (!locationManager.isLocationEnabled) {
            if (requestIfMissing) showLocationOffDialog()
            return
        }
        if (!TrackingService.isRunning) {
            stopPreviewLocation()
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
            }.onFailure {
                if (requestIfMissing) {
                    AlertDialog.Builder(this)
                        .setTitle("Could not start tracking")
                        .setMessage("Android blocked the tracking service. Open RoadConquest again and verify location permissions and battery settings.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
        refreshControls()
    }

    private fun ensureBasicPermissions() {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQUEST_BASIC_PERMISSIONS)
    }

    private fun maybeExplainBackgroundLocation() {
        if (!hasLocationPermission() || hasBackgroundLocation() || Prefs.isBackgroundPromptShown(this)) return
        Prefs.setBackgroundPromptShown(this, true)

        AlertDialog.Builder(this)
            .setTitle("Allow all-the-time location")
            .setMessage(
                "For RoadConquest to restart tracking automatically after a reboot or service restart, " +
                    "set Location permission to ‘Allow all the time’ in Android settings. A tracking service that you start while RoadConquest is open can continue after you leave the app."
            )
            .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showPreciseLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Precise location required")
            .setMessage("RoadConquest needs Precise location to distinguish nearby roads. Enable Precise location for RoadConquest in Android settings.")
            .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showLocationOffDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location is off")
            .setMessage("Turn on Android Location to record driven roads.")
            .setPositiveButton("Location settings") { _, _ ->
                SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.DEVICE_LOCATION)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        SystemSettingsNavigator.open(this, SystemSettingsNavigator.Destination.LOCATION_PERMISSION)
    }

    private fun refreshControls() {
        statsHandler.removeCallbacks(statsRefresh)
        statsRefreshScheduled = false
        if (Prefs.isTrackingPaused(this) || !hasLocationPermission() || !locationManager.isLocationEnabled) renderer?.clearCurrentLocation()
        val manualOnly = Prefs.isManualOnly(this)
        val active = TrackingService.isRunning
        statusText.text = when {
            Prefs.isTrackingPaused(this) -> getString(R.string.tracking_paused)
            !hasLocationPermission() && hasApproximateLocationPermission() -> "Precise location required"
            !hasLocationPermission() -> "Location permission required"
            !locationManager.isLocationEnabled -> if (active) "Tracking paused — Location is off" else "Location is off"
            active -> "Tracking your driving"
            else -> "Not tracking"
        }

        val generation = ++summaryGeneration
        summaryExecutor.execute {
            if (generation != summaryGeneration) return@execute
            val result = runCatching {
                val summary = repository.getSummary()
                val progression = ProgressionManager.sync(this, summary)
                val unlocked = Achievements.newlyUnlocked(
                    this,
                    summary,
                    ProgressionManager.metrics(progression)
                )
                Triple(summary, progression, unlocked)
            }
            runOnUiThread {
                if (isDestroyed || generation != summaryGeneration) return@runOnUiThread
                result.fold(
                    onSuccess = { (value, progression, unlocked) ->
                        statsText.text = StatsText.format(this, value)
                        pointsText.text = String.format(Locale.getDefault(), "⚔ %,d points", progression.balance)
                        if (unlocked.isNotEmpty()) {
                            val reward = unlocked.sumOf { it.rewardPoints }
                            val message = if (unlocked.size == 1) {
                                "Achievement unlocked: " + unlocked.first().title +
                                    String.format(Locale.getDefault(), " (+%,d points)", reward)
                            } else {
                                String.format(
                                    Locale.getDefault(),
                                    "%d achievements unlocked (+%,d points)",
                                    unlocked.size,
                                    reward
                                )
                            }
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onFailure = { error ->
                        Log.e("RoadConquest", "Could not load data summary", error)
                        statsText.text = getString(R.string.saved_data_unavailable)
                        pointsText.text = "Points unavailable"
                    }
                )
            }
        }

        if (Prefs.isTrackingPaused(this)) {
            enableButton.text = getString(R.string.resume_tracking)
            enableButton.isEnabled = true
        } else if (!manualOnly) {
            enableButton.text = "Enabled automatically"
            enableButton.isEnabled = false
        } else {
            enableButton.isEnabled = true
            enableButton.text = if (active) getString(R.string.disable) else getString(R.string.enable)
        }
    }

    private fun showOverlayPicker() {
        val choices = arrayOf("Countries — blue", "States and regions — purple", "Towns — green")
        val modes = arrayOf(PlaceOverlayMode.COUNTRY, PlaceOverlayMode.STATE, PlaceOverlayMode.TOWN)
        val current = renderer?.placeOverlayMode() ?: Prefs.placeOverlayMode(this)
        val checked = BooleanArray(modes.size) { modes[it] == current }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle("Map overlay — choose one")
            .setMultiChoiceItems(choices, checked) { _, which, isChecked ->
                val next = if (isChecked) modes[which] else PlaceOverlayMode.NONE
                for (index in modes.indices) {
                    val selected = isChecked && index == which
                    checked[index] = selected
                    if (dialog.listView.isItemChecked(index) != selected) {
                        dialog.listView.setItemChecked(index, selected)
                    }
                }
                renderer?.setPlaceOverlayMode(next) ?: Prefs.setPlaceOverlayMode(this, next)
                if (next != PlaceOverlayMode.NONE) {
                    // After a reset there may be no named-place row yet. Seed one from the best
                    // fresh cached fix so the current country/state/town can become an uncounted
                    // starter baseline and render immediately once reverse geocoding finishes.
                    showFreshCachedLocation()
                    lastPreviewLocation?.takeIf(::isFreshLocation)?.let {
                        ProgressionRepository(this).recordPlaceCandidate(it)
                    }
                    resolvePendingPlaces()
                }
            }
            .setPositiveButton("Done", null)
            .create()
        dialog.show()
    }

    private fun showOverlayInfo(info: PlaceOverlayInfo) {
        val type = when (info.kind) {
            com.roadfog.app.data.PlaceKind.COUNTRY -> "Country"
            com.roadfog.app.data.PlaceKind.STATE -> "State or region"
            com.roadfog.app.data.PlaceKind.TOWN -> "Town"
        }
        val population = info.population?.let {
            String.format(Locale.getDefault(), "%,d", it)
        } ?: "Not available"
        val squareMiles = info.areaSquareKilometers * 0.386102
        val area = if (squareMiles >= 1.0) {
            String.format(Locale.getDefault(), "%,.1f sq mi (%,.1f km²)", squareMiles, info.areaSquareKilometers)
        } else {
            String.format(Locale.getDefault(), "%,.2f sq mi (%,.2f km²)", squareMiles, info.areaSquareKilometers)
        }
        AlertDialog.Builder(this)
            .setTitle(info.name)
            .setMessage("$type\nPopulation: $population\nArea: $area")
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadRoadDetails(latitude: Double, longitude: Double, radiusMeters: Double) {
        val generation = ++roadDetailsGeneration
        summaryExecutor.execute {
            val result = runCatching {
                repository.findRoadNear(latitude, longitude, radiusMeters)?.let { road ->
                    road to repository.roadLengthMeters(road)
                }
            }
            runOnUiThread {
                if (isDestroyed || generation != roadDetailsGeneration) return@runOnUiThread
                result.getOrNull()?.let { (road, lengthMeters) ->
                    showRoadDetails(road, lengthMeters)
                }
            }
        }
    }

    private fun showRoadDetails(road: RoadRecord, lengthMeters: Double) {
        val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val length = if (lengthMeters < 160.9344) {
            String.format(Locale.getDefault(), "%.0f ft", lengthMeters * 3.28084)
        } else {
            String.format(Locale.getDefault(), "%.2f mi", lengthMeters / 1609.344)
        }
        val driven = if (road.timesDrivenExact) {
            if (road.timesDriven == 1) "1 time" else road.timesDriven.toString() + " times"
        } else {
            "At least " + road.timesDriven + if (road.timesDriven == 1) " time" else " times"
        }
        AlertDialog.Builder(this)
            .setTitle(road.name)
            .setMessage(
                "First unlocked: " + dateTime.format(Date(road.firstUnlockedAt)) + "\n" +
                    "Last driven: " + dateTime.format(Date(road.lastDrivenAt)) + "\n" +
                    "Times driven: " + driven + "\n" +
                    "Section length: " + length
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun configureMapChrome(map: MapLibreMap, controlPanel: View) {
        // Keep MapLibre's required logo/attribution visible above RoadConquest's bottom panel.
        // The default compass occupies the same top-right area as the Settings gear, so disable it.
        map.uiSettings.setCompassEnabled(false)
        map.uiSettings.setLogoGravity(Gravity.BOTTOM or Gravity.LEFT)
        map.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.RIGHT)
        val sideMargin = dpToPx(12f)

        fun updateMargins() {
            val fallbackBottom = dpToPx(170f)
            val occupiedBottom = if (mapView.height > 0 && controlPanel.top > 0) {
                (mapView.height - controlPanel.top + dpToPx(8f)).coerceAtLeast(dpToPx(24f))
            } else {
                fallbackBottom
            }
            val left = sideMargin + safeLeft
            val right = sideMargin + safeRight
            val ui = map.uiSettings
            if (ui.logoMarginLeft != left || ui.logoMarginTop != sideMargin ||
                ui.logoMarginRight != right || ui.logoMarginBottom != occupiedBottom) {
                ui.setLogoMargins(left, sideMargin, right, occupiedBottom)
            }
            if (ui.attributionMarginLeft != left || ui.attributionMarginTop != sideMargin ||
                ui.attributionMarginRight != right || ui.attributionMarginBottom != occupiedBottom) {
                ui.setAttributionMargins(left, sideMargin, right, occupiedBottom)
            }
        }

        controlPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMargins() }
        mapView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMargins() }
        controlPanel.post { updateMargins() }
    }

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun applySafeAreaInsets(
        settingsButton: ImageButton,
        centerButton: ImageButton,
        overlayButton: ImageButton,
        controlPanel: View,
        stats: View,
        points: View
    ) {
        val root = findViewById<View>(R.id.root)
        val settingsParams = settingsButton.layoutParams as FrameLayout.LayoutParams
        val settingsTop = settingsParams.topMargin
        val settingsEnd = settingsParams.marginEnd
        val centerParams = centerButton.layoutParams as FrameLayout.LayoutParams
        val centerTop = centerParams.topMargin
        val centerEnd = centerParams.marginEnd
        val overlayParams = overlayButton.layoutParams as FrameLayout.LayoutParams
        val overlayTop = overlayParams.topMargin
        val overlayEnd = overlayParams.marginEnd
        val statsParams = stats.layoutParams as FrameLayout.LayoutParams
        val statsTop = statsParams.topMargin
        val statsStart = statsParams.marginStart
        val pointsParams = points.layoutParams as FrameLayout.LayoutParams
        val pointsTop = pointsParams.topMargin
        val pointsStart = pointsParams.marginStart
        val panelParams = controlPanel.layoutParams as FrameLayout.LayoutParams
        val panelStart = panelParams.marginStart
        val panelEnd = panelParams.marginEnd
        val panelBottom = panelParams.bottomMargin

        fun sizeStats() {
            if (root.width <= 0) return
            val available = (root.width - safeLeft - safeRight - dpToPx(104f)).coerceAtLeast(dpToPx(100f))
            val target = minOf(dpToPx(210f), available)
            if ((stats as TextView).maxWidth != target) stats.maxWidth = target
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeStats() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeLeft = safe.left
            safeRight = safe.right
            (stats.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != statsTop + safe.top || params.marginStart != statsStart + safe.left) {
                    params.topMargin = statsTop + safe.top
                    params.marginStart = statsStart + safe.left
                    stats.layoutParams = params
                }
            }
            (points.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != pointsTop + safe.top || params.marginStart != pointsStart + safe.left) {
                    params.topMargin = pointsTop + safe.top
                    params.marginStart = pointsStart + safe.left
                    points.layoutParams = params
                }
            }
            // Setters can request layout even for identical values. Keep repeated inset/layout
            // callbacks idle, or the next layout will schedule another layout forever.
            sizeStats()
            (settingsButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != settingsTop + safe.top || params.marginEnd != settingsEnd + safe.right) {
                    params.topMargin = settingsTop + safe.top
                    params.marginEnd = settingsEnd + safe.right
                    settingsButton.layoutParams = params
                }
            }
            (centerButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != centerTop + safe.top || params.marginEnd != centerEnd + safe.right) {
                    params.topMargin = centerTop + safe.top
                    params.marginEnd = centerEnd + safe.right
                    centerButton.layoutParams = params
                }
            }
            (overlayButton.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.topMargin != overlayTop + safe.top || params.marginEnd != overlayEnd + safe.right) {
                    params.topMargin = overlayTop + safe.top
                    params.marginEnd = overlayEnd + safe.right
                    overlayButton.layoutParams = params
                }
            }
            (controlPanel.layoutParams as FrameLayout.LayoutParams).also { params ->
                if (params.marginStart != panelStart + safe.left || params.marginEnd != panelEnd + safe.right ||
                    params.bottomMargin != panelBottom + safe.bottom) {
                    params.marginStart = panelStart + safe.left
                    params.marginEnd = panelEnd + safe.right
                    params.bottomMargin = panelBottom + safe.bottom
                    controlPanel.layoutParams = params
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showFreshCachedLocation() {
        if (Prefs.isTrackingPaused(this)) return
        if (!hasLocationPermission() || !::locationManager.isInitialized || !locationManager.isLocationEnabled) return
        val providers = buildList {
            LocationProviders.preferred(locationManager)?.let(::add)
            addAll(LocationProviders.fallback(locationManager))
        }.distinct()
        val cached = providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null // Permission may have been revoked after the earlier check.
            } catch (_: IllegalArgumentException) {
                null // The provider may have disappeared.
            }
        }.filter { location ->
            isFreshLocation(location) && location.hasAccuracy() && location.accuracy <= MAX_PREVIEW_ACCURACY_M
        }
        val newest = cached.minByOrNull(::locationAgeMillis) ?: return
        val best = cached.filter { locationAgeMillis(it) <= locationAgeMillis(newest) + 2_000L }
            .minByOrNull { it.accuracy } ?: return
        lastPreviewLocation = Location(best)

        if (best.hasBearing() && (!best.hasSpeed() || best.speed >= 0.5f)) {
            previewBearingDegrees = best.bearing.toDouble()
        }
        renderer?.updateCar(best.latitude, best.longitude, previewBearingDegrees, locationAgeMillis(best))
    }

    private fun locationAgeMillis(location: Location): Long {
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            return ((SystemClock.elapsedRealtimeNanos() - fixElapsedNanos) / 1_000_000L)
                .coerceAtLeast(0L)
        }
        return (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
    }

    private fun startPreviewLocation() {
        if (Prefs.isTrackingPaused(this) || !resumed || !hasLocationPermission() || TrackingService.isRunning || !locationManager.isLocationEnabled) return
        stopPreviewLocation()
        baselinePreviewCapturedForRegistration = false
        LocationProviders.registerHighAccuracy(locationManager, ::registerPreviewProvider)
    }

    private fun registerPreviewProvider(provider: String): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val request = LocationRequest.Builder(PREVIEW_INTERVAL_MS)
                .setMinUpdateIntervalMillis(PREVIEW_MIN_UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(PREVIEW_MIN_DISTANCE_M)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            locationManager.requestLocationUpdates(provider, request, mainExecutor, previewLocationListener)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isFreshLocation(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 ||
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy < 0f
        ) return false
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - fixElapsedNanos
            return ageNanos in 0..MAX_PREVIEW_LOCATION_AGE_NANOS
        }
        val fixTime = location.time
        if (fixTime <= 0L) return false
        val ageMillis = System.currentTimeMillis() - fixTime
        return ageMillis in 0..MAX_PREVIEW_LOCATION_AGE_MS
    }

    private fun stopPreviewLocation() {
        if (::locationManager.isInitialized) runCatching { locationManager.removeUpdates(previewLocationListener) }
        lastPreviewLocation = null
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasApproximateLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQUEST_BASIC_PERMISSIONS = 100
        private const val STATE_START_AFTER_PERMISSION = "start_after_permission"
        private const val STATE_MAP_CENTERED = "map_centered"
        private const val PREVIEW_INTERVAL_MS = 5_000L
        private const val PREVIEW_MIN_UPDATE_INTERVAL_MS = 2_500L
        // Stationary fixes keep the car and nearby clearing fresh without recording a drive.
        private const val PREVIEW_MIN_DISTANCE_M = 0f
        private const val MAX_PREVIEW_ACCURACY_M = 100f
        private const val MAX_PREVIEW_LOCATION_AGE_MS = 60_000L
        private const val MAX_PREVIEW_LOCATION_AGE_NANOS = MAX_PREVIEW_LOCATION_AGE_MS * 1_000_000L
    }
}
