package com.ostrava.app.data

import com.ostrava.app.data.db.ActivityDao
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.BestEffortEntity
import com.ostrava.app.data.db.EffortForActivity
import com.ostrava.app.data.db.EffortWithActivity
import com.ostrava.app.data.db.RankedEffort
import com.ostrava.app.data.db.SegmentEffortEntity
import com.ostrava.app.data.db.SegmentEntity
import com.ostrava.app.data.db.SegmentWithStats
import com.ostrava.app.data.db.TrackPointEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.bestEffortsFor
import com.ostrava.app.domain.computeActivityStats
import com.ostrava.app.domain.estimateCalories
import com.ostrava.app.domain.matchSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ActivityRepository(private val dao: ActivityDao) {

    fun observeActivities(): Flow<List<ActivityEntity>> = dao.observeActivities()

    suspend fun getActivitiesList(): List<ActivityEntity> = dao.getActivitiesList()

    fun observeActivity(id: Long): Flow<ActivityEntity?> = dao.observeActivity(id)

    suspend fun existsByStartTime(startTime: Long): Boolean = dao.countByStartTime(startTime) > 0

    suspend fun getTrackPoints(activityId: Long): List<TrackPoint> =
        dao.getTrackPoints(activityId).map {
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

    /** Saves the activity and records efforts for any segments its route matches. */
    suspend fun saveActivityWithEfforts(activity: ActivityEntity, points: List<TrackPoint>): Long {
        val id = dao.insertActivityWithPoints(activity, points.map { it.toEntity() })
        val saved = activity.copy(id = id)
        storeSegmentEfforts(saved, points)
        storeBestEfforts(saved, points)
        return id
    }

    /** An activity typed in by hand: it has stats but no GPS track. */
    suspend fun addManualActivity(activity: ActivityEntity): Long =
        dao.insertActivity(activity.copy(effortsVersion = EFFORTS_VERSION))

    private suspend fun storeSegmentEfforts(activity: ActivityEntity, points: List<TrackPoint>) {
        dao.getSegmentsByType(activity.type).forEach { segment ->
            matchAgainst(segment, activity.id, points)
        }
    }

    private suspend fun matchAgainst(segment: SegmentEntity, activityId: Long, points: List<TrackPoint>) {
        matchSegment(
            startLat = segment.startLat,
            startLon = segment.startLon,
            endLat = segment.endLat,
            endLon = segment.endLon,
            segmentDistanceMeters = segment.distanceMeters,
            points = points,
        )?.let { match ->
            dao.insertSegmentEffort(
                SegmentEffortEntity(
                    segmentId = segment.id,
                    activityId = activityId,
                    durationMillis = match.durationMillis,
                    startTime = match.startTimeMillis,
                )
            )
        }
    }

    /**
     * Cuts the activity down to the points between [fromIndex] and [toIndex] (inclusive)
     * and recomputes everything derived from the track. Returns false if nothing usable
     * would be left.
     */
    suspend fun trimActivity(activityId: Long, fromIndex: Int, toIndex: Int, weightKg: Float): Boolean {
        val activity = dao.getActivity(activityId) ?: return false
        val points = getTrackPoints(activityId)
        if (fromIndex < 0 || toIndex > points.lastIndex || toIndex - fromIndex < 1) return false
        if (fromIndex == 0 && toIndex == points.lastIndex) return false
        val kept = points.subList(fromIndex, toIndex + 1)
        val stats = computeActivityStats(kept)
        if (stats.distanceMeters < 10.0) return false

        val type = ActivityType.fromName(activity.type)
        val heartRates = kept.mapNotNull { it.heartRate }
        val updated = activity.copy(
            startTime = kept.first().timeMillis,
            endTime = kept.last().timeMillis,
            movingTimeMillis = stats.movingTimeMillis,
            distanceMeters = stats.distanceMeters,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
            elevationGainMeters = stats.elevationGainMeters,
            calories = estimateCalories(type, stats.avgSpeedMps, stats.movingTimeMillis, weightKg),
            avgHeartRate = heartRates.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
                ?: activity.avgHeartRate,
            maxHeartRate = heartRates.maxOrNull() ?: activity.maxHeartRate,
        )
        dao.deleteTrackPointsOutside(activityId, kept.first().timeMillis, kept.last().timeMillis)
        dao.updateActivity(updated)
        dao.deleteSegmentEffortsForActivity(activityId)
        storeSegmentEfforts(updated, kept)
        storeBestEfforts(updated, kept)
        return true
    }

    // Best efforts (personal records)

    private suspend fun storeBestEfforts(activity: ActivityEntity, points: List<TrackPoint>) {
        val efforts = bestEffortsFor(ActivityType.fromName(activity.type), points).map {
            BestEffortEntity(
                activityId = activity.id,
                activityType = activity.type,
                label = it.label,
                distanceMeters = it.distanceMeters,
                durationMillis = it.durationMillis,
                achievedAt = activity.startTime,
            )
        }
        dao.replaceBestEfforts(activity.id, EFFORTS_VERSION, efforts)
    }

    /** Computes cached best efforts for activities recorded before the cache existed. */
    suspend fun backfillBestEfforts() {
        dao.getActivitiesNeedingEfforts(EFFORTS_VERSION).forEach { activity ->
            storeBestEfforts(activity, getTrackPoints(activity.id))
        }
    }

    suspend fun ensureBestEfforts(activityId: Long) {
        val activity = dao.getActivity(activityId) ?: return
        if ((activity.effortsVersion ?: 0) < EFFORTS_VERSION) {
            storeBestEfforts(activity, getTrackPoints(activityId))
        }
    }

    fun observeRankedEfforts(activityId: Long): Flow<List<RankedEffort>> =
        dao.observeRankedEfforts(activityId)

    /** Records this activity took from an earlier effort (first-ever efforts don't count). */
    suspend fun newRecords(activityId: Long): List<RankedEffort> =
        dao.observeRankedEfforts(activityId).first().filter { it.effortRank == 1 && it.attempts > 1 }

    fun observeAllBestEfforts(): Flow<List<EffortWithActivity>> = dao.observeAllBestEfforts()

    /** Activity id -> number of all-time records it currently holds. */
    fun observeRecordCounts(): Flow<Map<Long, Int>> =
        dao.observeRecordCounts().map { rows -> rows.associate { it.activityId to it.recordCount } }

    suspend fun deleteActivity(id: Long) = dao.deleteActivity(id)

    suspend fun renameActivity(id: Long, title: String) = dao.renameActivity(id, title)

    // Segments

    /**
     * Saves the stretch of the route between [fromIndex] and [toIndex] as a segment, then
     * times every existing activity of the same sport against it so its history fills in.
     */
    suspend fun createSegmentFromActivity(
        activity: ActivityEntity,
        points: List<TrackPoint>,
        name: String,
        fromIndex: Int = 0,
        toIndex: Int = points.lastIndex,
    ): Long? {
        if (fromIndex < 0 || toIndex > points.lastIndex || toIndex - fromIndex < 1) return null
        val stretch = points.subList(fromIndex, toIndex + 1)
        val wholeRoute = fromIndex == 0 && toIndex == points.lastIndex
        val stats = computeActivityStats(stretch)
        val distance = if (wholeRoute) activity.distanceMeters else stats.distanceMeters
        val duration = if (wholeRoute) activity.movingTimeMillis else stats.movingTimeMillis
        if (distance < 50.0 || duration <= 0) return null

        val segment = SegmentEntity(
            name = name,
            activityType = activity.type,
            startLat = stretch.first().latitude,
            startLon = stretch.first().longitude,
            endLat = stretch.last().latitude,
            endLon = stretch.last().longitude,
            distanceMeters = distance,
            createdAt = System.currentTimeMillis(),
        )
        val segmentId = dao.insertSegment(segment)
        // The source activity is by definition the first effort.
        dao.insertSegmentEffort(
            SegmentEffortEntity(
                segmentId = segmentId,
                activityId = activity.id,
                durationMillis = duration,
                startTime = stretch.first().timeMillis,
            )
        )
        val saved = segment.copy(id = segmentId)
        dao.getActivitiesList()
            .filter { it.type == activity.type && it.id != activity.id }
            .forEach { other -> matchAgainst(saved, other.id, getTrackPoints(other.id)) }
        return segmentId
    }

    suspend fun getSegmentsList(): List<SegmentEntity> = dao.getSegmentsList()

    suspend fun insertSegment(segment: SegmentEntity): Long = dao.insertSegment(segment)

    suspend fun deleteSegment(id: Long) = dao.deleteSegment(id)

    fun observeSegmentStats(): Flow<List<SegmentWithStats>> = dao.observeSegmentStats()

    fun observeEffortsForActivity(activityId: Long): Flow<List<EffortForActivity>> =
        dao.observeEffortsForActivity(activityId)

    private fun TrackPoint.toEntity() = TrackPointEntity(
        activityId = 0,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        time = timeMillis,
        speedMps = speedMps,
        segment = segment,
        heartRate = heartRate,
    )

    companion object {
        /** Bump when the benchmark distances change so cached efforts are recomputed. */
        const val EFFORTS_VERSION = 1
    }
}
