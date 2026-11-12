package com.ostrava.app.ui.feed

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.Feel
import com.ostrava.app.domain.Insight
import com.ostrava.app.domain.InsightAction
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDistanceShort
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatRelativeDay
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.domain.greetingFor
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.EmojiBadge
import com.ostrava.app.ui.components.EmptyState
import com.ostrava.app.ui.components.OstravaCard
import com.ostrava.app.ui.components.Pill
import com.ostrava.app.ui.components.ProgressRing
import com.ostrava.app.ui.components.RouteThumbnail
import com.ostrava.app.ui.components.SectionTitle
import com.ostrava.app.ui.components.SportBadge
import com.ostrava.app.ui.components.StatBlock
import com.ostrava.app.ui.components.StatRow
import com.ostrava.app.ui.components.color
import com.ostrava.app.ui.theme.HeroGradient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FeedScreen(
    onActivityClick: (Long) -> Unit,
    onRecordClick: () -> Unit,
    onInsightAction: (InsightAction) -> Unit,
    viewModel: FeedViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showManualEntry by remember { mutableStateOf(false) }

    FeedContent(
        uiState = uiState,
        nowMillis = System.currentTimeMillis(),
        onActivityClick = onActivityClick,
        onRecordClick = onRecordClick,
        onInsightAction = onInsightAction,
        onDismissInsight = viewModel::dismissInsight,
        onNeedTrack = viewModel::requestTrack,
        onAddManual = { showManualEntry = true },
    )

    if (showManualEntry) {
        ManualEntryDialog(
            imperial = uiState.settings.imperialUnits,
            onDismiss = { showManualEntry = false },
            onSave = { entry ->
                showManualEntry = false
                viewModel.addManualActivity(
                    type = entry.type,
                    title = entry.title,
                    startTime = entry.startTime,
                    durationMillis = entry.durationMillis,
                    distanceMeters = entry.distanceMeters,
                    elevationGainMeters = entry.elevationGainMeters,
                )
            },
        )
    }
}

