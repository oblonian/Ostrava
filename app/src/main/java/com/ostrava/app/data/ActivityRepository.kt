package com.ostrava.app.data

import com.ostrava.app.data.db.ActivityDao
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.TrackPointEntity
import com.ostrava.app.domain.TrackPoint
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val dao: ActivityDao) {

    fun observeActivities(): Flow<List<ActivityEntity>> = dao.observeActivities()

    fun observeActivity(id: Long): Flow<ActivityEntity?> = dao.observeActivity(id)

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

    suspend fun saveActivity(activity: ActivityEntity, points: List<TrackPoint>): Long =
        dao.insertActivityWithPoints(
            activity,
            points.map {
                TrackPointEntity(
                    activityId = 0,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    altitude = it.altitude,
                    time = it.timeMillis,
                    speedMps = it.speedMps,
                    segment = it.segment,
                )
            },
        )

    suspend fun deleteActivity(id: Long) = dao.deleteActivity(id)

    suspend fun renameActivity(id: Long, title: String) = dao.renameActivity(id, title)
}
