package com.ostrava.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class WeekBucket(
    val label: String,
    val distanceMeters: Double,
)

data class PersonalRecord(
    val label: String,
    val value: String,
    val activityTitle: String,
    val activityId: Long,
)

data class StatsUiState(
    val typeFilter: ActivityType? = null,
    val totalCount: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val totalMovingTimeMillis: Long = 0L,
    val totalElevationMeters: Double = 0.0,
    val weeklyDistances: List<WeekBucket> = emptyList(),
    val records: List<PersonalRecord> = emptyList(),
    val settings: UserSettings = UserSettings(),
)

class StatsViewModel(
    repository: ActivityRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val typeFilter = MutableStateFlow<ActivityType?>(null)

    val uiState: StateFlow<StatsUiState> = combine(
        repository.observeActivities(),
        settingsRepository.settings,
        typeFilter,
    ) { allActivities, settings, filter ->
        val activities = if (filter == null) allActivities
        else allActivities.filter { it.type == filter.name }
        StatsUiState(
            typeFilter = filter,
            totalCount = activities.size,
            totalDistanceMeters = activities.sumOf { it.distanceMeters },
            totalMovingTimeMillis = activities.sumOf { it.movingTimeMillis },
            totalElevationMeters = activities.sumOf { it.elevationGainMeters },
            weeklyDistances = computeWeeklyBuckets(activities),
            records = computeRecords(activities, settings),
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    fun setTypeFilter(type: ActivityType?) {
        typeFilter.value = type
    }

    private fun computeWeeklyBuckets(activities: List<ActivityEntity>, weeks: Int = 12): List<WeekBucket> {
        val zone = ZoneId.systemDefault()
        val labelFormatter = DateTimeFormatter.ofPattern("d/M", Locale.US)
        val thisWeekStart = LocalDate.now().with(DayOfWeek.MONDAY)
        return (weeks - 1 downTo 0).map { weeksAgo ->
            val weekStart = thisWeekStart.minusWeeks(weeksAgo.toLong())
            val startMillis = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            WeekBucket(
                label = labelFormatter.format(weekStart),
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
                value = com.ostrava.app.domain.formatDistance(it.distanceMeters, settings.imperialUnits),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        activities.maxByOrNull { it.movingTimeMillis }?.let {
            records += PersonalRecord(
                label = "Longest duration",
                value = com.ostrava.app.domain.formatDuration(it.movingTimeMillis),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        activities.maxByOrNull { it.elevationGainMeters }?.let {
            records += PersonalRecord(
                label = "Biggest climb",
                value = com.ostrava.app.domain.formatElevation(it.elevationGainMeters, settings.imperialUnits),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        // Fastest average over a meaningful distance (>= 1 km) so short blips don't win.
        activities.filter { it.distanceMeters >= 1000 }.maxByOrNull { it.avgSpeedMps }?.let {
            records += PersonalRecord(
                label = "Fastest average",
                value = com.ostrava.app.domain.formatSpeed(it.avgSpeedMps, settings.imperialUnits),
                activityTitle = it.title,
                activityId = it.id,
            )
        }
        return records
    }
}
