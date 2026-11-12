package com.ostrava.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.InsightSource
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.Insight
import com.ostrava.app.domain.InsightEngine
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.estimateCalories
import com.ostrava.app.domain.weeklyStreaks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.IsoFields

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
    /** Activity id -> number of personal records it currently holds. */
    val recordCounts: Map<Long, Int> = emptyMap(),
    /** The most useful tips right now, already filtered for ones the athlete dismissed. */
    val insights: List<Insight> = emptyList(),
    val streakWeeks: Int = 0,
    /** Downsampled routes for the card thumbnails, loaded as cards come on screen. */
    val tracks: Map<Long, List<TrackPoint>> = emptyMap(),
    val loading: Boolean = true,
)

class FeedViewModel(
    private val repository: ActivityRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val tracks = MutableStateFlow<Map<Long, List<TrackPoint>>>(emptyMap())
    private val tracksRequested = mutableSetOf<Long>()

    private val content = combine(
        repository.observeActivities(),
        settingsRepository.settings,
        repository.observeRecordCounts(),
        repository.observeAllBestEfforts(),
    ) { activities, settings, recordCounts, efforts ->
        val now = ZonedDateTime.now()
        val insights = InsightEngine.generate(InsightSource.input(activities, efforts, settings, now))
            .filter { dismissKey(it, now) !in settings.dismissedInsights }
            .take(MAX_INSIGHTS)
        FeedUiState(
            activities = activities,
            settings = settings,
            weeklySummary = computeWeeklySummary(activities),
            recordCounts = recordCounts,
            insights = insights,
            streakWeeks = currentStreak(activities, now.toLocalDate(), now.zone),
            loading = false,
        )
    }

    val uiState: StateFlow<FeedUiState> = combine(content, tracks) { state, tracks ->
        state.copy(tracks = tracks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FeedUiState())

    /** Loads the route thumbnail for an activity the first time its card is shown. */
    fun requestTrack(activityId: Long) {
        if (!tracksRequested.add(activityId)) return
        viewModelScope.launch {
            val points = repository.getTrackPoints(activityId)
            val thumbnail = if (points.size <= THUMBNAIL_POINTS) points
            else {
                val step = points.size.toFloat() / THUMBNAIL_POINTS
                (0 until THUMBNAIL_POINTS).map { points[(it * step).toInt()] }
            }
            tracks.update { it + (activityId to thumbnail) }
        }
    }

    /** Hides a tip for the rest of the week. */
    fun dismissInsight(insight: Insight) {
        viewModelScope.launch {
            settingsRepository.dismissInsight(dismissKey(insight, ZonedDateTime.now()))
        }
    }

    /** Saves an activity entered by hand (no GPS track). */
    fun addManualActivity(
        type: ActivityType,
        title: String,
        startTime: Long,
        durationMillis: Long,
        distanceMeters: Double,
        elevationGainMeters: Double,
    ) {
        if (durationMillis <= 0) return
        viewModelScope.launch {
            val weightKg = settingsRepository.settings.first().weightKg
            val avgSpeed = distanceMeters / (durationMillis / 1000.0)
            repository.addManualActivity(
                ActivityEntity(
                    type = type.name,
                    title = title,
                    startTime = startTime,
                    endTime = startTime + durationMillis,
                    movingTimeMillis = durationMillis,
                    distanceMeters = distanceMeters,
                    avgSpeedMps = avgSpeed,
                    maxSpeedMps = 0.0,
                    elevationGainMeters = elevationGainMeters,
                    calories = estimateCalories(type, avgSpeed, durationMillis, weightKg),
                )
            )
        }
    }

    private fun dismissKey(insight: Insight, now: ZonedDateTime): String =
        "${insight.id}:${now.get(IsoFields.WEEK_BASED_YEAR)}-${now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

    private fun currentStreak(activities: List<ActivityEntity>, today: LocalDate, zone: ZoneId): Int {
        fun weekIndex(date: LocalDate) = Math.floorDiv(date.with(DayOfWeek.MONDAY).toEpochDay(), 7L)
        val weeks = activities.map { weekIndex(Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate()) }.toSet()
        return weeklyStreaks(weeks, weekIndex(today)).first
    }

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

    private companion object {
        const val MAX_INSIGHTS = 2
        const val THUMBNAIL_POINTS = 80
    }
}
