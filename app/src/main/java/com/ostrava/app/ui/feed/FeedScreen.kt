package com.ostrava.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.Feel
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.formatDateTime
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDistanceShort
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.RouteThumbnail
import com.ostrava.app.ui.components.StatTile
import com.ostrava.app.ui.components.icon

@Composable
fun FeedScreen(
    onActivityClick: (Long) -> Unit,
    onRecordClick: () -> Unit,
    viewModel: FeedViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Hi, ${uiState.settings.userName}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            WeeklySummaryCard(
                summary = uiState.weeklySummary,
                imperial = uiState.settings.imperialUnits,
                weeklyGoalKm = uiState.settings.weeklyGoalKm,
            )
        }
        if (!uiState.loading && uiState.activities.isEmpty()) {
            item {
                EmptyFeed(onRecordClick)
            }
        }
        items(uiState.activities, key = { it.id }) { activity ->
            var track by remember(activity.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }
            LaunchedEffect(activity.id) {
                track = viewModel.trackFor(activity.id)
            }
            ActivityCard(
                activity = activity,
                track = track,
                imperial = uiState.settings.imperialUnits,
                onClick = { onActivityClick(activity.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@Composable
private fun WeeklySummaryCard(summary: WeeklySummary, imperial: Boolean, weeklyGoalKm: Float) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "This week",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("Distance", formatDistanceShort(summary.distanceMeters, imperial))
                StatTile("Time", formatDuration(summary.movingTimeMillis))
                StatTile("Activities", summary.activityCount.toString())
                StatTile("Climb", formatElevation(summary.elevationGainMeters, imperial))
            }
            if (weeklyGoalKm > 0f) {
                Spacer(Modifier.height(12.dp))
                val progress = (summary.distanceMeters / 1000.0 / weeklyGoalKm).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Goal: ${formatDistanceShort(summary.distanceMeters, imperial)} of " +
                        formatDistanceShort(weeklyGoalKm * 1000.0, imperial),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityEntity,
    track: List<TrackPoint>,
    imperial: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = ActivityType.fromName(activity.type)
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = type.icon,
                    contentDescription = type.label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = activity.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Feel.label(activity.feel)?.let { feelLabel ->
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = feelLabel.substringBefore(' '),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Text(
                        text = formatDateTime(activity.startTime),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (track.size >= 2) {
                    Box(Modifier.size(72.dp)) {
                        RouteThumbnail(points = track, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatTile("Distance", formatDistance(activity.distanceMeters, imperial))
                StatTile("Time", formatDuration(activity.movingTimeMillis))
                if (type.usesPace) {
                    StatTile("Pace", formatPace(activity.avgSpeedMps, imperial))
                } else {
                    StatTile("Speed", formatSpeed(activity.avgSpeedMps, imperial))
                }
            }
        }
    }
}

@Composable
private fun EmptyFeed(onRecordClick: () -> Unit) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No activities yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Head outside and record your first run, ride, walk or hike.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRecordClick) {
                Text("Record an activity")
            }
        }
    }
}
