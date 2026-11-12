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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
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
import com.ostrava.app.data.db.RecordingDraftEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.GpsSmoother
import com.ostrava.app.domain.IntervalConfig
import com.ostrava.app.domain.IntervalPhase
import com.ostrava.app.domain.METERS_PER_MILE
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.computeActivityStats
import com.ostrava.app.domain.defaultActivityTitle
import com.ostrava.app.domain.estimateCalories
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.maxPlausibleSpeedMps
import com.ostrava.app.domain.spokenDuration
import com.ostrava.app.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var fusedClient: FusedLocationProviderClient
    private var tickerJob: Job? = null

    private var startTimeWallMillis = 0L
    private var lastTickElapsed = 0L
    private var currentSegment = 0
    private var lastAcceptedPoint: TrackPoint? = null
    private var altitudeBaseline: Double? = null
    private var lowSpeedSinceMillis: Long? = null
    private val smoother = GpsSmoother()
    private var rejectedFixes = 0
    private var finishing = false

    // Writes to the crash-recovery draft run one at a time, in order, off the main thread.
    private val draftWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var tickCount = 0

    // Settings captured at start
    private var autoPauseEnabled = true
    private var weightKg = 70f
    private var imperialUnits = false
    private var audioCues = true
    private var haptics = true

    // Audio cues
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastAnnouncedSplit = 0
    private var lastSplitTimeMillis = 0L

    // Heart rate sampling
    private val hrSamples = mutableListOf<Int>()

    // Interval workout
    private var intervalPhases: List<Pair<String, Int>> = emptyList()
    private var lastPhaseIndex = -1
    private var workoutCompleteAnnounced = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onNewLocation(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                type = ActivityType.fromName(intent.getStringExtra(EXTRA_TYPE) ?: ActivityType.RUN.name),
                intervals = IntervalConfig.fromIntArray(intent.getIntArrayExtra(EXTRA_WORKOUT)),
            )
            ACTION_PAUSE -> pause(manual = true)
            ACTION_RESUME -> resume()
            ACTION_FINISH -> finish(
                title = intent.getStringExtra(EXTRA_TITLE),
                feel = intent.getStringExtra(EXTRA_FEEL),
            )
            ACTION_DISCARD -> discard()
            ACTION_RESUME_DRAFT -> resumeFromDraft()
            // A null intent means Android restarted the service after the process died
            // mid-recording: pick the recording back up from the saved draft.
            null -> if (intent == null) resumeFromDraft()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) {
            for (write in draftWrites) {
                runCatching { write() }
            }
        }
    }

    private val drafts get() = (application as OstravaApp).container.draftRepository

    private fun enqueueDraftWrite(write: suspend () -> Unit) {
        draftWrites.trySend(write)
    }

    private fun saveDraftProgress() {
        val state = TrackingStateHolder.state.value
        if (!state.isActive) return
        val meta = RecordingDraftEntity(
            type = state.type.name,
            startTime = startTimeWallMillis,
            movingTimeMillis = state.movingTimeMillis,
            distanceMeters = state.distanceMeters,
            elevationGainMeters = state.elevationGainMeters,
            maxSpeedMps = state.maxSpeedMps.toDouble(),
            segment = currentSegment,
            updatedAt = System.currentTimeMillis(),
        )
        enqueueDraftWrite { drafts.updateProgress(meta) }
    }

    private fun loadSettings(onLoaded: () -> Unit = {}) {
        val container = (application as OstravaApp).container
        scope.launch {
            val settings = container.settingsRepository.settings.first()
            autoPauseEnabled = settings.autoPauseEnabled
            weightKg = settings.weightKg
            imperialUnits = settings.imperialUnits
            audioCues = settings.audioCuesEnabled
            haptics = settings.hapticsEnabled
            onLoaded()
        }
    }

    private fun ensureTextToSpeech() {
        if (tts == null) {
            tts = TextToSpeech(applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.getDefault()
            }
        }
    }

    /**
     * Continues a recording that was interrupted by a crash or by Android killing the app.
     * The route so far comes from the draft; the gap while the app was dead is not counted.
     */
    private fun resumeFromDraft() {
        if (TrackingStateHolder.state.value.isActive) return
        try {
            startForegroundWithNotification()
        } catch (_: Exception) {
            // Android won't let a location service start from the background. The draft
            // stays on disk and the app offers to resume or save it when next opened.
            stopSelf()
            return
        }
        scope.launch {
            val draft = withContext(Dispatchers.IO) { runCatching { drafts.load() }.getOrNull() }
            if (draft == null) {
                shutdown()
                return@launch
            }
            val type = ActivityType.fromName(draft.meta.type)
            val points = draft.points
            startTimeWallMillis = draft.meta.startTime
            lastTickElapsed = SystemClock.elapsedRealtime()
            currentSegment = maxOf(draft.meta.segment, points.maxOfOrNull { it.segment } ?: 0) + 1
            lastAcceptedPoint = null
            altitudeBaseline = null
            lowSpeedSinceMillis = null
            smoother.reset()
            rejectedFixes = 0
            finishing = false
            hrSamples.clear()
            hrSamples += points.mapNotNull { it.heartRate }
            intervalPhases = emptyList()
            val stats = computeActivityStats(points)
            TrackingStateHolder.lastSavedActivityId.value = null
            TrackingStateHolder.state.value = RecordingState(
                status = TrackingStatus.TRACKING,
                type = type,
                distanceMeters = maxOf(stats.distanceMeters, draft.meta.distanceMeters),
                movingTimeMillis = draft.meta.movingTimeMillis.takeIf { it > 0 } ?: stats.movingTimeMillis,
                maxSpeedMps = draft.meta.maxSpeedMps.toFloat(),
                elevationGainMeters = maxOf(stats.elevationGainMeters, draft.meta.elevationGainMeters),
                points = points,
                lastFix = points.lastOrNull(),
            )
            loadSettings {
                // Don't re-announce splits that were already spoken before the interruption.
                val splitLength = if (imperialUnits) METERS_PER_MILE else 1000.0
                val state = TrackingStateHolder.state.value
                lastAnnouncedSplit = (state.distanceMeters / splitLength).toInt()
                lastSplitTimeMillis = state.movingTimeMillis
            }
            ensureTextToSpeech()
            requestLocationUpdates()
            startTicker()
            updateNotification()
        }
    }

    private fun start(type: ActivityType, intervals: IntervalConfig?) {
        if (TrackingStateHolder.state.value.isActive) return
        startTimeWallMillis = System.currentTimeMillis()
        lastTickElapsed = SystemClock.elapsedRealtime()
        currentSegment = 0
        lastAcceptedPoint = null
        altitudeBaseline = null
        lowSpeedSinceMillis = null
        lastAnnouncedSplit = 0
        lastSplitTimeMillis = 0L
        hrSamples.clear()
        intervalPhases = intervals?.phases() ?: emptyList()
        lastPhaseIndex = -1
        workoutCompleteAnnounced = false
        TrackingStateHolder.lastSavedActivityId.value = null
        TrackingStateHolder.state.value = RecordingState(status = TrackingStatus.TRACKING, type = type)

        smoother.reset()
        rejectedFixes = 0
        finishing = false
        loadSettings()
        ensureTextToSpeech()
        val startTime = startTimeWallMillis
        enqueueDraftWrite { drafts.begin(type, startTime) }

        startForegroundWithNotification()
        requestLocationUpdates()
        startTicker()
        vibrate(longArrayOf(0, 300))
        speak("${type.label} started")
    }

    private fun pause(manual: Boolean) {
        val state = TrackingStateHolder.state.value
        if (state.status != TrackingStatus.TRACKING) return
        TrackingStateHolder.state.value = state.copy(
            status = if (manual) TrackingStatus.PAUSED else TrackingStatus.AUTO_PAUSED,
        )
        vibrate(longArrayOf(0, 150, 100, 150))
        if (!manual) speak("Auto paused")
        saveDraftProgress()
        updateNotification()
    }

    private fun resume() {
        val state = TrackingStateHolder.state.value
        if (state.status != TrackingStatus.PAUSED && state.status != TrackingStatus.AUTO_PAUSED) return
        val wasAuto = state.status == TrackingStatus.AUTO_PAUSED
        currentSegment++
        lastAcceptedPoint = null
        lowSpeedSinceMillis = null
        smoother.reset()
        rejectedFixes = 0
        lastTickElapsed = SystemClock.elapsedRealtime()
        TrackingStateHolder.state.value = state.copy(status = TrackingStatus.TRACKING)
        vibrate(longArrayOf(0, 150))
        if (wasAuto) speak("Resumed")
        updateNotification()
    }

    private fun finish(title: String?, feel: String?) {
        val state = TrackingStateHolder.state.value
        if (!state.isActive || finishing) return
        finishing = true
        stopLocationUpdates()
        tickerJob?.cancel()

        if (state.points.size < 2 || state.distanceMeters < 10.0) {
            // Too short to be meaningful; drop it.
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { drafts.clear() } }
                TrackingStateHolder.lastSavedActivityId.value = -1L
                shutdown()
            }
            return
        }

        val endTime = System.currentTimeMillis()
        val avgSpeed = state.avgSpeedMps
        val entity = ActivityEntity(
            type = state.type.name,
            title = title?.takeIf { it.isNotBlank() }
                ?: defaultActivityTitle(state.type, startTimeWallMillis),
            startTime = startTimeWallMillis,
            endTime = endTime,
            movingTimeMillis = state.movingTimeMillis,
            distanceMeters = state.distanceMeters,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = state.maxSpeedMps.toDouble(),
            elevationGainMeters = state.elevationGainMeters,
            calories = estimateCalories(state.type, avgSpeed, state.movingTimeMillis, weightKg),
            avgHeartRate = hrSamples.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
            maxHeartRate = hrSamples.maxOrNull(),
            feel = feel?.takeIf { it.isNotBlank() },
        )
        val points = state.points
        val app = application as OstravaApp
        val repository = app.container.activityRepository
        vibrate(longArrayOf(0, 300, 150, 300))
        scope.launch {
            val id = withContext(Dispatchers.IO) {
                val saved = repository.saveActivityWithEfforts(entity, points)
                // Only once the activity is safely stored does the recovery draft go away.
                runCatching { drafts.clear() }
                saved
            }
            val records = withContext(Dispatchers.IO) {
                runCatching { repository.newRecords(id) }.getOrDefault(emptyList())
            }
            app.onActivitySaved(entity.copy(id = id))
            TrackingStateHolder.lastSavedActivityId.value = id
            TrackingStateHolder.reset()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (records.isNotEmpty()) {
                Notifications.showPersonalRecord(this@TrackingService, id, entity.title, records)
                val spoken = records.joinToString(", ") { it.label }
                speak("New personal record. $spoken.")
                // Give the announcement time to play before the service goes away.
                if (audioCues && ttsReady) delay(RECORD_ANNOUNCEMENT_MILLIS)
            }
            stopSelf()
        }
    }

    private fun discard() {
        if (finishing) return
        finishing = true
        stopLocationUpdates()
        tickerJob?.cancel()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { drafts.clear() } }
            TrackingStateHolder.lastSavedActivityId.value = null
            shutdown()
        }
    }

    private fun shutdown() {
        TrackingStateHolder.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        tts?.shutdown()
        tts = null
        draftWrites.close()
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

        val accuracy = if (location.hasAccuracy()) location.accuracy else DEFAULT_ACCURACY_METERS
        val speed = if (location.hasSpeed()) location.speed else 0f
        val accuracyOk = accuracy <= MAX_ACCURACY_METERS

        // Smooth while tracking; a fix this poor would only drag the estimate around.
        val (latitude, longitude) = if (state.status == TrackingStatus.TRACKING && accuracyOk) {
            smoother.update(location.latitude, location.longitude, accuracy, location.time, speed)
        } else {
            location.latitude to location.longitude
        }
        val fix = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            timeMillis = location.time,
            speedMps = speed,
            segment = currentSegment,
            heartRate = HeartRateMonitor.bpm.value?.takeIf { it > 30 },
        )
        var newState = state.copy(
            lastFix = fix,
            currentSpeedMps = fix.speedMps,
            gpsAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
        )

        if (state.status == TrackingStatus.TRACKING && accuracyOk) {
            val last = lastAcceptedPoint
            if (last == null) {
                accept(fix)
                altitudeBaseline = fix.altitude
                newState = newState.copy(points = newState.points + fix)
            } else {
                val results = FloatArray(1)
                Location.distanceBetween(last.latitude, last.longitude, fix.latitude, fix.longitude, results)
                val displacement = results[0]
                val elapsedSeconds = (fix.timeMillis - last.timeMillis) / 1000.0
                val impliedSpeed = if (elapsedSeconds > 0) displacement / elapsedSeconds else 0.0
                // Poorer fixes must move further before they count, so jitter isn't logged as distance.
                val minDisplacement = (accuracy * 0.3f).coerceIn(MIN_DISPLACEMENT_METERS, MAX_MIN_DISPLACEMENT_METERS)

                if (impliedSpeed > maxPlausibleSpeedMps(state.type)) {
                    // A jump no athlete could make. Usually a one-off glitch; if it persists the
                    // position really did move (tunnel, lost signal), so restart the line there.
                    if (++rejectedFixes >= MAX_REJECTED_FIXES) {
                        rejectedFixes = 0
                        currentSegment++
                        smoother.reset()
                        val restart = fix.copy(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            segment = currentSegment,
                        )
                        accept(restart)
                        altitudeBaseline = restart.altitude
                        newState = newState.copy(points = newState.points + restart, lastFix = restart)
                    }
                } else if (displacement >= minDisplacement) {
                    rejectedFixes = 0
                    val gain = computeElevationGain(fix.altitude)
                    accept(fix)
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
        announceSplitIfCrossed(newState)
        handleAutoPause(fix.speedMps)
    }

    /** Records a point as part of the route and writes it to the crash-recovery draft. */
    private fun accept(point: TrackPoint) {
        lastAcceptedPoint = point
        enqueueDraftWrite { drafts.appendPoint(point) }
    }

    /** Speaks a cue when the athlete crosses the next whole km/mile. */
    private fun announceSplitIfCrossed(state: RecordingState) {
        if (!audioCues) return
        val splitLength = if (imperialUnits) METERS_PER_MILE else 1000.0
        val completed = (state.distanceMeters / splitLength).toInt()
        if (completed <= lastAnnouncedSplit) return
        lastAnnouncedSplit = completed
        val lapMillis = state.movingTimeMillis - lastSplitTimeMillis
        lastSplitTimeMillis = state.movingTimeMillis
        val unit = if (imperialUnits) "mile" else "kilometer"
        val plural = if (completed == 1) unit else "${unit}s"
        speak(
            "$completed $plural. Total time ${spokenDuration(state.movingTimeMillis)}. " +
                "Last $unit ${spokenDuration(lapMillis)}."
        )
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
                    val movingTime = state.movingTimeMillis + (now - lastTickElapsed)
                    val bpm = HeartRateMonitor.bpm.value
                    if (bpm != null && bpm > 30) hrSamples += bpm
                    TrackingStateHolder.state.value = state.copy(
                        movingTimeMillis = movingTime,
                        heartRateBpm = bpm,
                        intervalPhase = updateIntervalPhase(movingTime),
                    )
                } else if (state.isActive) {
                    TrackingStateHolder.state.value =
                        state.copy(heartRateBpm = HeartRateMonitor.bpm.value)
                }
                lastTickElapsed = now
                if (++notificationCounter % 2 == 0) updateNotification()
                if (++tickCount % DRAFT_SAVE_INTERVAL_TICKS == 0) saveDraftProgress()
                delay(1000)
            }
        }
    }

    /** Drives the interval workout off moving time; announces phase transitions. */
    private fun updateIntervalPhase(movingTimeMillis: Long): IntervalPhase? {
        if (intervalPhases.isEmpty()) return null
        val elapsedSec = (movingTimeMillis / 1000).toInt()
        var boundary = 0
        intervalPhases.forEachIndexed { index, (name, durationSec) ->
            boundary += durationSec
            if (elapsedSec < boundary) {
                if (index != lastPhaseIndex) {
                    lastPhaseIndex = index
                    speak(name.substringBefore('/').trim())
                    vibrate(longArrayOf(0, 200, 100, 200))
                }
                return IntervalPhase(
                    name = name,
                    index = index + 1,
                    total = intervalPhases.size,
                    remainingSec = boundary - elapsedSec,
                )
            }
        }
        if (!workoutCompleteAnnounced) {
            workoutCompleteAnnounced = true
            speak("Workout complete. Great job.")
            vibrate(longArrayOf(0, 300, 100, 300, 100, 300))
        }
        return null
    }

    private fun speak(text: String) {
        if (!audioCues || !ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ostrava-cue")
    }

    private fun vibrate(pattern: LongArray) {
        if (!haptics) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
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
        const val ACTION_RESUME_DRAFT = "com.ostrava.app.action.RESUME_DRAFT"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_FEEL = "extra_feel"
        const val EXTRA_WORKOUT = "extra_workout"

        const val NOTIFICATION_ID = 42
        private const val LOCATION_INTERVAL_MS = 2000L
        private const val LOCATION_FASTEST_MS = 1000L
        private const val MAX_ACCURACY_METERS = 30f
        private const val DEFAULT_ACCURACY_METERS = 15f
        private const val MIN_DISPLACEMENT_METERS = 2f
        private const val MAX_MIN_DISPLACEMENT_METERS = 8f
        private const val MAX_REJECTED_FIXES = 5
        private const val DRAFT_SAVE_INTERVAL_TICKS = 5
        private const val RECORD_ANNOUNCEMENT_MILLIS = 4500L
        private const val ELEVATION_HYSTERESIS_METERS = 2.0
        private const val AUTO_PAUSE_DELAY_MS = 5000L

        fun start(context: Context, type: ActivityType, intervals: IntervalConfig? = null) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
            intervals?.let { intent.putExtra(EXTRA_WORKOUT, it.toIntArray()) }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Continues an interrupted recording from its saved draft. */
        fun resumeDraft(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(ACTION_RESUME_DRAFT),
            )
        }

        fun finish(context: Context, title: String?, feel: String?) {
            context.startService(
                Intent(context, TrackingService::class.java)
                    .setAction(ACTION_FINISH)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_FEEL, feel)
            )
        }

        fun sendAction(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
