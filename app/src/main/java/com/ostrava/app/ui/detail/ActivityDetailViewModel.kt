package com.ostrava.app.ui.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.BestEffort
import com.ostrava.app.domain.METERS_PER_MILE
import com.ostrava.app.domain.Split
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.bestEffortsFor
import com.ostrava.app.domain.computeSplits
import com.ostrava.app.export.GpxExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailUiState(
    val activity: ActivityEntity? = null,
    val points: List<TrackPoint> = emptyList(),
    val splits: List<Split> = emptyList(),
    val bestEfforts: List<BestEffort> = emptyList(),
    val elevationProfile: List<Float> = emptyList(),
    val speedProfile: List<Float> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val loading: Boolean = true,
)

class ActivityDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ActivityRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val activityId: Long = checkNotNull(savedStateHandle["activityId"])

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState

    init {
        combine(
            repository.observeActivity(activityId),
            settingsRepository.settings,
        ) { activity, settings -> activity to settings }
            .onEach { (activity, settings) ->
                if (activity == null) {
                    _uiState.value = _uiState.value.copy(activity = null, loading = false)
                    return@onEach
                }
                val current = _uiState.value
                val points = current.points.ifEmpty {
                    withContext(Dispatchers.Default) { repository.getTrackPoints(activityId) }
                }
                val analysed = if (current.splits.isEmpty()) {
                    withContext(Dispatchers.Default) { analyse(activity, points, settings) }
                } else {
                    current
                }
                _uiState.value = analysed.copy(
                    activity = activity,
                    points = points,
                    settings = settings,
                    loading = false,
                )
            }
            .launchIn(viewModelScope)
    }

    private fun analyse(activity: ActivityEntity, points: List<TrackPoint>, settings: UserSettings): DetailUiState {
        val type = ActivityType.fromName(activity.type)
        val splitLength = if (settings.imperialUnits) METERS_PER_MILE else 1000.0
        return DetailUiState(
            splits = computeSplits(points, splitLength),
            bestEfforts = bestEffortsFor(type, points),
            elevationProfile = downsample(points.map { it.altitude.toFloat() }),
            speedProfile = downsample(smooth(points.map { it.speedMps })),
        )
    }

    /** Moving average to tame raw GPS speed jitter before charting. */
    private fun smooth(values: List<Float>, window: Int = 5): List<Float> {
        if (values.size <= window) return values
        return values.indices.map { i ->
            val from = maxOf(0, i - window / 2)
            val to = minOf(values.lastIndex, i + window / 2)
            var sum = 0f
            for (j in from..to) sum += values[j]
            sum / (to - from + 1)
        }
    }

    private fun downsample(values: List<Float>, maxPoints: Int = 200): List<Float> {
        if (values.size <= maxPoints) return values
        val step = values.size.toFloat() / maxPoints
        return (0 until maxPoints).map { values[(it * step).toInt()] }
    }

    fun rename(title: String) {
        viewModelScope.launch { repository.renameActivity(activityId, title) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteActivity(activityId)
            onDeleted()
        }
    }

    fun exportGpx(context: Context) {
        val state = _uiState.value
        val activity = state.activity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            GpxExporter.shareGpx(context, activity, state.points)
        }
    }
}
