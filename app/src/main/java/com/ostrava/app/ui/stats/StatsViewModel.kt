package com.ostrava.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.EffortWithActivity
import com.ostrava.app.data.db.SegmentWithStats
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.formatDate
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
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

/** All-time fastest effort over a benchmark distance for one sport. */
data class EffortRecord(
    val label: String,
    val distanceMeters: Double,
    val durationMillis: Long,
    val activityId: Long,
    val activityTitle: String,
    val achievedAt: Long,
    /** How much faster than the best effort before it; null for a first-ever effort. */
    val improvementMillis: Long?,
    val attempts: Int,
    val isRecent: Boolean,
)

/** A non-timed record: longest, highest, biggest week and so on. */
data class MilestoneRecord(
    val label: String,
    val value: String,
    val detail: String,
    val activityId: Long?,
    val isRecent: Boolean,
)

/** One effort over a benchmark distance, for the record-history view. */
data class EffortHistoryEntry(
    val activityId: Long,
    val activityTitle: String,
    val durationMillis: Long,
    val achievedAt: Long,
)

data class RecordsUiState(
    val sport: ActivityType? = null,
    val availableSports: List<ActivityType> = emptyList(),
    val efforts: List<EffortRecord> = emptyList(),
    val milestones: List<MilestoneRecord> = emptyList(),
    /** True when only this calendar year's records are shown. */
    val thisYearOnly: Boolean = false,
    /** Every effort per distance, oldest first, to show how a record progressed. */
    val history: Map<String, List<EffortHistoryEntry>> = emptyMap(),
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
    val records: RecordsUiState = RecordsUiState(),
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
    private val recordsSport = MutableStateFlow<ActivityType?>(null)
    private val recordsThisYear = MutableStateFlow(false)

    private class Base(val state: StatsUiState, val allActivities: List<ActivityEntity>)

    private val base = combine(
        repository.observeActivities(),
        settingsRepository.settings,
        typeFilter,
        chartPeriod,
        repository.observeSegmentStats(),
    ) { allActivities, settings, filter, period, segments ->
        val activities = if (filter == null) allActivities
        else allActivities.filter { it.type == filter.name }
        val (currentStreak, longestStreak) = computeStreaks(allActivities)
        Base(
            state = StatsUiState(
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
                currentStreakWeeks = currentStreak,
                longestStreakWeeks = longestStreak,
                trainingLoad = computeTrainingLoad(allActivities),
                segments = segments,
                settings = settings,
                loading = false,
            ),
            allActivities = allActivities,
        )
    }

    val uiState: StateFlow<StatsUiState> = combine(
        base,
        repository.observeAllBestEfforts(),
        recordsSport,
        recordsThisYear,
    ) { base, efforts, chosenSport, thisYearOnly ->
        base.state.copy(
            records = buildRecords(
                allActivities = base.allActivities,
                efforts = efforts,
                chosenSport = chosenSport ?: base.state.typeFilter,
                imperial = base.state.settings.imperialUnits,
                thisYearOnly = thisYearOnly,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun setTypeFilter(type: ActivityType?) {
        typeFilter.value = type
        // The records card follows the page filter until a sport is picked on the card itself.
        recordsSport.value = null
    }

    fun setRecordsSport(type: ActivityType) {
        recordsSport.value = type
    }

    fun setRecordsThisYear(thisYearOnly: Boolean) {
        recordsThisYear.value = thisYearOnly
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

    private fun buildRecords(
        allActivities: List<ActivityEntity>,
        efforts: List<EffortWithActivity>,
        chosenSport: ActivityType?,
        imperial: Boolean,
        thisYearOnly: Boolean,
    ): RecordsUiState {
        if (allActivities.isEmpty()) return RecordsUiState(thisYearOnly = thisYearOnly)
        val countBySport = allActivities.groupingBy { it.type }.eachCount()
        val availableSports = ActivityType.entries.filter { (countBySport[it.name] ?: 0) > 0 }
        // Default to the sport the athlete does most.
        val sport = chosenSport?.takeIf { it in availableSports }
            ?: availableSports.maxByOrNull { countBySport[it.name] ?: 0 }
            ?: return RecordsUiState(thisYearOnly = thisYearOnly)

        val now = System.currentTimeMillis()
        fun recent(time: Long) = now - time < RECENT_WINDOW_MILLIS
        val yearStart = LocalDate.now().withDayOfYear(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun inScope(time: Long) = !thisYearOnly || time >= yearStart

        val sportEfforts = efforts.filter { it.activityType == sport.name && inScope(it.achievedAt) }
        val history = sportEfforts
            .groupBy { it.label }
            .mapValues { (_, sameDistance) ->
                sameDistance.sortedBy { it.achievedAt }.map {
                    EffortHistoryEntry(it.activityId, it.activityTitle, it.durationMillis, it.achievedAt)
                }
            }

        val effortRecords = sportEfforts
            .groupBy { it.label }
            .values
            .mapNotNull { sameDistance ->
                val best = sameDistance.minByOrNull { it.durationMillis } ?: return@mapNotNull null
                val previousBest = sameDistance
                    .filter { it.achievedAt < best.achievedAt }
                    .minOfOrNull { it.durationMillis }
                EffortRecord(
                    label = best.label,
                    distanceMeters = best.distanceMeters,
                    durationMillis = best.durationMillis,
                    activityId = best.activityId,
                    activityTitle = best.activityTitle,
                    achievedAt = best.achievedAt,
                    improvementMillis = previousBest?.let { it - best.durationMillis },
                    attempts = sameDistance.size,
                    isRecent = recent(best.achievedAt),
                )
            }
            .sortedBy { it.distanceMeters }

        val activities = allActivities.filter { it.type == sport.name && inScope(it.startTime) }
        val milestones = buildList {
            fun fromActivity(label: String, activity: ActivityEntity?, value: (ActivityEntity) -> String) {
                if (activity == null) return
                add(
                    MilestoneRecord(
                        label = label,
                        value = value(activity),
                        detail = "${activity.title} · ${formatDate(activity.startTime)}",
                        activityId = activity.id,
                        isRecent = recent(activity.startTime),
                    )
                )
            }
            fromActivity("Longest distance", activities.maxByOrNull { it.distanceMeters }) {
                formatDistance(it.distanceMeters, imperial)
            }
            fromActivity("Longest duration", activities.maxByOrNull { it.movingTimeMillis }) {
                formatDuration(it.movingTimeMillis)
            }
            fromActivity(
                "Most climbing",
                activities.filter { it.elevationGainMeters >= 1.0 }.maxByOrNull { it.elevationGainMeters },
            ) { formatElevation(it.elevationGainMeters, imperial) }
            // Fastest average over a meaningful distance (>= 1 km) so short blips don't win.
            fromActivity(
                if (sport.usesPace) "Fastest average pace" else "Fastest average speed",
                activities.filter { it.distanceMeters >= 1000 }.maxByOrNull { it.avgSpeedMps },
            ) {
                if (sport.usesPace) formatPace(it.avgSpeedMps, imperial)
                else formatSpeed(it.avgSpeedMps, imperial)
            }
            fromActivity(
                "Top speed",
                activities.filter { it.maxSpeedMps > 0 }.maxByOrNull { it.maxSpeedMps },
            ) { formatSpeed(it.maxSpeedMps, imperial) }

            val zone = ZoneId.systemDefault()
            fun dateOf(activity: ActivityEntity): LocalDate =
                Instant.ofEpochMilli(activity.startTime).atZone(zone).toLocalDate()

            activities.groupBy { dateOf(it).with(DayOfWeek.MONDAY) }
                .maxByOrNull { (_, week) -> week.sumOf { it.distanceMeters } }
                ?.let { (weekStart, week) ->
                    add(
                        MilestoneRecord(
                            label = "Biggest week",
                            value = formatDistance(week.sumOf { it.distanceMeters }, imperial),
                            detail = "Week of ${weekStart.format(WEEK_FORMAT)} · " +
                                "${week.size} activit${if (week.size == 1) "y" else "ies"}",
                            activityId = null,
                            isRecent = weekStart == LocalDate.now().with(DayOfWeek.MONDAY),
                        )
                    )
                }
            activities.groupBy { YearMonth.from(dateOf(it)) }
                .maxByOrNull { (_, month) -> month.sumOf { it.distanceMeters } }
                ?.let { (yearMonth, month) ->
                    add(
                        MilestoneRecord(
                            label = "Biggest month",
                            value = formatDistance(month.sumOf { it.distanceMeters }, imperial),
                            detail = "${yearMonth.format(MONTH_FORMAT)} · " +
                                "${month.size} activit${if (month.size == 1) "y" else "ies"}",
                            activityId = null,
                            isRecent = yearMonth == YearMonth.now(),
                        )
                    )
                }
        }

        return RecordsUiState(
            sport = sport,
            availableSports = availableSports,
            efforts = effortRecords,
            milestones = milestones,
            thisYearOnly = thisYearOnly,
            history = history,
        )
    }

    private companion object {
        /** Records set within this window are flagged as new. */
        const val RECENT_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        val WEEK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    }
}
