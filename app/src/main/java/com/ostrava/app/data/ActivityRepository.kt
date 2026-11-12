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
import com.ostrava.app.domain.matchSegment
import kotlinx.coroutines.flow.Flow

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
            )
        }

    /** Saves the activity and records efforts for any segments its route matches. */
    suspend fun saveActivityWithEfforts(activity: ActivityEntity, points: List<TrackPoint>): Long {
        val id = dao.insertActivityWithPoints(activity, points.map { it.toEntity() })
        dao.getSegmentsByType(activity.type).forEach { segment ->
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
                        activityId = id,
                        durationMillis = match.durationMillis,
                        startTime = match.startTimeMillis,
                    )
                )
            }
        }
        storeBestEfforts(activity.copy(id = id), points)
        return id
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

    fun observeAllBestEfforts(): Flow<List<EffortWithActivity>> = dao.observeAllBestEfforts()

    suspend fun deleteActivity(id: Long) = dao.deleteActivity(id)

    suspend fun renameActivity(id: Long, title: String) = dao.renameActivity(id, title)

    // Segments

    suspend fun createSegmentFromActivity(
        activity: ActivityEntity,
        points: List<TrackPoint>,
        name: String,
    ): Long {
        val first = points.first()
        val last = points.last()
        val segmentId = dao.insertSegment(
            SegmentEntity(
                name = name,
                activityType = activity.type,
                startLat = first.latitude,
                startLon = first.longitude,
                endLat = last.latitude,
                endLon = last.longitude,
                distanceMeters = activity.distanceMeters,
                createdAt = System.currentTimeMillis(),
            )
        )
        // The source activity is by definition the first effort.
        dao.insertSegmentEffort(
            SegmentEffortEntity(
                segmentId = segmentId,
                activityId = activity.id,
                durationMillis = activity.movingTimeMillis,
                startTime = activity.startTime,
            )
        )
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
    )

    companion object {
        /** Bump when the benchmark distances change so cached efforts are recomputed. */
        const val EFFORTS_VERSION = 1
    }
}
