package com.ostrava.app.ui.detail

import android.content.Context
import android.location.Location
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.EffortForActivity
import com.ostrava.app.data.db.RankedEffort
import com.ostrava.app.domain.METERS_PER_MILE
import com.ostrava.app.domain.Split
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.computeSplits
import com.ostrava.app.domain.timeInHeartRateZones
import com.ostrava.app.export.GpxExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailUiState(
    val activity: ActivityEntity? = null,
    val points: List<TrackPoint> = emptyList(),
    /** Distance from the start to each point, for the trim and segment pickers. */
    val cumulativeDistances: List<Float> = emptyList(),
    val splits: List<Split> = emptyList(),
    val elevationProfile: List<Float> = emptyList(),
    val speedProfile: List<Float> = emptyList(),
    val heartRateProfile: List<Float> = emptyList(),
    /** Milliseconds in heart-rate zones 1-5; empty when there is no heart-rate data. */
    val heartRateZones: List<Long> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val loading: Boolean = true,
)

class ActivityDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ActivityRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val activityId: Long = checkNotNull(savedStateHandle["activityId"])

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState

    /** Bumped when the track itself changes (trim), so it is re-read and re-analysed. */
    private val trackVersion = MutableStateFlow(0)
    private var analysedVersion = -1
    private var analysedImperial: Boolean? = null

    val segmentEfforts: StateFlow<List<EffortForActivity>> =
        repository.observeEffortsForActivity(activityId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** This activity's best efforts with their all-time rank (1 = personal record). */
    val rankedEfforts: StateFlow<List<RankedEffort>> =
        repository.observeRankedEfforts(activityId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.ensureBestEfforts(activityId) }
        }
        combine(
            repository.observeActivity(activityId),
            settingsRepository.settings,
            trackVersion,
        ) { activity, settings, version -> Triple(activity, settings, version) }
            .onEach { (activity, settings, version) ->
                if (activity == null) {
                    _uiState.update { it.copy(activity = null, loading = false) }
                    return@onEach
                }
                // Reading and analysing the track is the expensive part; redo it only when
                // the track or the split unit changes, not on every rename.
                val analysed = if (version != analysedVersion || settings.imperialUnits != analysedImperial) {
                    withContext(Dispatchers.Default) {
                        analyse(repository.getTrackPoints(activityId), settings)
                    }.also {
                        analysedVersion = version
                        analysedImperial = settings.imperialUnits
                    }
                } else {
                    _uiState.value
                }
                _uiState.value = analysed.copy(activity = activity, settings = settings, loading = false)
            }
            .launchIn(viewModelScope)
    }

    private fun analyse(points: List<TrackPoint>, settings: UserSettings): DetailUiState {
        val splitLength = if (settings.imperialUnits) METERS_PER_MILE else 1000.0
        return DetailUiState(
            points = points,
            cumulativeDistances = cumulativeDistances(points),
            splits = computeSplits(points, splitLength),
            elevationProfile = downsample(points.map { it.altitude.toFloat() }),
            speedProfile = downsample(smooth(points.map { it.speedMps })),
            heartRateProfile = downsample(points.mapNotNull { it.heartRate?.toFloat() }),
            heartRateZones = timeInHeartRateZones(points, settings.maxHeartRate),
        )
    }

    private fun cumulativeDistances(points: List<TrackPoint>): List<Float> {
        if (points.isEmpty()) return emptyList()
        val result = FloatArray(points.size)
        val leg = FloatArray(1)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val point = points[i]
            result[i] = result[i - 1]
            if (point.segment == previous.segment) {
                Location.distanceBetween(
                    previous.latitude, previous.longitude, point.latitude, point.longitude, leg,
                )
                result[i] += leg[0]
            }
        }
        return result.toList()
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

    /** Saves the stretch between two track points as a segment. */
    fun createSegment(name: String, fromIndex: Int, toIndex: Int) {
        val state = _uiState.value
        val activity = state.activity ?: return
        if (state.points.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.createSegmentFromActivity(activity, state.points, name, fromIndex, toIndex)
        }
    }

    /** Cuts the activity down to the stretch between two track points. */
    fun trim(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val weightKg = _uiState.value.settings.weightKg
            val trimmed = withContext(Dispatchers.IO) {
                repository.trimActivity(activityId, fromIndex, toIndex, weightKg)
            }
            if (trimmed) trackVersion.update { it + 1 }
        }
    }
}
