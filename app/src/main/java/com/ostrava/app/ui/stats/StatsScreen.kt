package com.ostrava.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.ostrava.app.ui.components.CardLabel
import com.ostrava.app.ui.components.EmptyState
import com.ostrava.app.ui.components.LineChart
import com.ostrava.app.ui.components.OstravaCard
import com.ostrava.app.ui.components.Pill
import com.ostrava.app.ui.components.StatRow
import com.ostrava.app.ui.components.color
import com.ostrava.app.ui.components.icon

class StatsActions(
    val onTypeFilter: (ActivityType?) -> Unit = {},
    val onChartPeriod: (ChartPeriod) -> Unit = {},
    val onRecordsSport: (ActivityType) -> Unit = {},
    val onRecordsThisYear: (Boolean) -> Unit = {},
    val onDeleteSegment: (Long) -> Unit = {},
    val onActivityClick: (Long) -> Unit = {},
)

@Composable
fun StatsScreen(
    onActivityClick: (Long) -> Unit,
    viewModel: StatsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    StatsContent(
        uiState = uiState,
        actions = StatsActions(
            onTypeFilter = viewModel::setTypeFilter,
            onChartPeriod = viewModel::setChartPeriod,
            onRecordsSport = viewModel::setRecordsSport,
            onRecordsThisYear = viewModel::setRecordsThisYear,
            onDeleteSegment = viewModel::deleteSegment,
            onActivityClick = onActivityClick,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(uiState: StatsUiState, actions: StatsActions) {
    val imperial = uiState.settings.imperialUnits
    /** The distance whose record history is open in the bottom sheet. */
    var historyLabel by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Your training", style = MaterialTheme.typography.headlineMedium)

        if (!uiState.loading && uiState.totalCount == 0 && uiState.typeFilter == null) {
            EmptyState(
                emoji = "📈",
                title = "Trends appear as you go",
                body = "After your first activity you'll see weekly distance, streaks, training load and personal records here.",
            )
            return@Column
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = uiState.typeFilter == null,
                onClick = { actions.onTypeFilter(null) },
                label = { Text("All sports") },
            )
            ActivityType.entries.forEach { type ->
                FilterChip(
                    selected = uiState.typeFilter == type,
                    onClick = { actions.onTypeFilter(type) },
                    label = { Text(type.label) },
                    leadingIcon = {
                        Icon(type.icon, contentDescription = null, tint = type.color, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }

        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OstravaCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = 16.dp,
            ) {
                Text("🔥", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${uiState.currentStreakWeeks} week${if (uiState.currentStreakWeeks == 1) "" else "s"}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Current streak · best ${uiState.longestStreakWeeks}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val load = uiState.trainingLoad
            OstravaCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentPadding = 16.dp,
                containerColor = if (load?.rampWarning == true) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(if (load?.rampWarning == true) "⚠️" else "⏱️", style = MaterialTheme.typography.headlineMedium)
                Text("${load?.thisWeekMinutes ?: 0} min", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (load?.rampWarning == true) "Well above your ${load.fourWeekAvgMinutes} min average — ease in"
                    else "This week · usual ${load?.fourWeekAvgMinutes ?: 0} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OstravaCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardLabel("Distance")
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = uiState.chartPeriod == ChartPeriod.WEEKLY,
                        onClick = { actions.onChartPeriod(ChartPeriod.WEEKLY) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Weeks") }
                    SegmentedButton(
                        selected = uiState.chartPeriod == ChartPeriod.MONTHLY,
                        onClick = { actions.onChartPeriod(ChartPeriod.MONTHLY) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Months") }
                }
            }
            Spacer(Modifier.height(14.dp))
            BarChart(
                entries = uiState.chartBuckets.mapIndexed { index, bucket ->
                    BarEntry(
                        // Twelve labels would collide; show every third week or every other month.
                        label = if (index % (if (uiState.chartPeriod == ChartPeriod.MONTHLY) 2 else 3) == 0) {
                            bucket.label
                        } else {
                            ""
                        },
                        value = bucket.distanceMeters.toFloat(),
                    )
                },
                highlightIndex = uiState.chartBuckets.lastIndex,
                barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                highlightColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OstravaCard {
            CardLabel("All time")
            Spacer(Modifier.height(12.dp))
            StatRow(
                stats = listOf(
                    "Activities" to uiState.totalCount.toString(),
                    "Distance" to formatDistanceShort(uiState.totalDistanceMeters, imperial),
                    "Time" to formatDuration(uiState.totalMovingTimeMillis),
                    "Climb" to formatElevation(uiState.totalElevationMeters, imperial),
                ),
            )
        }

        if (uiState.records.sport != null) {
            PersonalRecordsCard(
                records = uiState.records,
                imperial = imperial,
                actions = actions,
                onOpenHistory = { historyLabel = it },
            )
        }

        if (uiState.segments.isNotEmpty()) {
            OstravaCard {
                CardLabel("Segments")
                Spacer(Modifier.height(6.dp))
                uiState.segments.forEach { segment ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(segment.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatDistanceShort(segment.distanceMeters, imperial)} · " +
                                    "${segment.attempts} attempt${if (segment.attempts != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            segment.bestMillis?.let { formatDuration(it) } ?: "--",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        IconButton(onClick = { actions.onDeleteSegment(segment.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete segment ${segment.name}",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    val label = historyLabel
    val history = label?.let { uiState.records.history[it] }
    if (label != null && !history.isNullOrEmpty()) {
        ModalBottomSheet(onDismissRequest = { historyLabel = null }) {
            RecordHistory(
                label = label,
                entries = history,
                onActivityClick = { id ->
                    historyLabel = null
                    actions.onActivityClick(id)
                },
            )
        }
    }
}

@Composable
private fun PersonalRecordsCard(
    records: RecordsUiState,
    imperial: Boolean,
    actions: StatsActions,
    onOpenHistory: (String) -> Unit,
) {
    val sport = records.sport ?: return
    OstravaCard(contentPadding = 0.dp) {
        Column(Modifier.padding(vertical = 18.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🏆  Personal records", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !records.thisYearOnly,
                    onClick = { actions.onRecordsThisYear(false) },
                    label = { Text("All-time") },
                )
                FilterChip(
                    selected = records.thisYearOnly,
                    onClick = { actions.onRecordsThisYear(true) },
                    label = { Text("This year") },
                )
                if (records.availableSports.size > 1) {
                    Spacer(Modifier.width(4.dp))
                    records.availableSports.forEach { type ->
                        FilterChip(
                            selected = type == sport,
                            onClick = { actions.onRecordsSport(type) },
                            label = { Text(type.label) },
                            leadingIcon = {
                                Icon(type.icon, contentDescription = null, tint = type.color, modifier = Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }

            if (records.efforts.isEmpty() && records.milestones.isEmpty()) {
                Text(
                    "No ${sport.label.lowercase()} records ${if (records.thisYearOnly) "this year " else ""}yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }

            if (records.efforts.isNotEmpty()) {
                RecordsSectionHeader("Best efforts · tap for history")
                records.efforts.forEach { effort ->
                    val speed = effort.distanceMeters / (effort.durationMillis / 1000.0)
                    RecordRow(
                        label = effort.label,
                        detail = "${effort.activityTitle} · ${formatDate(effort.achievedAt)}",
                        highlight = effort.improvementMillis
                            ?.takeIf { it > 0 }
                            ?.let { "▲ ${formatDuration(it)} faster than your previous best" },
                        value = formatDuration(effort.durationMillis),
                        valueDetail = if (sport.usesPace) formatPace(speed, imperial) else formatSpeed(speed, imperial),
                        isRecent = effort.isRecent,
                        showChevron = true,
                        onClick = { onOpenHistory(effort.label) },
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
                        showChevron = false,
                        onClick = milestone.activityId?.let { id -> { actions.onActivityClick(id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordsSectionHeader(title: String) {
    CardLabel(title, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun RecordRow(
    label: String,
    detail: String,
    highlight: String?,
    value: String,
    valueDetail: String?,
    isRecent: Boolean,
    showChevron: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 18.dp, end = if (showChevron) 8.dp else 18.dp, top = 8.dp, bottom = 8.dp),
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
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (highlight != null) {
                Text(highlight, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (valueDetail != null) {
                Text(
                    valueDetail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showChevron) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Bottom sheet showing how the athlete's time over one distance has changed. */
@Composable
private fun RecordHistory(label: String, entries: List<EffortHistoryEntry>, onActivityClick: (Long) -> Unit) {
    val best = entries.minOf { it.durationMillis }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        Text("$label history", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${entries.size} effort${if (entries.size == 1) "" else "s"} · best ${formatDuration(best)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entries.size >= 2) {
            Spacer(Modifier.height(16.dp))
            // Negated so that getting faster reads as the line going up.
            LineChart(
                values = entries.map { -it.durationMillis.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
            Text(
                "Oldest to newest · higher is faster",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        entries.sortedBy { it.durationMillis }.take(8).forEachIndexed { index, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onActivityClick(entry.activityId) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (index) {
                        0 -> "🥇"
                        1 -> "🥈"
                        2 -> "🥉"
                        else -> "${index + 1}."
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(40.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(entry.activityTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatDate(entry.achievedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.durationMillis == best) Pill("Best")
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(entry.durationMillis), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
