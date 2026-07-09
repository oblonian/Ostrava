package com.ostrava.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

data class WeeklySummary(
    val distanceMeters: Double = 0.0,
    val movingTimeMillis: Long = 0L,
    val activityCount: Int = 0,
    val elevationGainMeters: Double = 0.0,
)

data class FeedUiState(
    val activities: List<ActivityEntity> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val weeklySummary: WeeklySummary = WeeklySummary(),
    val loading: Boolean = true,
)

class FeedViewModel(
    private val repository: ActivityRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val thumbnailCache = mutableMapOf<Long, List<TrackPoint>>()

    /** Downsampled track for the feed-card thumbnail; cached per activity. */
    suspend fun trackFor(activityId: Long): List<TrackPoint> =
        thumbnailCache.getOrPut(activityId) {
            val points = repository.getTrackPoints(activityId)
            if (points.size <= 80) points
            else {
                val step = points.size.toFloat() / 80
                (0 until 80).map { points[(it * step).toInt()] }
            }
        }

    val uiState: StateFlow<FeedUiState> = combine(
        repository.observeActivities(),
        settingsRepository.settings,
    ) { activities, settings ->
        FeedUiState(
            activities = activities,
            settings = settings,
            weeklySummary = computeWeeklySummary(activities),
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FeedUiState())

    private fun computeWeeklySummary(activities: List<ActivityEntity>): WeeklySummary {
        val weekStartMillis = LocalDate.now()
            .with(DayOfWeek.MONDAY)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val thisWeek = activities.filter { it.startTime >= weekStartMillis }
        return WeeklySummary(
            distanceMeters = thisWeek.sumOf { it.distanceMeters },
            movingTimeMillis = thisWeek.sumOf { it.movingTimeMillis },
            activityCount = thisWeek.size,
            elevationGainMeters = thisWeek.sumOf { it.elevationGainMeters },
        )
    }
}