@Composable
fun FeedContent(
    uiState: FeedUiState,
    nowMillis: Long,
    onActivityClick: (Long) -> Unit,
    onRecordClick: () -> Unit,
    onInsightAction: (InsightAction) -> Unit,
    onDismissInsight: (Insight) -> Unit,
    onNeedTrack: (Long) -> Unit,
    onAddManual: () -> Unit,
) {
    val imperial = uiState.settings.imperialUnits
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Header(name = uiState.settings.userName, nowMillis = nowMillis, onAddManual = onAddManual)
        }
        item(key = "week") {
            WeekHeroCard(
                summary = uiState.weeklySummary,
                imperial = imperial,
                weeklyGoalKm = uiState.settings.weeklyGoalKm,
                streakWeeks = uiState.streakWeeks,
            )
        }
        items(uiState.insights, key = { "insight-${it.id}" }) { insight ->
            InsightCard(
                insight = insight,
                onAction = { onInsightAction(insight.action) },
                onDismiss = { onDismissInsight(insight) },
                modifier = Modifier.animateItem(),
            )
        }
        if (!uiState.loading && uiState.activities.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    emoji = "🏃",
                    title = "Your feed starts here",
                    body = "Record a run, ride, walk or hike and it will show up with its route, splits and records.",
                    action = {
                        Button(onClick = onRecordClick) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Record an activity")
                        }
                    },
                )
            }
        }
        if (uiState.activities.isNotEmpty()) {
            item(key = "recent") { SectionTitle("Recent activities") }
        }
        items(uiState.activities, key = { it.id }) { activity ->
            LaunchedEffect(activity.id) { onNeedTrack(activity.id) }
            ActivityCard(
                activity = activity,
                track = uiState.tracks[activity.id].orEmpty(),
                recordCount = uiState.recordCounts[activity.id] ?: 0,
                imperial = imperial,
                nowMillis = nowMillis,
                onClick = { onActivityClick(activity.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

private val headerDateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US)

@Composable
private fun Header(name: String, nowMillis: Long, onAddManual: () -> Unit) {
    val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = headerDateFormatter.format(now).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${greetingFor(now.hour)}, $name 👋",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalIconButton(onClick = onAddManual) {
            Icon(Icons.Filled.Add, contentDescription = "Add an activity manually")
        }
    }
}

@Composable
private fun WeekHeroCard(summary: WeeklySummary, imperial: Boolean, weeklyGoalKm: Float, streakWeeks: Int) {
    val onHero = Color.White
    val soft = Color.White.copy(alpha = 0.82f)
    val hasGoal = weeklyGoalKm > 0f
    val progress = if (hasGoal) (summary.distanceMeters / 1000.0 / weeklyGoalKm).toFloat().coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(HeroGradient)
            .padding(22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("THIS WEEK", style = MaterialTheme.typography.labelSmall, color = soft)
                    Text(
                        text = formatDistanceShort(summary.distanceMeters, imperial),
                        style = MaterialTheme.typography.displaySmall,
                        color = onHero,
                    )
                    Text(
                        text = when {
                            !hasGoal -> "No weekly goal set"
                            progress >= 1f -> "Goal of ${formatDistanceShort(weeklyGoalKm * 1000.0, imperial)} reached 🎉"
                            else -> "of your ${formatDistanceShort(weeklyGoalKm * 1000.0, imperial)} goal"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = soft,
                    )
                }
                if (hasGoal) {
                    ProgressRing(
                        progress = progress,
                        size = 88.dp,
                        strokeWidth = 9.dp,
                        color = onHero,
                        trackColor = Color.White.copy(alpha = 0.28f),
                    ) {
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = onHero,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                StatBlock("Time", formatDuration(summary.movingTimeMillis), Modifier.weight(1f), onHero, soft)
                StatBlock("Activities", summary.activityCount.toString(), Modifier.weight(1f), onHero, soft)
                StatBlock("Climb", formatElevation(summary.elevationGainMeters, imperial), Modifier.weight(1f), onHero, soft)
            }
            if (streakWeeks >= 2) {
                Spacer(Modifier.height(14.dp))
                Pill(
                    text = "🔥 $streakWeeks-week streak",
                    containerColor = Color.White.copy(alpha = 0.22f),
                    contentColor = onHero,
                )
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight, onAction: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    OstravaCard(
        modifier = modifier.animateContentSize(),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentPadding = 16.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            EmojiBadge(insight.emoji, background = MaterialTheme.colorScheme.surfaceContainerLowest)
            Column(Modifier.weight(1f)) {
                Text(
                    insight.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    insight.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Got it") }
            insight.action.label?.let { label ->
                TextButton(onClick = onAction) { Text(label, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: ActivityEntity,
    track: List<TrackPoint>,
    recordCount: Int,
    imperial: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = ActivityType.fromName(activity.type)
    OstravaCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SportBadge(type)
            Column(Modifier.weight(1f)) {
                Text(
                    text = activity.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${type.label} · ${formatRelativeDay(activity.startTime, nowMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (track.size >= 2) {
                Box(
                    Modifier
                        .size(60.dp)
                        .background(type.color.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(4.dp),
                ) {
                    RouteThumbnail(points = track, modifier = Modifier.fillMaxSize(), color = type.color)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        StatRow(
            stats = listOf(
                "Distance" to formatDistance(activity.distanceMeters, imperial),
                "Time" to formatDuration(activity.movingTimeMillis),
                if (type.usesPace) "Pace" to formatPace(activity.avgSpeedMps, imperial)
                else "Speed" to formatSpeed(activity.avgSpeedMps, imperial),
            ),
        )
        val feel = Feel.label(activity.feel)
        if (recordCount > 0 || feel != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recordCount > 0) {
                    Pill(if (recordCount == 1) "🏆 Personal record" else "🏆 $recordCount personal records")
                }
                if (feel != null) {
                    Pill(
                        text = feel,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
