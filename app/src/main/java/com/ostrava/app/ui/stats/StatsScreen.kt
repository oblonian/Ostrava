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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

        Card {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Last 12 weeks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                BarChart(
                    entries = uiState.weeklyDistances.mapIndexed { index, bucket ->
                        // Label only every third week to avoid clutter.
                        BarEntry(
                            label = if (index % 3 == 0) bucket.label else "",
                            value = bucket.distanceMeters.toFloat(),
                        )
                    },
                    highlightIndex = uiState.weeklyDistances.lastIndex,
                    modifier = Modifier.fillMaxWidth(),
                )
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
