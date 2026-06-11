package com.ostrava.app.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ostrava.app.MainActivity
import com.ostrava.app.OstravaApp
import com.ostrava.app.R
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.defaultActivityTitle
import com.ostrava.app.domain.estimateCalories
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var fusedClient: FusedLocationProviderClient
    private var tickerJob: Job? = null

    private var startTimeWallMillis = 0L
    private var lastTickElapsed = 0L
    private var currentSegment = 0
    private var lastAcceptedPoint: TrackPoint? = null
    private var altitudeBaseline: Double? = null
    private var autoPauseEnabled = true
    private var weightKg = 70f
    private var imperialUnits = false
    private var lowSpeedSinceMillis: Long? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onNewLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(ActivityType.fromName(intent.getStringExtra(EXTRA_TYPE) ?: ActivityType.RUN.name))
            ACTION_PAUSE -> pause(manual = true)
            ACTION_RESUME -> resume()
            ACTION_FINISH -> finish()
            ACTION_DISCARD -> discard()
        }
        return START_STICKY
    }

    private fun start(type: ActivityType) {
        if (TrackingStateHolder.state.value.isActive) return
        startTimeWallMillis = System.currentTimeMillis()
        lastTickElapsed = SystemClock.elapsedRealtime()
        currentSegment = 0
        lastAcceptedPoint = null
        altitudeBaseline = null
        lowSpeedSinceMillis = null
        TrackingStateHolder.lastSavedActivityId.value = null
        TrackingStateHolder.state.value = RecordingState(status = TrackingStatus.TRACKING, type = type)

        val container = (application as OstravaApp).container
        scope.launch {
            val settings = container.settingsRepository.settings.first()
            autoPauseEnabled = settings.autoPauseEnabled
            weightKg = settings.weightKg
            imperialUnits = settings.imperialUnits
        }

        startForegroundWithNotification()
        requestLocationUpdates()
        startTicker()
    }

    private fun pause(manual: Boolean) {
        val state = TrackingStateHolder.state.value
        if (state.status != TrackingStatus.TRACKING) return
        TrackingStateHolder.state.value = state.copy(
            status = if (manual) TrackingStatus.PAUSED else TrackingStatus.AUTO_PAUSED,
        )
        updateNotification()
    }

    private fun resume() {
        val state = TrackingStateHolder.state.value
        if (state.status != TrackingStatus.PAUSED && state.status != TrackingStatus.AUTO_PAUSED) return
        currentSegment++
        lastAcceptedPoint = null
        lowSpeedSinceMillis = null
        lastTickElapsed = SystemClock.elapsedRealtime()
        TrackingStateHolder.state.value = state.copy(status = TrackingStatus.TRACKING)
        updateNotification()
    }

    private fun finish() {
        val state = TrackingStateHolder.state.value
        if (!state.isActive) return
        stopLocationUpdates()
        tickerJob?.cancel()

        if (state.points.size < 2 || state.distanceMeters < 10.0) {
            // Too short to be meaningful; drop it.
            TrackingStateHolder.lastSavedActivityId.value = -1L
            shutdown()
            return
        }

        val endTime = System.currentTimeMillis()
        val avgSpeed = state.avgSpeedMps
        val entity = ActivityEntity(
            type = state.type.name,
            title = defaultActivityTitle(state.type, startTimeWallMillis),
            startTime = startTimeWallMillis,
            endTime = endTime,
            movingTimeMillis = state.movingTimeMillis,
            distanceMeters = state.distanceMeters,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = state.maxSpeedMps.toDouble(),
            elevationGainMeters = state.elevationGainMeters,
            calories = estimateCalories(state.type, avgSpeed, state.movingTimeMillis, weightKg),
        )
        val points = state.points
        val repository = (application as OstravaApp).container.activityRepository
        scope.launch(Dispatchers.IO) {
            val id = repository.saveActivity(entity, points)
            TrackingStateHolder.lastSavedActivityId.value = id
            launch(Dispatchers.Main) { shutdown() }
        }
    }

    private fun discard() {
        stopLocationUpdates()
        tickerJob?.cancel()
        TrackingStateHolder.lastSavedActivityId.value = null
        shutdown()
    }

    private fun shutdown() {
        TrackingStateHolder.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_MS)
            .setMinUpdateDistanceMeters(0f)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        if (::fusedClient.isInitialized) {
            fusedClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun onNewLocation(location: Location) {
        val state = TrackingStateHolder.state.value
        if (!state.isActive) return

        val fix = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            timeMillis = location.time,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            segment = currentSegment,
        )
        var newState = state.copy(
            lastFix = fix,
            currentSpeedMps = fix.speedMps,
            gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        )

        val accuracyOk = !location.hasAccuracy() || location.accuracy <= MAX_ACCURACY_METERS

        if (state.status == TrackingStatus.TRACKING && accuracyOk) {
            val last = lastAcceptedPoint
            if (last == null) {
                lastAcceptedPoint = fix
                altitudeBaseline = fix.altitude
                newState = newState.copy(points = newState.points + fix)
            } else {
                val results = FloatArray(1)
                Location.distanceBetween(last.latitude, last.longitude, fix.latitude, fix.longitude, results)
                val displacement = results[0]
                if (displacement >= MIN_DISPLACEMENT_METERS) {
                    val gain = computeElevationGain(fix.altitude)
                    lastAcceptedPoint = fix
                    newState = newState.copy(
                        points = newState.points + fix,
                        distanceMeters = newState.distanceMeters + displacement,
                        elevationGainMeters = newState.elevationGainMeters + gain,
                        maxSpeedMps = maxOf(newState.maxSpeedMps, fix.speedMps),
                    )
                }
            }
        }

        TrackingStateHolder.state.value = newState
        handleAutoPause(fix.speedMps)
    }

    /** Hysteresis filter so barometric/GPS altitude noise does not inflate total climb. */
    private fun computeElevationGain(newAltitude: Double): Double {
        val baseline = altitudeBaseline ?: newAltitude
        return when {
            newAltitude - baseline >= ELEVATION_HYSTERESIS_METERS -> {
                val gain = newAltitude - baseline
                altitudeBaseline = newAltitude
                gain
            }
            newAltitude < baseline -> {
                altitudeBaseline = newAltitude
                0.0
            }
            else -> 0.0
        }
    }

    private fun handleAutoPause(speedMps: Float) {
        if (!autoPauseEnabled) return
        val state = TrackingStateHolder.state.value
        val isRide = state.type == ActivityType.RIDE
        val pauseBelow = if (isRide) 0.8f else 0.4f
        val resumeAbove = if (isRide) 1.6f else 0.9f
        val now = SystemClock.elapsedRealtime()

        when (state.status) {
            TrackingStatus.TRACKING -> {
                if (speedMps < pauseBelow) {
                    val since = lowSpeedSinceMillis ?: now.also { lowSpeedSinceMillis = it }
                    if (now - since >= AUTO_PAUSE_DELAY_MS) {
                        pause(manual = false)
                        lowSpeedSinceMillis = null
                    }
                } else {
                    lowSpeedSinceMillis = null
                }
            }
            TrackingStatus.AUTO_PAUSED -> {
                if (speedMps > resumeAbove) resume()
            }
            else -> Unit
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var notificationCounter = 0
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val state = TrackingStateHolder.state.value
                if (state.status == TrackingStatus.TRACKING) {
                    TrackingStateHolder.state.value =
                        state.copy(movingTimeMillis = state.movingTimeMillis + (now - lastTickElapsed))
                }
                lastTickElapsed = now
                if (++notificationCounter % 2 == 0) updateNotification()
                delay(1000)
            }
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = TrackingStateHolder.state.value
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val statusLabel = when (state.status) {
            TrackingStatus.PAUSED -> " (paused)"
            TrackingStatus.AUTO_PAUSED -> " (auto-paused)"
            else -> ""
        }
        return NotificationCompat.Builder(this, OstravaApp.TRACKING_CHANNEL_ID)
            .setContentTitle("Recording ${state.type.label}$statusLabel")
            .setContentText(
                "${formatDistance(state.distanceMeters, imperialUnits)} · ${formatDuration(state.movingTimeMillis)}"
            )
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ostrava.app.action.START"
        const val ACTION_PAUSE = "com.ostrava.app.action.PAUSE"
        const val ACTION_RESUME = "com.ostrava.app.action.RESUME"
        const val ACTION_FINISH = "com.ostrava.app.action.FINISH"
        const val ACTION_DISCARD = "com.ostrava.app.action.DISCARD"
        const val EXTRA_TYPE = "extra_type"

        const val NOTIFICATION_ID = 42
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val LOCATION_FASTEST_MS = 1000L
        private const val MAX_ACCURACY_METERS = 25f
        private const val MIN_DISPLACEMENT_METERS = 2f
        private const val ELEVATION_HYSTERESIS_METERS = 2.0
        private const val AUTO_PAUSE_DELAY_MS = 5000L

        fun start(context: Context, type: ActivityType) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun sendAction(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
