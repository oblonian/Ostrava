package com.ostrava.app.ui.detail

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.EffortForActivity
import com.ostrava.app.data.db.RankedEffort
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.Feel
import com.ostrava.app.domain.METERS_PER_MILE
import com.ostrava.app.domain.Split
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.formatDateTime
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatPaceSeconds
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.domain.heartRateZones
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.CardLabel
import com.ostrava.app.ui.components.HorizontalBar
import com.ostrava.app.ui.components.LineChart
import com.ostrava.app.ui.components.OstravaCard
import com.ostrava.app.ui.components.RouteMap
import com.ostrava.app.ui.components.RouteThumbnail
import com.ostrava.app.ui.components.SportBadge
import com.ostrava.app.ui.components.StatBlock
import com.ostrava.app.ui.components.color
import com.ostrava.app.ui.components.icon

/** Colours for heart-rate zones 1-5, easy to hard. */
val ZoneColors = listOf(
    Color(0xFF90A4AE),
    Color(0xFF42A5F5),
    Color(0xFF66BB6A),
    Color(0xFFFFA726),
    Color(0xFFEF5350),
)

class DetailActions(
    val onShare: () -> Unit = {},
    val onExportGpx: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onMakeSegment: () -> Unit = {},
    val onTrim: () -> Unit = {},
)

