package com.ostrava.app.data

import com.ostrava.app.data.db.ActivityDao
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.EffortForActivity
import com.ostrava.app.data.db.SegmentEffortEntity
import com.ostrava.app.data.db.SegmentEntity
import com.ostrava.app.data.db.SegmentWithStats
import com.ostrava.app.data.db.TrackPointEntity
import com.ostrava.app.domain.TrackPoint
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
        return id
    }

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
}
