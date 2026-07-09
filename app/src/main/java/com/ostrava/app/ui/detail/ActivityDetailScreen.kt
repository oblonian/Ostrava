package com.ostrava.app.ui.detail

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.BestEffort
import com.ostrava.app.domain.Feel
import com.ostrava.app.domain.Split
import com.ostrava.app.domain.formatDateTime
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatPaceSeconds
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.HorizontalBar
import com.ostrava.app.ui.components.LineChart
import com.ostrava.app.ui.components.RouteMap
import com.ostrava.app.ui.components.StatTile

@Composable
fun ActivityDetailScreen(
    onDeleted: () -> Unit,
    viewModel: ActivityDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val segmentEfforts by viewModel.segmentEfforts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSegmentDialog by remember { mutableStateOf(false) }

    val activity = uiState.activity
    if (uiState.loading || activity == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (uiState.loading) CircularProgressIndicator() else Text("Activity not found")
        }
        return
    }
    val imperial = uiState.settings.imperialUnits
    val type = ActivityType.fromName(activity.type)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        RouteMap(
            points = uiState.points,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = activity.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${type.label} · ${formatDateTime(activity.startTime)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.shareImage(context) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share image")
                }
                OutlinedButton(onClick = { viewModel.exportGpx(context) }) {
                    Text("Export GPX")
                }
                OutlinedButton(onClick = { showSegmentDialog = true }) {
                    Text("Make segment")
                }
            }

            StatGrid(activity = activity, type = type, imperial = imperial)

            if (segmentEfforts.isNotEmpty()) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Segments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        segmentEfforts.forEach { effort ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(effort.segmentName, style = MaterialTheme.typography.bodyLarge)
                                Row {
                                    Text(
                                        formatDuration(effort.durationMillis),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (effort.durationMillis <= effort.bestMillis) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "PR 🏆",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.bestEfforts.isNotEmpty()) {
                BestEffortsCard(efforts = uiState.bestEfforts, imperial = imperial)
            }

            if (uiState.splits.isNotEmpty()) {
                SplitsCard(splits = uiState.splits, imperial = imperial, usesPace = type.usesPace)
            }

            if (uiState.elevationProfile.size >= 2) {
                ChartCard(title = "Elevation") {
                    LineChart(
                        values = uiState.elevationProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        lineColor = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            if (uiState.speedProfile.size >= 2) {
                ChartCard(title = "Speed") {
                    LineChart(
                        values = uiState.speedProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete activity?") },
            text = { Text("\"${activity.title}\" and its GPS track will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showSegmentDialog) {
        var segmentName by remember { mutableStateOf("${activity.title} segment") }
        AlertDialog(
            onDismissRequest = { showSegmentDialog = false },
            title = { Text("Create segment") },
            text = {
                Column {
                    Text(
                        "Saves this route as a segment. Future ${type.label.lowercase()}s covering it " +
                            "will be timed automatically and ranked against your best.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = segmentName,
                        onValueChange = { segmentName = it },
                        label = { Text("Segment name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSegmentDialog = false
                    if (segmentName.isNotBlank()) viewModel.createSegment(segmentName.trim())
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showSegmentDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRenameDialog) {
        var title by remember { mutableStateOf(activity.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename activity") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (title.isNotBlank()) viewModel.rename(title.trim())
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatGrid(activity: ActivityEntity, type: ActivityType, imperial: Boolean) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Distance", formatDistance(activity.distanceMeters, imperial))
                StatTile("Moving time", formatDuration(activity.movingTimeMillis))
                if (type.usesPace) {
                    StatTile("Avg pace", formatPace(activity.avgSpeedMps, imperial))
                } else {
                    StatTile("Avg speed", formatSpeed(activity.avgSpeedMps, imperial))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("Climb", formatElevation(activity.elevationGainMeters, imperial))
                StatTile("Max speed", formatSpeed(activity.maxSpeedMps, imperial))
                StatTile("Calories", "${activity.calories} kcal")
            }
            if (activity.avgHeartRate != null || activity.feel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("Avg HR", activity.avgHeartRate?.let { "$it bpm" } ?: "--")
                    StatTile("Max HR", activity.maxHeartRate?.let { "$it bpm" } ?: "--")
                    StatTile("Felt", Feel.label(activity.feel) ?: "--")
                }
            }
        }
    }
}

@Composable
private fun BestEffortsCard(efforts: List<BestEffort>, imperial: Boolean) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Best efforts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            efforts.forEach { effort ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(effort.label, style = MaterialTheme.typography.bodyLarge)
                    Row {
                        Text(
                            formatDuration(effort.durationMillis),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            formatPace(effort.distanceMeters / (effort.durationMillis / 1000.0), imperial),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitsCard(splits: List<Split>, imperial: Boolean, usesPace: Boolean) {
    val slowestPace = splits.maxOf { it.paceSecondsPerKm }.takeIf { it > 0 } ?: 1.0
    val unitLabel = if (imperial) "mi" else "km"
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("Splits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            splits.forEach { split ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${split.index} $unitLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        text = if (usesPace) {
                            // Pace per split unit: duration over actual split distance.
                            formatPaceSeconds(
                                split.durationMillis / 1000.0 /
                                    (split.distanceMeters / (if (imperial) com.ostrava.app.domain.METERS_PER_MILE else 1000.0)),
                                imperial,
                            )
                        } else {
                            formatSpeed(split.distanceMeters / (split.durationMillis / 1000.0), imperial)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(84.dp),
                    )
                    HorizontalBar(
                        // Faster splits get longer bars.
                        fraction = (slowestPace / split.paceSecondsPerKm.coerceAtLeast(1.0)).toFloat()
                            .coerceIn(0f, 1f),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatElevation(split.elevationDelta, imperial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
