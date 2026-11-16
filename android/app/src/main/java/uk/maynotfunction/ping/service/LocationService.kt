package uk.maynotfunction.ping.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.bridgeApp
import uk.maynotfunction.ping.core.Cadence
import uk.maynotfunction.ping.core.LiveSession
import uk.maynotfunction.ping.core.LocationValidator
import uk.maynotfunction.ping.core.RelayWatchdog
import uk.maynotfunction.ping.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that shares this phone's location with the relay iPhone and keeps a
 * streaming subscription for friend updates.
 *
 * Battery: the GNSS chip is by far the biggest cost, so the location request adapts:
 *  - **Boost** while the app is on screen: high accuracy every 5 s for a smooth blue dot.
 *  - **Moving** in the background: what the Update frequency setting needs. Balanced and Saver
 *    use the fused provider's balanced-power mode (Wi-Fi/cell, GPS only when needed); only Fast
 *    asks for GPS-grade accuracy.
 *  - **Stationary** after [STATIONARY_AFTER_MS] without moving: no active request at all, only
 *    passive fixes other apps already pay for. The hardware significant-motion sensor (a wake-up
 *    trigger that costs almost nothing) switches back to Moving as soon as you start moving.
 *    Heartbeats while stationary take one quick balanced-power fix.
 *  - **Live** while someone views your location in Find My (the relay iPhone says so): high
 *    accuracy every few seconds, uploading each time you've moved, like a real iPhone does.
 * Explicit requests (Sync now, the iPhone asking for a location) take one fresh precise fix.
 */
class LocationService : LifecycleService() {

    companion object {
        const val ACTION_START = "uk.maynotfunction.ping.ACTION_START"
        const val ACTION_STOP = "uk.maynotfunction.ping.ACTION_STOP"
        const val ACTION_PUBLISH_NOW = "uk.maynotfunction.ping.ACTION_PUBLISH_NOW"
        const val ACTION_REFRESH_FRIENDS = "uk.maynotfunction.ping.ACTION_REFRESH_FRIENDS"

        private const val CHANNEL_ID = "location_sharing"
        private const val NOTIFICATION_ID = 4201
        private const val ALERT_CHANNEL_ID = "relay_alerts"
        private const val QUIET_NOTIFICATION_ID = 4202
        private const val MIN_UPLOAD_INTERVAL_MS = 10_000L
        private const val DEDUPE_WINDOW_MS = 3_000L
        /** Uploads only ask the iPhone to re-send friends this often, unless explicitly requested. */
        private const val FRIENDS_REQUEST_INTERVAL_MS = 15 * 60_000L
        /** No movement beyond [STATIONARY_RADIUS_M] for this long switches to Stationary. */
        private const val STATIONARY_AFTER_MS = 5 * 60_000L
        private const val STATIONARY_RADIUS_M = 75f
        /** One-shot fixes give up after this long. */
        private const val ONE_SHOT_TIMEOUT_MS = 30_000L
        /** A precise request reuses a last-known fix only if it's this fresh; heartbeats take any valid one. */
        private const val PRECISE_REUSE_MAX_AGE_MS = 15_000L
        /** Live: a fix every few seconds, uploaded once you've moved this far. */
        private const val LIVE_INTERVAL_MS = 3_000L
        private const val LIVE_DISTANCE_M = 5f
        /** The public ntfy.sh would answer uploads every 3 s with 429s. */
        private const val LIVE_PUBLIC_RELAY_INTERVAL_MS = 10_000L

        fun send(context: Context, action: String) {
            val intent = Intent(context, LocationService::class.java).setAction(action)
            if (action == ACTION_STOP) context.startService(intent) else context.startForegroundService(intent)
        }
    }

    private enum class Mode { Live, Boost, Moving, Stationary }

    private val repo by lazy { bridgeApp.repository }
    private val relay by lazy { bridgeApp.relay }
    private lateinit var locationManager: LocationManager
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val significantMotion: Sensor? by lazy { sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }

    private var running = false
    private var streamJob: Job? = null
    private var heartbeatJob: Job? = null
    private var modeJob: Job? = null

    private var mode: Mode? = null
    private var stationary = false
    private var motionArmed = false
    private var anchor: Location? = null
    private var lastMovementMs = 0L

    private var lastUploaded: Location? = null
    private var lastUploadMs = 0L
    private var lastFriendsRequestMs = 0L
    private var friendsRequestPending = false
    private var quietAlertShown = false

    private val live = LiveSession()
    private var liveJob: Job? = null
    private var liveUploadIntervalMs = LIVE_INTERVAL_MS

