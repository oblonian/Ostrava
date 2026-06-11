package com.ostrava.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {

    @Insert
    suspend fun insertActivity(activity: ActivityEntity): Long

    @Insert
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)

    @Transaction
    suspend fun insertActivityWithPoints(
        activity: ActivityEntity,
        points: List<TrackPointEntity>,
    ): Long {
        val id = insertActivity(activity)
        insertTrackPoints(points.map { it.copy(activityId = id) })
        return id
    }

    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    fun observeActivities(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun observeActivity(id: Long): Flow<ActivityEntity?>

    @Query("SELECT * FROM track_points WHERE activityId = :activityId ORDER BY time ASC")
    suspend fun getTrackPoints(activityId: Long): List<TrackPointEntity>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteActivity(id: Long)

    @Query("UPDATE activities SET title = :title WHERE id = :id")
    suspend fun renameActivity(id: Long, title: String)
}
