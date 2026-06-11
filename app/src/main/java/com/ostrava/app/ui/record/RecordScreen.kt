package com.ostrava.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.tracking.TrackingService
import com.ostrava.app.tracking.TrackingStatus
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.RouteMap
import com.ostrava.app.ui.components.StatTile
import com.ostrava.app.ui.components.icon

@Composable
fun RecordScreen(
    onActivitySaved: (Long) -> Unit,
    viewModel: RecordViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val savedId by viewModel.lastSavedActivityId.collectAsStateWithLifecycle()

    var selectedType by remember { mutableStateOf(ActivityType.RUN) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingStart by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocationPermission && pendingStart) {
            pendingStart = false
            TrackingService.start(context, selectedType)
        }
    }

    fun requestPermissionsAndStart() {
        if (hasLocationPermission) {
            TrackingService.start(context, selectedType)
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
        RouteMap(
            points = state.points,
            currentFix = state.lastFix,
            followLast = true,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(16.dp)) {
                GpsStatusRow(accuracy = state.gpsAccuracyMeters, recording = state.isActive)
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
                    }
                    Spacer(Modifier.height(16.dp))
                }

                val activeType = if (state.isActive) state.type else selectedType
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatTile("Time", formatDuration(state.movingTimeMillis), emphasized = true)
                    StatTile(
                        "Distance",
                        formatDistance(state.distanceMeters, settings.imperialUnits),
                        emphasized = true,
                    )
                    if (activeType.usesPace) {
                        StatTile(
                            "Pace",
                            formatPace(state.avgSpeedMps, settings.imperialUnits),
                            emphasized = true,
                        )
                    } else {
                        StatTile(
                            "Speed",
                            formatSpeed(state.currentSpeedMps.toDouble(), settings.imperialUnits),
                            emphasized = true,
                        )
                    }
                    StatTile(
                        "Climb",
                        formatElevation(state.elevationGainMeters, settings.imperialUnits),
                        emphasized = true,
                    )
                }
                Spacer(Modifier.height(16.dp))

                when (state.status) {
                    TrackingStatus.IDLE -> {
                        Button(
                            onClick = { requestPermissionsAndStart() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Text("Start ${selectedType.label}", fontWeight = FontWeight.Bold)
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
                                onClick = { TrackingService.sendAction(context, TrackingService.ACTION_FINISH) },
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
                                onClick = { TrackingService.sendAction(context, TrackingService.ACTION_DISCARD) },
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
}

@Composable
private fun GpsStatusRow(accuracy: Float?, recording: Boolean) {
    val (label, color) = when {
        accuracy == null && !recording -> "GPS: waiting for signal" to MaterialTheme.colorScheme.onSurfaceVariant
        accuracy == null -> "GPS: searching…" to MaterialTheme.colorScheme.error
        accuracy <= 10f -> "GPS: strong (±${accuracy.toInt()} m)" to MaterialTheme.colorScheme.primary
        accuracy <= 25f -> "GPS: ok (±${accuracy.toInt()} m)" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "GPS: weak (±${accuracy.toInt()} m)" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