@Composable
fun ActivityDetailScreen(
    onDeleted: () -> Unit,
    onShare: (Long) -> Unit,
    viewModel: ActivityDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val segmentEfforts by viewModel.segmentEfforts.collectAsStateWithLifecycle()
    val rankedEfforts by viewModel.rankedEfforts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSegmentDialog by remember { mutableStateOf(false) }
    var showTrimDialog by remember { mutableStateOf(false) }

    val activity = uiState.activity
    if (uiState.loading || activity == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.loading) CircularProgressIndicator() else Text("This activity is no longer here")
        }
        return
    }
    val type = ActivityType.fromName(activity.type)

    DetailContent(
        uiState = uiState,
        activity = activity,
        segmentEfforts = segmentEfforts,
        rankedEfforts = rankedEfforts,
        actions = DetailActions(
            onShare = { onShare(activity.id) },
            onExportGpx = { viewModel.exportGpx(context) },
            onRename = { showRenameDialog = true },
            onDelete = { showDeleteDialog = true },
            onMakeSegment = { showSegmentDialog = true },
            onTrim = { showTrimDialog = true },
        ),
        map = { modifier -> RouteMap(points = uiState.points, modifier = modifier) },
    )

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this activity?") },
            text = { Text("\"${activity.title}\" and its GPS track will be removed for good.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(onDeleted)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Keep it") } },
        )
    }

    if (showRenameDialog) {
        var title by remember { mutableStateOf(activity.title) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename activity") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (title.isNotBlank()) viewModel.rename(title.trim())
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } },
        )
    }

    if (showSegmentDialog) {
        var name by remember { mutableStateOf("${activity.title} segment") }
        RangePickerDialog(
            title = "Make a segment",
            description = "Pick a stretch of this route. Every ${type.label.lowercase()} over it, past and future, " +
                "gets timed and ranked against your best.",
            confirmLabel = "Create",
            allowWholeRoute = true,
            uiState = uiState,
            color = type.color,
            onDismiss = { showSegmentDialog = false },
            onConfirm = { from, to ->
                showSegmentDialog = false
                if (name.isNotBlank()) viewModel.createSegment(name.trim(), from, to)
            },
            extra = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Segment name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    if (showTrimDialog) {
        RangePickerDialog(
            title = "Trim activity",
            description = "Keep only the highlighted part, for when the clock kept running on the way home. " +
                "This can't be undone.",
            confirmLabel = "Trim",
            allowWholeRoute = false,
            uiState = uiState,
            color = type.color,
            onDismiss = { showTrimDialog = false },
            onConfirm = { from, to ->
                showTrimDialog = false
                viewModel.trim(from, to)
            },
        )
    }
}

/**
 * The detail screen's layout. [map] is passed in so the screen can be rendered
 * without a real map view (previews, screenshot tests).
 */
@Composable
fun DetailContent(
    uiState: DetailUiState,
    activity: ActivityEntity,
    segmentEfforts: List<EffortForActivity>,
    rankedEfforts: List<RankedEffort>,
    actions: DetailActions,
    map: @Composable (Modifier) -> Unit,
) {
    val imperial = uiState.settings.imperialUnits
    val type = ActivityType.fromName(activity.type)
    val hasTrack = uiState.points.size >= 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        if (hasTrack) {
            map(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
        } else {
            NoRouteBanner(type)
        }

        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SportBadge(type, size = 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(activity.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "${type.label} · ${formatDateTime(activity.startTime)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = actions.onRename) {
                    Icon(Icons.Filled.Edit, contentDescription = "Rename")
                }
                IconButton(onClick = actions.onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Button(onClick = actions.onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
                if (hasTrack) {
                    ActionChip(Icons.Filled.Flag, "Segment", actions.onMakeSegment)
                    ActionChip(Icons.Filled.ContentCut, "Trim", actions.onTrim)
                    ActionChip(Icons.Filled.IosShare, "GPX", actions.onExportGpx)
                }
            }

            val records = rankedEfforts.filter { it.effortRank == 1 }
            if (records.isNotEmpty()) {
                OstravaCard(containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "🏆",
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                text = if (records.size == 1) "Personal record" else "${records.size} personal records",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "Your fastest ${records.joinToString(", ") { it.label }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            StatGrid(activity = activity, type = type, imperial = imperial)

            if (uiState.heartRateZones.any { it > 0 }) {
                HeartRateZonesCard(zones = uiState.heartRateZones, maxHeartRate = uiState.settings.maxHeartRate)
            }

            if (segmentEfforts.isNotEmpty()) {
                OstravaCard {
                    CardLabel("Segments")
                    Spacer(Modifier.height(8.dp))
                    segmentEfforts.forEach { effort ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                effort.segmentName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (effort.durationMillis <= effort.bestMillis) {
                                Text(
                                    "🏆 Best  ",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(formatDuration(effort.durationMillis), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            if (rankedEfforts.isNotEmpty()) {
                BestEffortsCard(efforts = rankedEfforts, imperial = imperial, usesPace = type.usesPace)
            }

            if (uiState.splits.isNotEmpty()) {
                SplitsCard(splits = uiState.splits, imperial = imperial, usesPace = type.usesPace, color = type.color)
            }

            if (uiState.elevationProfile.size >= 2) {
                ChartCard(title = "Elevation") {
                    LineChart(
                        values = uiState.elevationProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        lineColor = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            if (uiState.speedProfile.size >= 2) {
                ChartCard(title = "Speed over time") {
                    LineChart(
                        values = uiState.speedProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        lineColor = type.color,
                    )
                }
            }

            if (uiState.heartRateProfile.size >= 2) {
                ChartCard(title = "Heart rate") {
                    LineChart(
                        values = uiState.heartRateProfile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        lineColor = ZoneColors[4],
                    )
                }
            }
        }
    }
}

/** Shown in place of the map for activities that were typed in by hand. */
@Composable
private fun NoRouteBanner(type: ActivityType) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Brush.linearGradient(listOf(type.color.copy(alpha = 0.85f), type.color.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(type.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(6.dp))
            Text("Added by hand — no route", style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

@Composable
private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun StatGrid(activity: ActivityEntity, type: ActivityType, imperial: Boolean) {
    OstravaCard {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatBlock("Distance", formatDistance(activity.distanceMeters, imperial), Modifier.weight(1f), large = true)
                StatBlock("Moving time", formatDuration(activity.movingTimeMillis), Modifier.weight(1f), large = true)
            }
            Row(Modifier.fillMaxWidth()) {
                if (type.usesPace) {
                    StatBlock("Avg pace", formatPace(activity.avgSpeedMps, imperial), Modifier.weight(1f))
                } else {
                    StatBlock("Avg speed", formatSpeed(activity.avgSpeedMps, imperial), Modifier.weight(1f))
                }
                StatBlock("Climb", formatElevation(activity.elevationGainMeters, imperial), Modifier.weight(1f))
                StatBlock("Calories", "${activity.calories} kcal", Modifier.weight(1f))
            }
            val feel = Feel.label(activity.feel)
            if (activity.maxSpeedMps > 0 || activity.avgHeartRate != null || feel != null) {
                Row(Modifier.fillMaxWidth()) {
                    StatBlock(
                        "Max speed",
                        if (activity.maxSpeedMps > 0) formatSpeed(activity.maxSpeedMps, imperial) else "--",
                        Modifier.weight(1f),
                    )
                    StatBlock(
                        "Avg / max HR",
                        activity.avgHeartRate?.let { avg -> "$avg / ${activity.maxHeartRate ?: avg}" } ?: "--",
                        Modifier.weight(1f),
                    )
                    StatBlock("Felt", feel ?: "--", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeartRateZonesCard(zones: List<Long>, maxHeartRate: Int) {
    val total = zones.sum().coerceAtLeast(1L)
    val definitions = heartRateZones(maxHeartRate)
    OstravaCard {
        CardLabel("Time in heart-rate zones")
        Spacer(Modifier.height(10.dp))
        zones.forEachIndexed { index, millis ->
            val zone = definitions.getOrNull(index)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(100.dp)) {
                    Text("Z${index + 1} ${zone?.name.orEmpty()}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    if (zone != null) {
                        Text(
                            "${zone.fromBpm}–${zone.toBpm} bpm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50)),
                ) {
                    val fraction = (millis.toFloat() / total).coerceIn(0f, 1f)
                    if (fraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                                .height(14.dp)
                                .background(ZoneColors[index.coerceAtMost(ZoneColors.lastIndex)], RoundedCornerShape(50)),
                        )
                    }
                }
                Text(
                    formatDuration(millis),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .width(64.dp)
                        .padding(start = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun BestEffortsCard(efforts: List<RankedEffort>, imperial: Boolean, usesPace: Boolean) {
    OstravaCard {
        CardLabel("Best efforts")
        Spacer(Modifier.height(8.dp))
        efforts.forEach { effort ->
            val speed = effort.distanceMeters / (effort.durationMillis / 1000.0)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (effort.effortRank) {
                        1 -> "🥇"
                        2 -> "🥈"
                        3 -> "🥉"
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .width(36.dp)
                        .clearAndSetSemantics { },
                )
                Column(Modifier.weight(1f)) {
                    Text(effort.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    val rankLabel = when (effort.effortRank) {
                        1 -> "Personal record"
                        2 -> "2nd fastest ever"
                        3 -> "3rd fastest ever"
                        else -> null
                    }
                    if (rankLabel != null) {
                        Text(rankLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatDuration(effort.durationMillis), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (usesPace) formatPace(speed, imperial) else formatSpeed(speed, imperial),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitsCard(splits: List<Split>, imperial: Boolean, usesPace: Boolean, color: Color) {
    val fastestPace = splits.map { it.paceSecondsPerKm }.filter { it > 0 }.minOrNull() ?: 1.0
    val unitLabel = if (imperial) "mi" else "km"
    val unitMeters = if (imperial) METERS_PER_MILE else 1000.0
    OstravaCard {
        CardLabel("Splits")
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    text = if (usesPace) {
                        formatPaceSeconds(split.durationMillis / 1000.0 / (split.distanceMeters / unitMeters), imperial)
                    } else {
                        formatSpeed(split.distanceMeters / (split.durationMillis / 1000.0), imperial)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(84.dp),
                )
                HorizontalBar(
                    // Faster splits get longer bars.
                    fraction = (fastestPace / split.paceSecondsPerKm.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    color = color,
                )
                Text(
                    text = formatElevation(split.elevationDelta, imperial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(52.dp)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    OstravaCard {
        CardLabel(title)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * Dialog for choosing a stretch of the route with a two-handled slider, with the
 * chosen part highlighted on a small drawing of the route.
 */
@Composable
private fun RangePickerDialog(
    title: String,
    description: String,
    confirmLabel: String,
    /** Whether confirming with the whole route selected makes sense (it doesn't for trimming). */
    allowWholeRoute: Boolean,
    uiState: DetailUiState,
    color: Color,
    onDismiss: () -> Unit,
    onConfirm: (fromIndex: Int, toIndex: Int) -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val points = uiState.points
    val lastIndex = points.lastIndex.coerceAtLeast(1)
    var range by remember { mutableStateOf(0f..1f) }
    val from = (range.start * lastIndex).toInt().coerceIn(0, lastIndex - 1)
    val to = (range.endInclusive * lastIndex).toInt().coerceIn(from + 1, lastIndex)
    val wholeRoute = from == 0 && to == lastIndex

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                extra?.invoke()
                RouteThumbnail(
                    points = points,
                    highlight = from..to,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                        .padding(8.dp),
                )
                RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..1f)
                SelectionSummary(points, uiState.cumulativeDistances, from, to, uiState.settings.imperialUnits)
            }
        },
        confirmButton = {
            TextButton(
                enabled = allowWholeRoute || !wholeRoute,
                onClick = { onConfirm(from, to) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SelectionSummary(
    points: List<TrackPoint>,
    cumulativeDistances: List<Float>,
    from: Int,
    to: Int,
    imperial: Boolean,
) {
    val distance = if (cumulativeDistances.size > to) {
        (cumulativeDistances[to] - cumulativeDistances[from]).toDouble()
    } else {
        0.0
    }
    val elapsed = if (points.size > to) points[to].timeMillis - points[from].timeMillis else 0L
    Row(Modifier.fillMaxWidth()) {
        StatBlock("Selected distance", formatDistance(distance, imperial), Modifier.weight(1f))
        StatBlock("Selected time", formatDuration(elapsed), Modifier.weight(1f))
    }
}
