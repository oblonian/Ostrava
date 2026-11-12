package com.ostrava.app.ui.record

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.IntervalConfig
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.defaultActivityTitle
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.tracking.HeartRateMonitor
import com.ostrava.app.tracking.RecordingState
import com.ostrava.app.tracking.TrackingService
import com.ostrava.app.tracking.TrackingStatus
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.IconBadge
import com.ostrava.app.ui.components.RouteMap
import com.ostrava.app.ui.components.StatBlock
import com.ostrava.app.ui.components.color
import com.ostrava.app.ui.components.icon
import com.ostrava.app.ui.theme.HeroGradient
import kotlinx.coroutines.delay

/** Everything the record screen can ask for, so the layout itself stays free of side effects. */
class RecordActions(
    val onSelectType: (ActivityType) -> Unit = {},
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onFinish: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onOpenIntervals: () -> Unit = {},
    val onOpenHeartRate: () -> Unit = {},
    val onAllowBackground: () -> Unit = {},
    val onDismissBackgroundPrompt: () -> Unit = {},
)

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
    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }

    // Re-check after the athlete comes back from the system battery settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryUnrestricted = isBatteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    RecordContent(
        state = state,
        settings = settings,
        selectedType = selectedType,
        intervals = intervals,
        countdown = countdown,
        showBackgroundPrompt = !batteryUnrestricted && !settings.batteryPromptDismissed && !state.isActive,
        actions = RecordActions(
            onSelectType = { selectedType = it },
            onStart = { requestPermissionsAndStart() },
            onPause = { TrackingService.sendAction(context, TrackingService.ACTION_PAUSE) },
            onResume = { TrackingService.sendAction(context, TrackingService.ACTION_RESUME) },
            onFinish = { showFinishDialog = true },
            onDiscard = { showDiscardDialog = true },
            onOpenIntervals = { showIntervalDialog = true },
            onOpenHeartRate = { requestBluetoothAndScan() },
            onAllowBackground = { openBatterySettings(context) },
            onDismissBackgroundPrompt = viewModel::dismissBatteryPrompt,
        ),
        map = { modifier ->
            RouteMap(
                points = state.points,
                currentFix = state.lastFix ?: initialFix,
                followLast = true,
                modifier = modifier,
            )
        },
    )

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

/** True when Android won't put the app to sleep in the background. */
fun isBatteryUnrestricted(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/** Opens the system dialog (or settings page) that exempts the app from battery optimisation. */
fun openBatterySettings(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            // No settings screen for this on the device; nothing more we can do.
        }
    }
}

/**
 * The record screen's layout. [map] is passed in so the screen can be rendered
 * without a real map view (previews, screenshot tests).
 */
@Composable
fun RecordContent(
    state: RecordingState,
    settings: UserSettings,
    selectedType: ActivityType,
    intervals: IntervalConfig?,
    countdown: Int,
    showBackgroundPrompt: Boolean,
    actions: RecordActions,
    map: @Composable (Modifier) -> Unit,
) {
    val activeType = if (state.isActive) state.type else selectedType
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        content = {
            Box {
                map(Modifier.fillMaxSize())
                MapOverlays(state = state, countdown = countdown, onOpenHeartRate = actions.onOpenHeartRate)
            }
            ControlPanel(
                state = state,
                settings = settings,
                activeType = activeType,
                selectedType = selectedType,
                intervals = intervals,
                countdown = countdown,
                showBackgroundPrompt = showBackgroundPrompt,
                actions = actions,
            )
        },
    ) { measurables, constraints ->
        // The panel takes the height it needs; the map fills the rest and tucks
        // slightly under the panel's rounded corners.
        val panel = measurables[1].measure(constraints.copy(minHeight = 0))
        val overlap = 28.dp.roundToPx()
        val mapHeight = (constraints.maxHeight - panel.height + overlap).coerceAtLeast(0)
        val mapArea = measurables[0].measure(Constraints.fixed(constraints.maxWidth, mapHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            mapArea.place(0, 0)
            panel.place(0, constraints.maxHeight - panel.height)
        }
    }
}

