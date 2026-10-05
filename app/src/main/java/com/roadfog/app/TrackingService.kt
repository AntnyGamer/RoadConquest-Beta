package com.roadfog.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.roadfog.app.data.TrackingRepository
import com.roadfog.app.account.VerifiedDriving
import com.roadfog.app.matching.OsrmMatcher
import com.roadfog.app.util.LocationProviders
import com.roadfog.app.util.Prefs
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

class TrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var repository: TrackingRepository
    private lateinit var verifiedDriving: VerifiedDriving
    private val matcher = OsrmMatcher(BuildConfig.OSRM_API_URL)
    private val matchingExecutor = Executors.newSingleThreadScheduledExecutor()
    private val storageExecutor = Executors.newSingleThreadExecutor()
    private val matchingInFlight = AtomicBoolean(false)
    private val matchingRerunRequested = AtomicBoolean(false)
    private val delayedMatchScheduled = AtomicBoolean(false)
    // Accessed only on the matching executor; rebuilt from persistent deadlines after every run.
    private var deferredRetry: ScheduledFuture<*>? = null
    private val backlogContinuationScheduled = AtomicBoolean(false)
    private var lastObserved: Location? = null
    private var lastAccepted: Location? = null
    private var lastExplored: Location? = null
    private var lastBearingDegrees = 0.0
    @Volatile private var lastMatchAttempt = 0L
    @Volatile private var ready = false
    private var providerReceiverRegistered = false
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!ready) return
            if (locationManager.isLocationEnabled) requestLocations()
            sendBroadcast(Intent(ACTION_TRACKING_STATE_CHANGED).setPackage(packageName))
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = TrackingRepository(this)
        verifiedDriving = VerifiedDriving(this)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
        if (Prefs.isTrackingPaused(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            !locationManager.isLocationEnabled
        ) {
            stopSelf()
            return
        }

        val started = runCatching { startAsForeground() }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        ready = true
        isRunning = true
        ContextCompat.registerReceiver(this, providerReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        providerReceiverRegistered = true
        Prefs.markEverStarted(this)
        sendBroadcast(Intent(ACTION_TRACKING_STATE_CHANGED).setPackage(packageName))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_UNTIL_OPEN) {
            Prefs.setTrackingPaused(this, true)
            ready = false
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            sendBroadcast(Intent(ACTION_TRACKING_STATE_CHANGED).setPackage(packageName))
            return START_NOT_STICKY
        }
        if (Prefs.isTrackingPaused(this)) {
            ready = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ready) return START_NOT_STICKY
        requestLocations()
        maybeRunMatching(force = true)
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (!ready || Prefs.isTrackingPaused(this) || !isFreshLocation(location)) return
        // Verification consumes its independent live GPS stream; consent never selects tracking quality.
        verifiedDriving.offer(location)
        val previous = lastObserved
        if (!LocationProviders.isBetterFix(location, previous)) return
        if (previous != null && elapsedMillis(previous, location) == 0L) {
            // A better simultaneous source may improve the marker/next baseline, never add mileage twice.
            if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_M) lastObserved = Location(location)
            if (location.hasAccuracy() && location.accuracy <= MAX_PREVIEW_ACCURACY_M) sendLocationUpdate(location, lastBearingDegrees)
            return
        }
        val elapsedFromPrevious = if (previous == null) Long.MIN_VALUE else elapsedMillis(previous, location)
        val distanceFromPrevious = if (previous != null && elapsedFromPrevious in 1..MAX_MOTION_SAMPLE_AGE_MS) {
            previous.distanceTo(location)
        } else {
            Float.NaN
        }
        if (elapsedFromPrevious in 1..MAX_MOTION_SAMPLE_AGE_MS &&
            distanceFromPrevious / (elapsedFromPrevious / 1_000f) > MAX_PLAUSIBLE_SPEED_MPS
        ) return
        if (!location.isMock && location.hasAccuracy() && location.accuracy in 0.01f..25f &&
            (lastExplored?.distanceTo(location) ?: Float.POSITIVE_INFINITY) >= 20f) {
            val visited = Location(location)
            lastExplored = visited
            storageExecutor.execute {
                try {
                    if (repository.recordExploredPlace(visited)) {
                        sendBroadcast(Intent(ACTION_EXPLORATION_UPDATED).setPackage(packageName))
                    }
                } catch (error: Exception) {
                    Log.e("RoadConquest", "Could not save explored place", error)
                }
            }
        }
        // Do not let a coarse, duplicate, or out-of-order fix poison the next driving-speed
        // comparison. It may still update the live car below when current, but only a newer
        // road-quality fix becomes the motion baseline.
        if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_M &&
            (previous == null || elapsedFromPrevious > 0L)
        ) {
            lastObserved = Location(location)
        }

        // The live car marker follows a reasonable current fix even when that fix is not
        // accepted as driving history (for example, while stopped at a traffic light).
        if (location.hasBearing() && (!location.hasSpeed() || location.speed >= MIN_BEARING_SPEED_MPS)) {
            lastBearingDegrees = location.bearing.toDouble()
        }
        if (location.hasAccuracy() && location.accuracy <= MAX_PREVIEW_ACCURACY_M) {
            sendLocationUpdate(location, lastBearingDegrees)
        }

        if (lastAccepted?.let { elapsedMillis(it, location) > MAX_MOTION_SAMPLE_AGE_MS } == true) {
            lastAccepted = null
        }
        if (!isUsableDrivingLocation(location, previous, elapsedFromPrevious, distanceFromPrevious)) return

        // Save one recent anchor before the first confirmed driving point so the beginning
        // of a drive is not lost merely because driving speed could only be confirmed later.
        val toSave = mutableListOf<Location>()
        if (lastAccepted == null && previous != null &&
            previous.hasAccuracy() && previous.accuracy <= MAX_ACCURACY_M &&
            elapsedFromPrevious in 1..MAX_START_ANCHOR_AGE_MS
        ) {
            toSave += previous
        }

        val accepted = requireNotNull(lastObserved)
        toSave += accepted
        lastAccepted = accepted
        storageExecutor.execute {
            try {
                repository.insertLocations(toSave)
                sendBroadcast(Intent(ACTION_STATS_UPDATED).setPackage(packageName))
                maybeRunMatching()
            } catch (error: Exception) {
                Log.e("RoadConquest", "Could not save driving locations; stopping tracking", error)
                stopSelf()
            }
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (ready) {
            requestLocations()
            sendBroadcast(Intent(ACTION_TRACKING_STATE_CHANGED).setPackage(packageName))
        }
    }

    // Keep the existing registration while Location is off so the provider can resume when
    // the user turns Location back on; re-registering while disabled can lose that callback.
    override fun onProviderDisabled(provider: String) {
        if (ready) sendBroadcast(Intent(ACTION_TRACKING_STATE_CHANGED).setPackage(packageName))
        if (ready && locationManager.isLocationEnabled && LocationProviders.preferred(locationManager) != null) {
            requestLocations()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Flip readiness before shutting down the scheduler so an in-flight matcher cannot
        // enqueue more work while teardown is in progress.
        ready = false
        if (providerReceiverRegistered) runCatching { unregisterReceiver(providerReceiver) }
        providerReceiverRegistered = false
        if (::locationManager.isInitialized) runCatching { locationManager.removeUpdates(this) }
        matchingExecutor.shutdownNow()
        if (::verifiedDriving.isInitialized) verifiedDriving.close()
        // Drain accepted samples even when the user stops tracking. They were copied before enqueueing.
        storageExecutor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        sendBroadcast(Intent(ACTION_TRACKING_STATE_CHANGED).setPackage(packageName))
        super.onDestroy()
    }

    private fun requestLocations() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        runCatching { locationManager.removeUpdates(this) }
        LocationProviders.registerHighAccuracy(locationManager, ::registerProvider)
    }

    private fun registerProvider(provider: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val request = LocationRequest.Builder(LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(LOCATION_MIN_DISTANCE_M)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            locationManager.requestLocationUpdates(provider, request, mainExecutor, this)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun sendLocationUpdate(location: Location, bearingDegrees: Double) {
        sendBroadcast(
            Intent(ACTION_LOCATION_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_LATITUDE, location.latitude)
                .putExtra(EXTRA_LONGITUDE, location.longitude)
                .putExtra(EXTRA_BEARING, bearingDegrees)
                .putExtra(EXTRA_LOCATION_AGE_MS, if (location.elapsedRealtimeNanos > 0L) {
                    ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
                } else (System.currentTimeMillis() - location.time).coerceAtLeast(0L))
        )
    }

    private fun isUsableDrivingLocation(location: Location, previous: Location?): Boolean {
        if (previous == null) return false
        val ageMs = elapsedMillis(previous, location)
        val distance = if (ageMs in 1..MAX_MOTION_SAMPLE_AGE_MS) previous.distanceTo(location) else Float.NaN
        return isUsableDrivingLocation(location, previous, ageMs, distance)
    }

    private fun isUsableDrivingLocation(
        location: Location,
        previous: Location?,
        ageMs: Long,
        distance: Float
    ): Boolean {
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..MAX_ACCURACY_M ||
            previous == null || !previous.hasAccuracy() || !previous.accuracy.isFinite() || previous.accuracy !in 0f..MAX_ACCURACY_M ||
            ageMs <= 0L || ageMs > MAX_MOTION_SAMPLE_AGE_MS || !distance.isFinite()
        ) return false
        if (ageMs <= 4_000L && distance < 1.5f) return false

        val inferredSpeed = distance / (ageMs / 1_000f)
        if (inferredSpeed > MAX_PLAUSIBLE_SPEED_MPS) return false
        val measuredSpeed = maxOf(
            if (previous.hasSpeed()) previous.speed else 0f,
            if (location.hasSpeed()) location.speed else 0f
        )
        val movedBeyondAccuracy = distance > maxOf(5f, previous.accuracy + location.accuracy)
        return measuredSpeed >= MIN_DRIVING_SPEED_MPS ||
            (movedBeyondAccuracy && inferredSpeed >= MIN_DRIVING_SPEED_MPS)
    }

    private fun isFreshLocation(location: Location): Boolean {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0 ||
            (location.hasAccuracy() && (!location.accuracy.isFinite() || location.accuracy < 0f))
        ) return false
        val fixElapsedNanos = location.elapsedRealtimeNanos
        if (fixElapsedNanos > 0L) {
            val ageNanos = SystemClock.elapsedRealtimeNanos() - fixElapsedNanos
            return ageNanos in 0..MAX_LOCATION_AGE_NANOS
        }
        val fixTime = location.time
        if (fixTime <= 0L) return false
        val ageMillis = System.currentTimeMillis() - fixTime
        return ageMillis in 0..MAX_LOCATION_AGE_MS
    }

    private fun elapsedMillis(from: Location, to: Location): Long =
        if (from.elapsedRealtimeNanos > 0L && to.elapsedRealtimeNanos > 0L) {
            (to.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000L
        } else {
            to.time - from.time
        }

    private fun maybeRunMatching(force: Boolean = false) {
        if (!ready) return
        val now = SystemClock.elapsedRealtime()
        val cooldownRemaining = if (lastMatchAttempt == 0L) {
            0L
        } else {
            (MATCH_INTERVAL_MS - (now - lastMatchAttempt)).coerceAtLeast(0L)
        }
        if (!force && cooldownRemaining > 0L) {
            if (delayedMatchScheduled.compareAndSet(false, true)) {
                runCatching {
                    matchingExecutor.schedule(
                        {
                            delayedMatchScheduled.set(false)
                            if (ready) maybeRunMatching(force = true)
                        },
                        cooldownRemaining,
                        TimeUnit.MILLISECONDS
                    )
                }.onFailure { delayedMatchScheduled.set(false) }
            }
            return
        }
        if (!matchingInFlight.compareAndSet(false, true)) {
            matchingRerunRequested.set(true)
            return
        }

        val submitted = runCatching {
            matchingExecutor.execute {
                var needsRecovery = false
                try {
                    repeat(MAX_MATCH_BATCHES_PER_RUN) { batchIndex ->
                        val window = repository.loadMatchingWindow(MATCH_BATCH_SIZE)
                        val points = window.points
                        if (points.isEmpty()) return@execute

                        if (points.size < 2) {
                            val onlyId = window.markableIds.singleOrNull() ?: return@execute
                            // For the current drive, keep the lone point eligible and simply wait
                            // for the next fix. If it is blocking an older backlog, defer only that
                            // singleton and continue draining older, matchable history.
                            if (!repository.hasOlderEligiblePending(onlyId)) return@execute
                            repository.deferMatching(
                                window.markableIds,
                                System.currentTimeMillis() + MATCH_SINGLE_POINT_DEFERRAL_MS
                            )
                            if (batchIndex + 1 < MAX_MATCH_BATCHES_PER_RUN) {
                                Thread.sleep(MATCH_BATCH_PAUSE_MS)
                            } else {
                                scheduleBacklogContinuation()
                            }
                            return@repeat
                        }

                        lastMatchAttempt = SystemClock.elapsedRealtime()
                        val result = matcher.match(points)
                        if (result == null) {
                            repository.deferMatching(
                                window.markableIds,
                                System.currentTimeMillis() + MATCH_RETRY_AFTER_FAILURE_MS
                            )
                            // The newest batch may be intrinsically unmatchable, not merely
                            // offline. Since it is now deferred, keep draining older eligible
                            // history so one bad batch cannot permanently starve the queue.
                            if (batchIndex + 1 < MAX_MATCH_BATCHES_PER_RUN) {
                                Thread.sleep(MATCH_BATCH_PAUSE_MS)
                            } else {
                                scheduleBacklogContinuation()
                            }
                            return@repeat
                        }

                        val acceptedRoads = result.roads.filter { it.confidence >= MIN_MATCH_CONFIDENCE }
                        val matchedIds = result.matchedPointConfidences
                            .filter { (id, confidence) ->
                                confidence >= MIN_MATCH_CONFIDENCE && id in window.markableIds
                            }
                            .keys
                            .toList()

                        val resolvedIds = if (acceptedRoads.isNotEmpty() && matchedIds.isNotEmpty()) {
                            repository.completeMatch(acceptedRoads, matchedIds)
                            sendBroadcast(Intent(ACTION_ROADS_UPDATED).setPackage(packageName))
                            matchedIds.toSet()
                        } else {
                            emptySet()
                        }

                        val unresolvedIds = window.markableIds - resolvedIds
                        if (unresolvedIds.isNotEmpty()) {
                            // A valid response with only part of the trace matched usually
                            // benefits from retrying soon with fresh neighboring fixes. Keep the
                            // longer backoff for actual network/server failures above.
                            repository.deferMatching(
                                unresolvedIds,
                                System.currentTimeMillis() + MATCH_RETRY_AFTER_PARTIAL_MS
                            )
                        }

                        if (batchIndex + 1 < MAX_MATCH_BATCHES_PER_RUN) {
                            Thread.sleep(MATCH_BATCH_PAUSE_MS)
                        } else {
                            scheduleBacklogContinuation()
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    needsRecovery = true
                    Log.e("RoadConquest", "Road matching failed; pending points retained for retry", error)
                } finally {
                    matchingInFlight.set(false)
                    if (ready) scheduleDeferredRetry(needsRecovery)
                    if (matchingRerunRequested.getAndSet(false) && ready) maybeRunMatching()
                }
            }
        }.isSuccess
        if (!submitted) matchingInFlight.set(false)
    }

    private fun scheduleBacklogContinuation() {
        if (!backlogContinuationScheduled.compareAndSet(false, true)) return
        runCatching {
            matchingExecutor.schedule(
                {
                    backlogContinuationScheduled.set(false)
                    if (ready) maybeRunMatching(force = true)
                },
                MATCH_BACKLOG_CONTINUATION_MS,
                TimeUnit.MILLISECONDS
            )
        }.onFailure { backlogContinuationScheduled.set(false) }
    }

    private fun scheduleDeferredRetry(needsRecovery: Boolean = false) {
        deferredRetry?.cancel(false)
        deferredRetry = null
        if (!ready) return
        val now = System.currentTimeMillis()
        val next = try {
            repository.nextDeferredMatchAttempt(now)
        } catch (error: Exception) {
            Log.e("RoadConquest", "Could not read retry deadlines", error)
            now + MATCH_RETRY_AFTER_FAILURE_MS
        } ?: if (needsRecovery) now + MATCH_RETRY_AFTER_FAILURE_MS else return
        runCatching {
            deferredRetry = matchingExecutor.schedule(
                {
                    deferredRetry = null
                    if (ready) maybeRunMatching(force = true)
                },
                (next - now).coerceAtLeast(1_000L),
                TimeUnit.MILLISECONDS
            )
        }.onFailure { deferredRetry = null }
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopTracking = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP_UNTIL_OPEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("RoadConquest is tracking")
            .setContentText("Your driven roads are being saved locally")
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_tracking), stopTracking).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Drive tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required while RoadConquest records your driving location"
            }
        )
    }

    companion object {
        const val ACTION_LOCATION_UPDATE = "com.roadfog.app.LOCATION_UPDATE"
        const val ACTION_STATS_UPDATED = "com.roadfog.app.STATS_UPDATED"
        const val ACTION_ROADS_UPDATED = "com.roadfog.app.ROADS_UPDATED"
        const val ACTION_EXPLORATION_UPDATED = "com.roadfog.app.EXPLORATION_UPDATED"
        const val ACTION_TRACKING_STATE_CHANGED = "com.roadfog.app.TRACKING_STATE_CHANGED"
        const val ACTION_STOP_UNTIL_OPEN = "com.roadfog.app.STOP_UNTIL_OPEN"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_BEARING = "bearing"
        const val EXTRA_LOCATION_AGE_MS = "location_age_ms"

        private const val CHANNEL_ID = "roadfog_tracking"
        private const val NOTIFICATION_ID = 4101
        private const val LOCATION_INTERVAL_MS = 3_000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 1_500L
        // Require time between updates, not movement: stopped cars still need fresh live fixes.
        private const val LOCATION_MIN_DISTANCE_M = 0f
        private const val MAX_ACCURACY_M = 50f
        private const val MAX_PREVIEW_ACCURACY_M = 100f
        private const val MIN_DRIVING_SPEED_MPS = 2.2f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 100f
        private const val MIN_BEARING_SPEED_MPS = 0.5f
        private const val MAX_MOTION_SAMPLE_AGE_MS = 30_000L
        private const val MAX_LOCATION_AGE_MS = 60_000L
        private const val MAX_LOCATION_AGE_NANOS = MAX_LOCATION_AGE_MS * 1_000_000L
        private const val MAX_START_ANCHOR_AGE_MS = 10_000L
        private const val MATCH_INTERVAL_MS = 10_000L
        private const val MATCH_RETRY_AFTER_PARTIAL_MS = 30_000L
        private const val MATCH_RETRY_AFTER_FAILURE_MS = 5 * 60 * 1000L
        private const val MATCH_SINGLE_POINT_DEFERRAL_MS = 30_000L
        private const val MATCH_BACKLOG_CONTINUATION_MS = 30_000L
        private const val MATCH_BATCH_SIZE = OsrmMatcher.MAX_MATCH_POINTS
        private const val MAX_MATCH_BATCHES_PER_RUN = 4
        private const val MATCH_BATCH_PAUSE_MS = 1_100L
        private const val MIN_MATCH_CONFIDENCE = 0.45

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
