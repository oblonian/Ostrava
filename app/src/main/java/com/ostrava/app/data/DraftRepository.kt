package com.ostrava.app.data

import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.DraftDao
import com.ostrava.app.data.db.DraftPointEntity
import com.ostrava.app.data.db.RecordingDraftEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.computeActivityStats
import com.ostrava.app.domain.defaultActivityTitle
import com.ostrava.app.domain.estimateCalories
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

data class RecordingDraft(
    val meta: RecordingDraftEntity,
    val points: List<TrackPoint>,
)

/**
 * Persists the recording in progress so it survives a crash or the process being killed.
 * The tracking service writes here as it records; the UI offers recovery when a draft
 * exists but nothing is recording.
 */
class DraftRepository(private val dao: DraftDao) {

    fun observeDraft(): Flow<RecordingDraftEntity?> = dao.observeDraft()

    suspend fun begin(type: ActivityType, startTime: Long) = dao.begin(
        RecordingDraftEntity(
            type = type.name,
            startTime = startTime,
            movingTimeMillis = 0L,
            distanceMeters = 0.0,
            elevationGainMeters = 0.0,
            maxSpeedMps = 0.0,
            segment = 0,
            updatedAt = System.currentTimeMillis(),
        )
    )

    suspend fun appendPoint(point: TrackPoint) = dao.insertPoint(
        DraftPointEntity(
            latitude = point.latitude,
            longitude = point.longitude,
            altitude = point.altitude,
            time = point.timeMillis,
            speedMps = point.speedMps,
            segment = point.segment,
            heartRate = point.heartRate,
        )
    )

    suspend fun updateProgress(meta: RecordingDraftEntity) = dao.upsertDraft(meta)

    suspend fun load(): RecordingDraft? {
        val meta = dao.getDraft() ?: return null
        val points = dao.getPoints().map {
            TrackPoint(
                latitude = it.latitude,
                longitude = it.longitude,
                altitude = it.altitude,
                timeMillis = it.time,
                speedMps = it.speedMps,
                segment = it.segment,
                heartRate = it.heartRate,
            )
        }
        return RecordingDraft(meta, points)
    }

    suspend fun clear() = dao.clear()
}

/** Turns an interrupted recording into a saved activity, or throws it away. */
class RecordingRecovery(
    private val drafts: DraftRepository,
    private val activities: ActivityRepository,
    private val settings: SettingsRepository,
) {
    /** Returns the new activity id, or null when the draft was too short to keep. */
    suspend fun saveDraft(): Long? {
        val draft = drafts.load() ?: return null
        val points = draft.points
        val stats = computeActivityStats(points)
        if (points.size < 2 || stats.distanceMeters < 10.0) {
            drafts.clear()
            return null
        }
        val type = ActivityType.fromName(draft.meta.type)
        // The timer's own count is exact; fall back to the track when it was never written.
        val movingTime = draft.meta.movingTimeMillis.takeIf { it > 0 } ?: stats.movingTimeMillis
        val avgSpeed = if (movingTime > 0) stats.distanceMeters / (movingTime / 1000.0) else 0.0
        val heartRates = points.mapNotNull { it.heartRate }
        val weightKg = settings.settings.first().weightKg
        val id = activities.saveActivityWithEfforts(
            ActivityEntity(
                type = type.name,
                title = defaultActivityTitle(type, draft.meta.startTime),
                startTime = draft.meta.startTime,
                endTime = points.last().timeMillis,
                movingTimeMillis = movingTime,
                distanceMeters = stats.distanceMeters,
                avgSpeedMps = avgSpeed,
                maxSpeedMps = maxOf(stats.maxSpeedMps, draft.meta.maxSpeedMps),
                elevationGainMeters = maxOf(stats.elevationGainMeters, draft.meta.elevationGainMeters),
                calories = estimateCalories(type, avgSpeed, movingTime, weightKg),
                avgHeartRate = heartRates.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size },
                maxHeartRate = heartRates.maxOrNull(),
            ),
            points,
        )
        drafts.clear()
        return id
    }

    suspend fun discardDraft() = drafts.clear()
}