@Composable
private fun MapOverlays(state: RecordingState, countdown: Int, onOpenHeartRate: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GpsPill(accuracy = state.gpsAccuracyMeters, recording = state.isActive)
            MapPill(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenHeartRate)
                    .semantics { contentDescription = "Heart rate sensor" },
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.heartRateBpm?.let { "$it bpm" } ?: "Connect",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        state.intervalPhase?.let { phase ->
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 60.dp),
            ) {
                Text(
                    text = "${phase.name} · ${formatDuration(phase.remainingSec * 1000L)} left",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (countdown > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = countdown.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        color = Color.White,
                    )
                    Text("Get ready", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MapPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 3.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun GpsPill(accuracy: Float?, recording: Boolean) {
    val good = Color(0xFF1F9D61)
    val (label, color) = when {
        accuracy == null && !recording -> "GPS ready when you are" to MaterialTheme.colorScheme.outline
        accuracy == null -> "Finding GPS…" to MaterialTheme.colorScheme.error
        accuracy <= 10f -> "GPS strong" to good
        accuracy <= 30f -> "GPS ok" to Color(0xFFE0A100)
        else -> "GPS weak" to MaterialTheme.colorScheme.error
    }
    MapPill {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ControlPanel(
    state: RecordingState,
    settings: UserSettings,
    activeType: ActivityType,
    selectedType: ActivityType,
    intervals: IntervalConfig?,
    countdown: Int,
    showBackgroundPrompt: Boolean,
    actions: RecordActions,
) {
    val imperial = settings.imperialUnits
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp)) {
            if (!state.isActive) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    ActivityType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { actions.onSelectType(type) },
                            label = { Text(type.label) },
                            leadingIcon = {
                                Icon(
                                    type.icon,
                                    contentDescription = null,
                                    tint = type.color,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                    FilterChip(
                        selected = intervals != null,
                        onClick = actions.onOpenIntervals,
                        label = { Text(intervals?.summary() ?: "Intervals") },
                        leadingIcon = {
                            Icon(Icons.Filled.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            // Primary stats: big enough to read at arm's length mid-run.
            Row(Modifier.fillMaxWidth()) {
                BigStat("Time", formatDuration(state.movingTimeMillis), Modifier.weight(1f))
                BigStat("Distance", formatDistance(state.distanceMeters, imperial), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                if (activeType.usesPace) {
                    StatBlock("Avg pace", formatPace(state.avgSpeedMps, imperial), Modifier.weight(1f))
                } else {
                    StatBlock("Speed", formatSpeed(state.currentSpeedMps.toDouble(), imperial), Modifier.weight(1f))
                }
                StatBlock("Climb", formatElevation(state.elevationGainMeters, imperial), Modifier.weight(1f))
                StatBlock("Heart rate", state.heartRateBpm?.let { "$it bpm" } ?: "--", Modifier.weight(1f))
            }

            if (showBackgroundPrompt) {
                Spacer(Modifier.height(16.dp))
                BackgroundPrompt(actions)
            }
            Spacer(Modifier.height(18.dp))

            when (state.status) {
                TrackingStatus.IDLE -> StartButton(
                    label = if (countdown > 0) "Starting…" else "Start ${selectedType.label.lowercase()}",
                    enabled = countdown < 0,
                    onClick = actions.onStart,
                )
                TrackingStatus.TRACKING -> Button(
                    onClick = actions.onPause,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pause", style = MaterialTheme.typography.titleMedium)
                }
                TrackingStatus.PAUSED, TrackingStatus.AUTO_PAUSED -> {
                    if (state.status == TrackingStatus.AUTO_PAUSED) {
                        Text(
                            text = "Auto-paused — start moving and the clock picks up again",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = actions.onResume,
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Resume", style = MaterialTheme.typography.titleMedium)
                        }
                        Button(
                            onClick = actions.onFinish,
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                        ) {
                            Icon(Icons.Filled.Flag, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Finish", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    TextButton(
                        onClick = actions.onDiscard,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Discard this recording", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.displaySmall, maxLines = 1)
    }
}

/** The main call to action: a full-width pill in the brand gradient. */
@Composable
private fun StartButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(50))
            .background(HeroGradient)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun BackgroundPrompt(actions: RecordActions) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Filled.BatteryAlert, tint = MaterialTheme.colorScheme.secondary, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        "Keep recording with the screen off",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Some phones stop background apps to save battery. Allow Ostrava to keep running so long activities aren't cut short.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = actions.onDismissBackgroundPrompt) { Text("Not now") }
                TextButton(onClick = actions.onAllowBackground) { Text("Allow", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