    private val listener = LocationListener { handleFix(it, force = false) }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // The trigger disarms itself after firing.
            motionArmed = false
            lifecycleScope.launch {
                if (stationary) {
                    stationary = false
                    lastMovementMs = System.currentTimeMillis()
                    anchor = null
                    applyMode()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ID, "Live location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your location is shared with your relay iPhone."
            },
            NotificationChannel(ALERT_CHANNEL_ID, "Relay iPhone alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when the relay iPhone stops responding."
            },
        ))
        // Only redraw the notification when what it shows changes (not on every fix).
        lifecycleScope.launch {
            repo.status.map { notificationText(it.lastError, it.lastUploadMs, it.lastTransport) to it.lastUploadMs }
                .distinctUntilChanged()
                .collect {
                    if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                }
        }
        // Clears the quiet-iPhone alert as soon as a reply arrives.
        lifecycleScope.launch {
            repo.status.map { it.unansweredSinceMs }.distinctUntilChanged().collect { checkRelayQuiet() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            lifecycleScope.launch { repo.setSharingEnabled(false) }
            stopEverything()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission() || !startInForeground()) {
            repo.recordUploadFailure("Location permission required")
            stopEverything()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            lastMovementMs = System.currentTimeMillis()
            repo.clearUnansweredRequests()
            // Re-plan the location request whenever the UI shows/hides or the cadence changes.
            modeJob = lifecycleScope.launch {
                combine(repo.uiVisible, repo.settings.map { it.cadence }.distinctUntilChanged()) { v, c -> v to c }
                    .distinctUntilChanged()
                    .collect { applyMode(force = true) }
            }
            // One streaming subscription, restarted whenever the pairing code changes.
            streamJob = lifecycleScope.launch {
                repo.settings.map { it.pairingCode }.distinctUntilChanged().collectLatest {
                    relay.streamDownlink(
                        onLocateRequest = { withContext(Dispatchers.Main) { publishNow(requestFriends = true, precise = true) } },
                        onLive = { active -> withContext(Dispatchers.Main) { onLiveSignal(active) } },
                    )
                }
            }
            heartbeatJob = lifecycleScope.launch { heartbeatLoop() }
        }

        when (action) {
            ACTION_REFRESH_FRIENDS -> lifecycleScope.launch {
                relay.sendRefreshRequest() // the reply arrives on the downlink stream
            }
            else -> publishNow(requestFriends = true, precise = true)
        }
        return START_STICKY
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startInForeground(): Boolean = runCatching {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }.isSuccess

    private fun stopEverything() {
        running = false
        streamJob?.cancel()
        heartbeatJob?.cancel()
        modeJob?.cancel()
        liveJob?.cancel()
        live.reset()
        removeLocationRequests()
        cancelQuietAlert()
        repo.setLocationMode("Off")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Location request planning -------------------------------------------------------------

    private fun activeProvider(): String? {
        val all = locationManager.allProviders
        return when {
            LocationManager.FUSED_PROVIDER in all -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in all -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in all -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    private fun desiredMode(): Mode = when {
        live.active -> Mode.Live
        repo.uiVisible.value -> Mode.Boost
        stationary -> Mode.Stationary
        else -> Mode.Moving
    }

    private fun movingRequest(cadence: Cadence): LocationRequest = when (cadence) {
        Cadence.Fast -> LocationRequest.Builder(15_000L)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateDistanceMeters(10f)
            .build()
        Cadence.Balanced -> LocationRequest.Builder(60_000L)
            .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
            .setMinUpdateIntervalMillis(30_000L)
            .setMinUpdateDistanceMeters(25f)
            .build()
        Cadence.Saver -> LocationRequest.Builder(180_000L)
            .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
            .setMinUpdateIntervalMillis(60_000L)
            .setMinUpdateDistanceMeters(50f)
            .setMaxUpdateDelayMillis(360_000L) // lets the platform batch fixes and wake less often
            .build()
    }

    private fun removeLocationRequests() {
        runCatching { locationManager.removeUpdates(listener) }
        if (motionArmed) {
            runCatching { sensorManager?.cancelTriggerSensor(motionListener, significantMotion) }
            motionArmed = false
        }
    }

    @Suppress("MissingPermission")
    private fun applyMode(force: Boolean = false) {
        if (!running) return
        val target = desiredMode()
        if (target == mode && !force) return
        mode = target
        removeLocationRequests()
        val cadence = repo.settings.value.cadence
        runCatching {
            when (target) {
                Mode.Live -> {
                    val provider = activeProvider() ?: return
                    locationManager.requestLocationUpdates(provider,
                        LocationRequest.Builder(LIVE_INTERVAL_MS)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .build(),
                        mainExecutor, listener)
                    repo.setLocationMode("Live • precise")
                }
                Mode.Boost -> {
                    val provider = activeProvider() ?: return
                    locationManager.requestLocationUpdates(provider,
                        LocationRequest.Builder(5_000L)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setMinUpdateDistanceMeters(5f)
                            .build(),
                        mainExecutor, listener)
                    repo.setLocationMode("Precise (app open)")
                }
                Mode.Moving -> {
                    val provider = activeProvider() ?: return
                    locationManager.requestLocationUpdates(provider, movingRequest(cadence), mainExecutor, listener)
                    repo.setLocationMode(if (cadence == Cadence.Fast) "Moving • precise" else "Moving • balanced power")
                }
                Mode.Stationary -> {
                    // Free: only fixes other apps already requested.
                    locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                        LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                            .setMinUpdateIntervalMillis(60_000L)
                            .build(),
                        mainExecutor, listener)
                    motionArmed = significantMotion?.let { sensorManager?.requestTriggerSensor(motionListener, it) } == true
                    repo.setLocationMode("Stationary • location off until you move")
                }
            }
            repo.log("Location mode: ${repo.locationMode.value}")
        }.onFailure { repo.recordUploadFailure("Couldn't change location updates: ${it.message}") }
    }

    /** Tracks movement; after a while in one place, switches to Stationary (if the sensor exists). */
    private fun trackMovement(location: Location) {
        val now = System.currentTimeMillis()
        val a = anchor
        // Allow for both fixes' uncertainty so Wi-Fi jitter doesn't count as movement.
        if (a == null || location.distanceTo(a) > maxOf(STATIONARY_RADIUS_M, location.accuracy + a.accuracy)) {
            anchor = Location(location)
            lastMovementMs = now
            if (stationary) {
                stationary = false
                applyMode()
            }
            return
        }
        checkStationary()
    }

    /**
     * Called on fixes and from the heartbeat loop: with a distance filter the provider sends no
     * fixes at all while you're still, so this can't rely on fixes arriving.
     */
    private fun checkStationary() {
        if (!stationary && significantMotion != null &&
            System.currentTimeMillis() - lastMovementMs >= STATIONARY_AFTER_MS
        ) {
            stationary = true
            applyMode()
        }
    }

    // ---- Live sessions -------------------------------------------------------------------------

    /** The relay iPhone's `live` message: someone started viewing (or still is), or stopped. */
    private fun onLiveSignal(active: Boolean) {
        if (!running || !repo.settings.value.sharingEnabled) return
        when (live.onSignal(active, System.currentTimeMillis())) {
            LiveSession.Change.Started -> {
                val publicRelay = repo.settings.value.pairing?.usesPublicRelay == true
                liveUploadIntervalMs = if (publicRelay) LIVE_PUBLIC_RELAY_INTERVAL_MS else LIVE_INTERVAL_MS
                repo.log("Someone is viewing your location: sharing live")
                applyMode()
                publishNow(requestFriends = false, precise = true)
                liveJob?.cancel()
                liveJob = lifecycleScope.launch {
                    while (live.active) {
                        delay(5_000L)
                        if (live.check(System.currentTimeMillis()) == LiveSession.Change.Ended) {
                            liveEnded("Live sharing ended (no word from the relay iPhone, or 30 minutes passed)")
                        }
                    }
                }
            }
            LiveSession.Change.Ended -> liveEnded("Live sharing ended")
            LiveSession.Change.None -> Unit
        }
    }

    private fun liveEnded(message: String) {
        liveJob?.cancel()
        liveJob = null
        repo.log(message)
        applyMode()
    }

    // ---- Heartbeat and uploads -----------------------------------------------------------------

    /** Re-sends the location while stationary so the iPhone's copy doesn't expire. */
    private suspend fun heartbeatLoop() {
        while (lifecycleScope.isActive) {
            checkStationary()
            checkRelayQuiet()
            val settings = repo.settings.value
            val due = settings.cadence.heartbeatMs - (System.currentTimeMillis() - lastUploadMs)
            if (due <= 0) {
                if (settings.sharingEnabled) publishNow(requestFriends = false, precise = false)
                delay(60_000L)
            } else {
                // Sleep until the next heartbeat is due (capped so cadence changes are picked up).
                delay(due.coerceIn(5_000L, 60_000L))
            }
        }
    }

    /**
     * Uploads a recent fix now if there is one (e.g. a passive fix another app paid for),
     * otherwise takes one fresh fix: precise for explicit requests, balanced power for heartbeats.
     * Precise requests only reuse a fix from the last [PRECISE_REUSE_MAX_AGE_MS].
     */
    @Suppress("MissingPermission")
    private fun publishNow(requestFriends: Boolean, precise: Boolean) {
        if (requestFriends) friendsRequestPending = true
        val recent = listOfNotNull(activeProvider(), LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .filter { LocationValidator.isValid(it) }
            .minByOrNull { it.accuracy }
        val recentAgeMs = recent?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L }
        if (recent != null && (!precise || (recent.accuracy <= 50f && recentAgeMs!! <= PRECISE_REUSE_MAX_AGE_MS))) {
            handleFix(recent, force = true)
            return
        }
        val provider = activeProvider() ?: return
        val request = LocationRequest.Builder(0L)
            .setQuality(if (precise) LocationRequest.QUALITY_HIGH_ACCURACY else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(ONE_SHOT_TIMEOUT_MS)
            .build()
        runCatching {
            locationManager.getCurrentLocation(provider, request, CancellationSignal(), mainExecutor) { loc ->
                when {
                    loc != null -> handleFix(loc, force = true)
                    recent != null -> handleFix(recent, force = true)
                    requestFriends -> lifecycleScope.launch { relay.sendRefreshRequest() }
                }
            }
        }
    }

    private fun handleFix(location: Location, force: Boolean) {
        when (val result = LocationValidator.validate(location)) {
            is LocationValidator.Result.Rejected -> { repo.recordRejected(result.reason); return }
            LocationValidator.Result.Valid -> repo.recordFix(location)
        }
        trackMovement(location)

        val settings = repo.settings.value
        if (!settings.sharingEnabled && !force) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastUploadMs
        // Start, "sync now" and the first heartbeat can all fire together; one upload is enough.
        if (force && elapsed < DEDUPE_WINDOW_MS) return
        val moved = lastUploaded?.let { location.distanceTo(it) } ?: Float.MAX_VALUE
        val shouldUpload = force ||
            (live.active && moved >= LIVE_DISTANCE_M && elapsed >= liveUploadIntervalMs) ||
            (moved >= settings.cadence.distanceM && elapsed >= MIN_UPLOAD_INTERVAL_MS) ||
            elapsed >= settings.cadence.heartbeatMs
        if (!shouldUpload) return

        lastUploaded = Location(location)
        lastUploadMs = now
        lifecycleScope.launch {
            repo.setSyncing(true)
            try {
                val wantFriends = friendsRequestPending || now - lastFriendsRequestMs >= FRIENDS_REQUEST_INTERVAL_MS
                if (relay.publishLocation(location, requestFriendsRefresh = wantFriends) && wantFriends) {
                    lastFriendsRequestMs = now
                    friendsRequestPending = false
                }
            } finally {
                repo.setSyncing(false)
            }
        }
    }

    // ---- Quiet relay iPhone --------------------------------------------------------------------

    /** Raises (once) or clears the alert for a relay iPhone that has stopped answering. */
    private fun checkRelayQuiet() {
        if (!running) return
        val quiet = RelayWatchdog.isQuiet(repo.status.value.unansweredSinceMs, System.currentTimeMillis())
        if (quiet && !quietAlertShown) {
            quietAlertShown = true
            repo.log("Relay iPhone hasn't answered in ${RelayWatchdog.QUIET_AFTER_MS / 60_000} min")
            getSystemService(NotificationManager::class.java).notify(QUIET_NOTIFICATION_ID, buildQuietNotification())
        } else if (!quiet && quietAlertShown) {
            repo.log("Relay iPhone is answering again")
            cancelQuietAlert()
        }
    }

    private fun cancelQuietAlert() {
        quietAlertShown = false
        getSystemService(NotificationManager::class.java).cancel(QUIET_NOTIFICATION_ID)
    }

    private fun buildQuietNotification(): Notification {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "Ping hasn't heard from it in ${RelayWatchdog.QUIET_AFTER_MS / 60_000} minutes. " +
            "Check that it's on, online and still jailbroken."
        return Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share_location)
            .setContentTitle("Relay iPhone isn't responding")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .build()
    }

    // ---- Notification --------------------------------------------------------------------------

    private fun notificationText(error: String?, lastUploadMs: Long, transport: String?) = when {
        error != null -> "Relay problem: $error"
        lastUploadMs > 0 -> "Updated via ${transport ?: "relay"}"
        else -> "Getting your location…"
    }

    private fun buildNotification(): Notification {
        val status = repo.status.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val syncNow = PendingIntent.getForegroundService(this, 1,
            Intent(this, LocationService::class.java).setAction(ACTION_PUBLISH_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_share_location)
            .setContentTitle("Sharing your location")
            .setContentText(notificationText(status.lastError, status.lastUploadMs, status.lastTransport))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_LOCATION_SHARING)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(status.lastUploadMs > 0)
            .setWhen(status.lastUploadMs)
            .addAction(Notification.Action.Builder(null, "Sync now", syncNow).build())
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        removeLocationRequests()
        super.onDestroy()
    }
}
