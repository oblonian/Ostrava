package com.ostrava.app.ui.stats

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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.formatDistanceShort
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.BarChart
import com.ostrava.app.ui.components.BarEntry
import com.ostrava.app.ui.components.StatTile

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
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

        if (uiState.records.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Personal records",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    uiState.records.forEach { record ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(record.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    record.activityTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                record.value,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
