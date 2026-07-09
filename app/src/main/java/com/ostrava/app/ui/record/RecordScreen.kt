package com.ostrava.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.IntervalConfig
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.defaultActivityTitle
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.tracking.HeartRateMonitor
import com.ostrava.app.tracking.TrackingService
import com.ostrava.app.tracking.TrackingStatus
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.RouteMap
import com.ostrava.app.ui.components.StatTile
import com.ostrava.app.ui.components.icon
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(
    onActivitySaved: (Long) -> Unit,
    viewModel: RecordViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedId by viewModel.lastSavedActivityId.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(ActivityType.RUN) }
    var intervals by remember { mutableStateOf<IntervalConfig?>(null) }
    var countdown by remember { mutableIntStateOf(-1) }
    var showFinishDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showHrDialog by remember { mutableStateOf(false) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingStart by remember { mutableStateOf(false) }
    var initialFix by remember { mutableStateOf<TrackPoint?>(null) }

    // Centre the map on the athlete before recording starts; otherwise the map
    // sits at (0, 0) until the tracking service produces the first fix.
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission && initialFix == null) {
            try {
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            initialFix = TrackPoint(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                                timeMillis = location.time,
                                speedMps = 0f,
                                segment = 0,
                            )
                        }
                    }
            } catch (_: SecurityException) {
                // Permission revoked between check and call; map stays uncentred.
            }
        }
    }

    // Keep the display awake mid-workout.
    DisposableEffect(state.isActive, settings.keepScreenOn) {
        view.keepScreenOn = state.isActive && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 3-2-1 countdown, then start the tracking service.
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        } else if (countdown == 0) {
            countdown = -1
            TrackingService.start(context, selectedType, intervals)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocationPermission && pendingStart) {
            pendingStart = false
            countdown = 3
        }
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            showHrDialog = true
            HeartRateMonitor.startScan(context)
        }
    }

    fun requestBluetoothAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            showHrDialog = true
            HeartRateMonitor.startScan(context)
        } else {
            bluetoothLauncher.launch(permissions)
        }
    }

    fun requestPermissionsAndStart() {
        if (hasLocationPermission) {
            countdown = 3
        } else {
            pendingStart = true
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    LaunchedEffect(savedId) {
        val id = savedId ?: return@LaunchedEffect
        viewModel.consumeSavedActivityId()
        if (id > 0) onActivitySaved(id)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            RouteMap(
                points = state.points,
                currentFix = state.lastFix ?: initialFix,
                followLast = true,
                modifier = Modifier.fillMaxSize(),
            )
            state.intervalPhase?.let { phase ->
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "${phase.name} · ${formatDuration(phase.remainingSec * 1000L)} left" +
                            "  (${phase.index}/${phase.total})",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (countdown > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = countdown.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    GpsStatusRow(accuracy = state.gpsAccuracyMeters, recording = state.isActive)
                    HeartRateChip(
                        bpm = state.heartRateBpm,
                        onClick = { requestBluetoothAndScan() },
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (!state.isActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        ActivityType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(type.label) },
                                leadingIcon = {
                                    Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                            )
                        }
                        AssistChip(
                            onClick = { showIntervalDialog = true },
                            label = { Text(intervals?.let { "Intervals: ${it.summary()}" } ?: "Intervals") },
                            leadingIcon = {
                                Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                val activeType = if (state.isActive) state.type else selectedType

                // Primary stats: big enough to read at arm's length mid-run.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formatDuration(state.movingTimeMillis),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "TIME",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = formatDistance(state.distanceMeters, settings.imperialUnits),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "DISTANCE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (activeType.usesPace) {
                        StatTile("Pace", formatPace(state.avgSpeedMps, settings.imperialUnits))
                    } else {
                        StatTile("Speed", formatSpeed(state.currentSpeedMps.toDouble(), settings.imperialUnits))
                    }
                    StatTile("Climb", formatElevation(state.elevationGainMeters, settings.imperialUnits))
                    StatTile("Heart rate", state.heartRateBpm?.let { "$it bpm" } ?: "--")
                }
                Spacer(Modifier.height(16.dp))

                when (state.status) {
                    TrackingStatus.IDLE -> {
                        Button(
                            onClick = { requestPermissionsAndStart() },
                            enabled = countdown < 0,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text(
                                text = if (countdown > 0) "Starting…" else "Start ${selectedType.label}",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    TrackingStatus.TRACKING -> {
                        Button(
                            onClick = { TrackingService.sendAction(context, TrackingService.ACTION_PAUSE) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                            ),
                        ) {
                            Text("Pause", fontWeight = FontWeight.Bold)
                        }
                    }
                    TrackingStatus.PAUSED, TrackingStatus.AUTO_PAUSED -> {
                        if (state.status == TrackingStatus.AUTO_PAUSED) {
                            Text(
                                text = "Auto-paused — move to resume",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { TrackingService.sendAction(context, TrackingService.ACTION_RESUME) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                            ) {
                                Text("Resume", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { showFinishDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                            ) {
                                Text("Finish", fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { showDiscardDialog = true },
                                modifier = Modifier.height(52.dp),
                            ) {
                                Text("Discard")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFinishDialog) {
        FinishDialog(
            defaultTitle = defaultActivityTitle(state.type, System.currentTimeMillis()),
            onSave = { title, feel ->
                showFinishDialog = false
                TrackingService.finish(context, title, feel)
            },
            onDismiss = { showFinishDialog = false },
        )
    }
    if (showDiscardDialog) {
        DiscardDialog(
            onConfirm = {
                showDiscardDialog = false
                TrackingService.sendAction(context, TrackingService.ACTION_DISCARD)
            },
            onDismiss = { showDiscardDialog = false },
        )
    }
    if (showIntervalDialog) {
        IntervalDialog(
            initial = intervals,
            onApply = { config ->
                intervals = config
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false },
        )
    }
    if (showHrDialog) {
        HeartRateDialog(
            onDismiss = {
                HeartRateMonitor.stopScan()
                showHrDialog = false
            },
            onScan = { HeartRateMonitor.startScan(context) },
            onConnect = { address -> HeartRateMonitor.connect(context, address) },
        )
    }
}

@Composable
private fun HeartRateChip(bpm: Int?, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(bpm?.let { "$it bpm" } ?: "HR sensor") },
        leadingIcon = {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Heart rate",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
private fun GpsStatusRow(accuracy: Float?, recording: Boolean) {
    val (label, color) = when {
        accuracy == null && !recording -> "GPS: waiting" to MaterialTheme.colorScheme.onSurfaceVariant
        accuracy == null -> "GPS: searching…" to MaterialTheme.colorScheme.error
        accuracy <= 10f -> "GPS: strong (±${accuracy.toInt()} m)" to MaterialTheme.colorScheme.primary
        accuracy <= 25f -> "GPS: ok (±${accuracy.toInt()} m)" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "GPS: weak (±${accuracy.toInt()} m)" to MaterialTheme.colorScheme.error
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
}
