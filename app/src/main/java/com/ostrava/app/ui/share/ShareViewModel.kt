package com.ostrava.app.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.RankedEffort
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.formatDate
import com.ostrava.app.domain.formatDistance
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.domain.formatElevation
import com.ostrava.app.domain.formatPace
import com.ostrava.app.domain.formatSpeed
import com.ostrava.app.export.MapBackdrop
import com.ostrava.app.export.MapBackdropLoader
import com.ostrava.app.export.ShareAspect
import com.ostrava.app.export.ShareCardRenderer
import com.ostrava.app.export.ShareContent
import com.ostrava.app.export.ShareOutput
import com.ostrava.app.export.ShareSpec
import com.ostrava.app.export.ShareStat
import com.ostrava.app.export.ShareStatValue
import com.ostrava.app.export.ShareTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShareUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val hasPhoto: Boolean = false,
    val availableStats: List<ShareStat> = emptyList(),
    val hasPersonalRecord: Boolean = false,
    val hasRoute: Boolean = false,
    val exporting: Boolean = false,
    val mapLoading: Boolean = false,
    /** True when the map tiles couldn't be fetched (usually: offline). */
    val mapUnavailable: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModel(
    savedStateHandle: SavedStateHandle,
    private val appContext: Context,
    private val repository: ActivityRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val activityId: Long = checkNotNull(savedStateHandle["activityId"])

    private val _spec = MutableStateFlow(ShareSpec())
    val spec: StateFlow<ShareSpec> = _spec

    private val _uiState = MutableStateFlow(ShareUiState())
    val uiState: StateFlow<ShareUiState> = _uiState

    private val photo = MutableStateFlow<Bitmap?>(null)
    private val content = MutableStateFlow<ShareContent?>(null)

    /** Map backdrops already built, keyed by the card shape they were framed for. */
    private val mapBackdrops = MutableStateFlow<Map<MapKey, MapBackdrop>>(emptyMap())
    private val mapsInFlight = mutableSetOf<MapKey>()

    private data class MapKey(val aspect: ShareAspect, val textAtTop: Boolean)

    private class RenderInput(
        val spec: ShareSpec,
        val photo: Bitmap?,
        val content: ShareContent?,
        val map: MapBackdrop?,
    )

    /** Half-resolution render of the card, re-drawn whenever an option changes. */
    val preview: StateFlow<Bitmap?> = combine(_spec, photo, content, mapBackdrops) { spec, photo, content, maps ->
        RenderInput(spec, photo, content, mapFor(spec, maps))
    }.mapLatest { input ->
        val content = input.content
        if (content == null) null
        else withContext(Dispatchers.Default) {
            ShareCardRenderer.render(input.spec, content, input.photo, input.map, PREVIEW_SCALE)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun mapFor(spec: ShareSpec, maps: Map<MapKey, MapBackdrop>): MapBackdrop? =
        if (spec.template == ShareTemplate.MAP) maps[MapKey(spec.aspect, spec.textAtTop)] else null

    init {
        viewModelScope.launch {
            val activity = repository.observeActivity(activityId).first()
            if (activity == null) {
                _uiState.value = ShareUiState(loading = false, missing = true)
                return@launch
            }
            val imperial = settingsRepository.settings.first().imperialUnits
            val (points, efforts) = withContext(Dispatchers.IO) {
                runCatching { repository.ensureBestEfforts(activityId) }
                simplify(repository.getTrackPoints(activityId)) to
                    repository.observeRankedEfforts(activityId).first()
            }
            val built = buildContent(activity, points, efforts, imperial)
            content.value = built
            _uiState.value = ShareUiState(
                loading = false,
                availableStats = built.stats.map { it.stat },
                hasPersonalRecord = built.prText != null,
                hasRoute = points.size >= 2,
            )
        }
        // Fetch the map the first time the Map style is shown for a given card shape.
        viewModelScope.launch {
            combine(_spec, content) { spec, content -> spec to content }.collect { (spec, content) ->
                if (spec.template == ShareTemplate.MAP && content != null) {
                    ensureMap(MapKey(spec.aspect, spec.textAtTop), content)
                }
            }
        }
    }

    private fun ensureMap(key: MapKey, content: ShareContent) {
        if (key in mapBackdrops.value || !mapsInFlight.add(key)) return
        viewModelScope.launch {
            _uiState.update { it.copy(mapLoading = true, mapUnavailable = false) }
            val backdrop = runCatching {
                MapBackdropLoader.load(appContext, content.points, key.aspect, key.textAtTop)
            }.getOrNull()
            mapsInFlight.remove(key)
            if (backdrop != null) mapBackdrops.update { it + (key to backdrop) }
            _uiState.update { it.copy(mapLoading = mapsInFlight.isNotEmpty(), mapUnavailable = backdrop == null) }
        }
    }

    /** Retries after a failed map download. */
    fun retryMap() {
        val content = content.value ?: return
        val spec = _spec.value
        ensureMap(MapKey(spec.aspect, spec.textAtTop), content)
    }

    /**
     * Drags and pinches on the preview reframe the photo. [dxFraction] and [dyFraction]
     * are the drag as a fraction of the preview's width and height.
     */
    fun movePhoto(dxFraction: Float, dyFraction: Float, zoomChange: Float) {
        val bitmap = photo.value ?: return
        _spec.update { spec ->
            if (spec.template != ShareTemplate.PHOTO) spec
            else ShareCardRenderer.movePhoto(spec, bitmap.width, bitmap.height, dxFraction, dyFraction, zoomChange)
        }
    }

    private fun buildContent(
        activity: ActivityEntity,
        points: List<TrackPoint>,
        efforts: List<RankedEffort>,
        imperial: Boolean,
    ): ShareContent {
        val type = ActivityType.fromName(activity.type)
        val stats = buildList {
            add(ShareStatValue(ShareStat.DISTANCE, formatDistance(activity.distanceMeters, imperial), "Distance"))
            add(ShareStatValue(ShareStat.TIME, formatDuration(activity.movingTimeMillis), "Time"))
            if (type.usesPace) {
                add(ShareStatValue(ShareStat.PACE, formatPace(activity.avgSpeedMps, imperial), "Pace"))
            } else {
                add(ShareStatValue(ShareStat.PACE, formatSpeed(activity.avgSpeedMps, imperial), "Avg speed"))
            }
            add(ShareStatValue(ShareStat.CLIMB, formatElevation(activity.elevationGainMeters, imperial), "Elev gain"))
            add(ShareStatValue(ShareStat.CALORIES, "${activity.calories} kcal", "Calories"))
            activity.avgHeartRate?.let {
                add(ShareStatValue(ShareStat.HEART_RATE, "$it bpm", "Avg HR"))
            }
            if (activity.maxSpeedMps > 0) {
                add(ShareStatValue(ShareStat.MAX_SPEED, formatSpeed(activity.maxSpeedMps, imperial), "Max speed"))
            }
        }
        val records = efforts.filter { it.effortRank == 1 }
        val prText = when (records.size) {
            0 -> null
            1 -> "🏆 PR · ${records[0].label}"
            else -> "🏆 ${records.size} PRs · ${records.joinToString(", ") { it.label }}"
        }
        return ShareContent(
            title = activity.title,
            subtitle = "${type.label} · ${formatDate(activity.startTime)}",
            stats = stats,
            points = points,
            prText = prText,
        )
    }

    /** The card only needs the route's shape, not every GPS fix. */
    private fun simplify(points: List<TrackPoint>, maxPoints: Int = 800): List<TrackPoint> {
        if (points.size <= maxPoints) return points
        val step = points.size.toDouble() / maxPoints
        return (0 until maxPoints).map { points[(it * step).toInt()] } + points.last()
    }

    fun update(transform: (ShareSpec) -> ShareSpec) = _spec.update(transform)

    fun toggleStat(stat: ShareStat) = _spec.update { spec ->
        val stats = when {
            stat in spec.stats -> spec.stats - stat
            spec.stats.size >= ShareCardRenderer.MAX_STATS -> spec.stats
            else -> (spec.stats + stat).sortedBy { it.ordinal }
        }
        spec.copy(stats = stats)
    }

    fun setPhoto(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { ShareOutput.loadPhoto(appContext, uri) }
            if (bitmap == null) {
                Toast.makeText(appContext, "Couldn't load that photo", Toast.LENGTH_SHORT).show()
                return@launch
            }
            photo.value = bitmap
            _uiState.update { it.copy(hasPhoto = true) }
            // A new photo starts centred and un-zoomed.
            _spec.update {
                it.copy(template = ShareTemplate.PHOTO, photoZoom = 1f, photoCenterX = 0.5f, photoCenterY = 0.5f)
            }
        }
    }

    fun removePhoto() {
        photo.value = null
        _uiState.update { it.copy(hasPhoto = false) }
    }

    fun share(context: Context) = export(context) { bitmap, photographic, title ->
        val intent = withContext(Dispatchers.IO) {
            ShareOutput.shareIntent(context, bitmap, "ostrava_$activityId", photographic, title)
        }
        context.startActivity(intent)
    }

    fun saveToGallery(context: Context) = export(context) { bitmap, photographic, _ ->
        val appContext = context.applicationContext
        val saved = withContext(Dispatchers.IO) {
            ShareOutput.saveToGallery(
                appContext, bitmap, "ostrava_${activityId}_${System.currentTimeMillis()}", photographic,
            )
        }
        Toast.makeText(
            appContext,
            if (saved) "Saved to Pictures/Ostrava" else "Couldn't save the image",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Renders the card at full resolution and hands it to [action] on the main thread. */
    private fun export(context: Context, action: suspend (Bitmap, Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        val currentContent = content.value ?: return
        if (_uiState.value.exporting) return
        val currentSpec = _spec.value
        val currentPhoto = photo.value
        val currentMap = mapFor(currentSpec, mapBackdrops.value)
        viewModelScope.launch {
            _uiState.update { it.copy(exporting = true) }
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    ShareCardRenderer.render(currentSpec, currentContent, currentPhoto, currentMap)
                }
                // Photos and maps compress well as JPEG; the sticker must stay PNG to keep
                // its transparent background.
                val photographic = (currentSpec.template == ShareTemplate.PHOTO && currentPhoto != null) ||
                    (currentSpec.template == ShareTemplate.MAP && currentMap != null)
                action(bitmap, photographic, currentContent.title)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(appContext, "Couldn't export the image", Toast.LENGTH_SHORT).show()
            } finally {
                _uiState.update { it.copy(exporting = false) }
            }
        }
    }

    private companion object {
        const val PREVIEW_SCALE = 0.5f
    }
}
