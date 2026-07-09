package com.ostrava.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.SegmentWithStats
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.domain.weeklyStreaks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ChartPeriod { WEEKLY, MONTHLY }

data class ChartBucket(
    val label: String,
    val distanceMeters: Double,
)

data class PersonalRecord(
    val label: String,
    val value: String,
    val activityTitle: String,
    val activityId: Long,
)

data class TrainingLoad(
    val thisWeekMinutes: Int,
    val fourWeekAvgMinutes: Int,
    val rampWarning: Boolean,
)

data class StatsUiState(
    val typeFilter: ActivityType? = null,
    val chartPeriod: ChartPeriod = ChartPeriod.WEEKLY,
    val totalCount: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val totalMovingTimeMillis: Long = 0L,
    val totalElevationMeters: Double = 0.0,
    val chartBuckets: List<ChartBucket> = emptyList(),
    val records: List<PersonalRecord> = emptyList(),
    val currentStreakWeeks: Int = 0,
    val longestStreakWeeks: Int = 0,
    val trainingLoad: TrainingLoad? = null,
    val segments: List<SegmentWithStats> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val loading: Boolean = true,
)

class StatsViewModel(
    private val repository: ActivityRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val typeFilter = MutableStateFlow<ActivityType?>(null)
    private val chartPeriod = MutableStateFlow(ChartPeriod.WEEKLY)

    val uiState: StateFlow<StatsUiState> = combine(
        repository.observeActivities(),
        settingsRepository.settings,
        typeFilter,
        chartPeriod,
        repository.observeSegmentStats(),
    ) { allActivities, settings, filter, period, segments ->
        val activities = if (filter == null) allActivities
        else allActivities.filter { it.type == filter.name }
        val (currentStreak, longestStreak) = computeStreaks(allActivities)
        StatsUiState(
            typeFilter = filter,
            chartPeriod = period,
            totalCount = activities.size,
            totalDistanceMeters = activities.sumOf { it.distanceMeters },
            totalMovingTimeMillis = activities.sumOf { it.movingTimeMillis },
            totalElevationMeters = activities.sumOf { it.elevationGainMeters },
            chartBuckets = when (period) {
                ChartPeriod.WEEKLY -> weeklyBuckets(activities)
                ChartPeriod.MONTHLY -> monthlyBuckets(activities)
            },
            records = computeRecords(activities, settings),
            currentStreakWeeks = currentStreak,
            longestStreakWeeks = longestStreak,
            trainingLoad = computeTrainingLoad(allActivities),
            segments = segments,
            settings = settings,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun setTypeFilter(type: ActivityType?) {
        typeFilter.value = type
    }

    fun setChartPeriod(period: ChartPeriod) {
        chartPeriod.value = period
    }

    fun deleteSegment(id: Long) {
        viewModelScope.launch { repository.deleteSegment(id) }
    }

    private fun weekIndexOf(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .with(DayOfWeek.MONDAY)
            .toEpochDay() / 7

    private fun computeStreaks(activities: List<ActivityEntity>): Pair<Int, Int> {
        val activeWeeks = activities.map { weekIndexOf(it.startTime) }.toSet()
        val currentWeek = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay() / 7
        return weeklyStreaks(activeWeeks, currentWeek)
    }

    private fun computeTrainingLoad(activities: List<ActivityEntity>): TrainingLoad? {
        if (activities.isEmpty()) return null
        val currentWeek = LocalDate.now().with(DayOfWeek.MONDAY).toEpochDay() / 7
        val minutesByWeek = activities.groupBy { weekIndexOf(it.startTime) }
            .mapValues { (_, list) -> (list.sumOf { it.movingTimeMillis } / 60_000L).toInt() }
        val thisWeek = minutesByWeek[currentWeek] ?: 0
        val previousWeeks = (1..4).map { minutesByWeek[currentWeek - it] ?: 0 }
        val avg = previousWeeks.sum() / 4
        return TrainingLoad(
            thisWeekMinutes = thisWeek,
            fourWeekAvgMinutes = avg,
            rampWarning = avg > 0 && thisWeek > avg * 1.5,
        )
    }

    private fun weeklyBuckets(activities: List<ActivityEntity>, weeks: Int = 12): List<ChartBucket> {
        val zone = ZoneId.systemDefault()
        val labelFormatter = DateTimeFormatter.ofPattern("d/M", Locale.US)
        val thisWeekStart = LocalDate.now().with(DayOfWeek.MONDAY)
        return (weeks - 1 downTo 0).map { weeksAgo ->
            val weekStart = thisWeekStart.minusWeeks(weeksAgo.toLong())
            val startMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            ChartBucket(
                label = labelFormatter.format(weekStart),
                distanceMeters = activities
                    .filter { it.startTime in startMillis until endMillis }
                    .sumOf { it.distanceMeters },
            )
        }
    }

    private fun monthlyBuckets(activities: List<ActivityEntity>, months: Int = 12): List<ChartBucket> {
        val zone = ZoneId.systemDefault()
        val labelFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
        val thisMonth = YearMonth.now()
        return (months - 1 downTo 0).map { monthsAgo ->
            val month = thisMonth.minusMonths(monthsAgo.toLong())
            val startMillis = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            ChartBucket(
                label = labelFormatter.format(month.atDay(1)),
                distanceMeters = activities
                    .filter { it.startTime in startMillis until endMillis }
                    .sumOf { it.distanceMeters },
            )
        }
    }

    private fun computeRecords(activities: List<ActivityEntity>, settings: UserSettings): List<PersonalRecord> {
        if (activities.isEmpty()) return emptyList()
        val records = mutableListOf<PersonalRecord>()

        activities.maxByOrNull { it.distanceMeters }?.let {
            records += PersonalRecord(
                label = "Longest distance",
                value = formatDistance(it.distanceMeters, settings.imperialUnits),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        activities.maxByOrNull { it.movingTimeMillis }?.let {
            records += PersonalRecord(
                label = "Longest duration",
                value = formatDuration(it.movingTimeMillis),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        activities.maxByOrNull { it.elevationGainMeters }?.let {
            records += PersonalRecord(
                label = "Biggest climb",
                value = formatElevation(it.elevationGainMeters, settings.imperialUnits),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        // Fastest average over a meaningful distance (>= 1 km) so short blips don't win.
        activities.filter { it.distanceMeters >= 1000 }.maxByOrNull { it.avgSpeedMps }?.let {
            records += PersonalRecord(
                label = "Fastest average",
                value = formatSpeed(it.avgSpeedMps, settings.imperialUnits),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        return records
    }
}
