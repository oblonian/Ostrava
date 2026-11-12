package com.ostrava.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.formatDate
import com.ostrava.app.domain.formatDistanceShort
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.BarChart
import com.ostrava.app.ui.components.BarEntry
import com.ostrava.app.ui.components.StatTile
import com.ostrava.app.ui.components.icon

@Composable
fun StatsScreen(
    onActivityClick: (Long) -> Unit,
    viewModel: StatsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val imperial = uiState.settings.imperialUnits

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Training",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        if (!uiState.loading && uiState.totalCount == 0 && uiState.typeFilter == null) {
            Card {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("📈", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Record your first activity to see weekly trends, streaks and personal records here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.typeFilter == null,
                onClick = { viewModel.setTypeFilter(null) },
                label = { Text("All") },
            )
            ActivityType.entries.forEach { type ->
                FilterChip(
                    selected = uiState.typeFilter == type,
                    onClick = { viewModel.setTypeFilter(type) },
                    label = { Text(type.label) },
                )
            }
        }

        // Streak + training load
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "🔥 ${uiState.currentStreakWeeks}-week streak",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Longest: ${uiState.longestStreakWeeks} weeks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            uiState.trainingLoad?.let { load ->
                Card(Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "${load.thisWeekMinutes} min this week",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (load.rampWarning) "⚠️ Well above your ${load.fourWeekAvgMinutes} min avg — ramp carefully"
                            else "4-week avg: ${load.fourWeekAvgMinutes} min",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (load.rampWarning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Distance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = uiState.chartPeriod == ChartPeriod.WEEKLY,
                            onClick = { viewModel.setChartPeriod(ChartPeriod.WEEKLY) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("12 wk") }
                        SegmentedButton(
                            selected = uiState.chartPeriod == ChartPeriod.MONTHLY,
                            onClick = { viewModel.setChartPeriod(ChartPeriod.MONTHLY) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("12 mo") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BarChart(
                    entries = uiState.chartBuckets.mapIndexed { index, bucket ->
                        BarEntry(
                            label = if (uiState.chartPeriod == ChartPeriod.MONTHLY || index % 3 == 0) {
                                bucket.label
                            } else {
                                ""
                            },
                            value = bucket.distanceMeters.toFloat(),
                        )
                    },
                    highlightIndex = uiState.chartBuckets.lastIndex,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("All time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile("Activities", uiState.totalCount.toString())
                    StatTile("Distance", formatDistanceShort(uiState.totalDistanceMeters, imperial))
                    StatTile("Time", formatDuration(uiState.totalMovingTimeMillis))
                    StatTile("Climb", formatElevation(uiState.totalElevationMeters, imperial))
                }
            }
        }

        if (uiState.segments.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Segments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    uiState.segments.forEach { segment ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(segment.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${formatDistanceShort(segment.distanceMeters, imperial)} · " +
                                        "${segment.attempts} attempt${if (segment.attempts != 1) "s" else ""}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                segment.bestMillis?.let { formatDuration(it) } ?: "--",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(onClick = { viewModel.deleteSegment(segment.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete segment",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (uiState.records.sport != null) {
            PersonalRecordsCard(
                records = uiState.records,
                imperial = imperial,
                onSportSelected = viewModel::setRecordsSport,
                onActivityClick = onActivityClick,
            )
        }
    }
}

@Composable
private fun PersonalRecordsCard(
    records: RecordsUiState,
    imperial: Boolean,
    onSportSelected: (ActivityType) -> Unit,
    onActivityClick: (Long) -> Unit,
) {
    val sport = records.sport ?: return
    Card {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                "Personal records",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (records.availableSports.size > 1) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    records.availableSports.forEach { type ->
                        FilterChip(
                            selected = type == sport,
                            onClick = { onSportSelected(type) },
                            label = { Text(type.label) },
                            leadingIcon = {
                                Icon(type.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            } else {
                Text(
                    sport.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (records.efforts.isNotEmpty()) {
                RecordsSectionHeader("Best efforts")
                records.efforts.forEach { effort ->
                    val speed = effort.distanceMeters / (effort.durationMillis / 1000.0)
                    RecordRow(
                        label = effort.label,
                        detail = "${effort.activityTitle} · ${formatDate(effort.achievedAt)}",
                        highlight = effort.improvementMillis
                            ?.takeIf { it > 0 }
                            ?.let { "▲ ${formatDuration(it)} faster than your previous best" },
                        value = formatDuration(effort.durationMillis),
                        valueDetail = if (sport.usesPace) formatPace(speed, imperial)
                        else formatSpeed(speed, imperial),
                        isRecent = effort.isRecent,
                        onClick = { onActivityClick(effort.activityId) },
                    )
                }
            }

            if (records.milestones.isNotEmpty()) {
                RecordsSectionHeader("Milestones")
                records.milestones.forEach { milestone ->
                    RecordRow(
                        label = milestone.label,
                        detail = milestone.detail,
                        highlight = null,
                        value = milestone.value,
                        valueDetail = null,
                        isRecent = milestone.isRecent,
                        onClick = milestone.activityId?.let { id -> { onActivityClick(id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecordRow(
    label: String,
    detail: String,
    highlight: String?,
    value: String,
    valueDetail: String?,
    isRecent: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (isRecent) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (highlight != null) {
                Text(
                    highlight,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (valueDetail != null) {
                Text(
                    valueDetail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
