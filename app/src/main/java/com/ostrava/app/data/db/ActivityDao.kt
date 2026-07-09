package com.ostrava.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class SegmentWithStats(
    val id: Long,
    val name: String,
    val activityType: String,
    val distanceMeters: Double,
    val attempts: Int,
    val bestMillis: Long?,
)

data class EffortForActivity(
    val segmentName: String,
    val durationMillis: Long,
    val bestMillis: Long,
)

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

    @Query("SELECT * FROM activities ORDER BY startTime DESC")
    suspend fun getActivitiesList(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun observeActivity(id: Long): Flow<ActivityEntity?>

    @Query("SELECT COUNT(*) FROM activities WHERE startTime = :startTime")
    suspend fun countByStartTime(startTime: Long): Int

    @Query("SELECT * FROM track_points WHERE activityId = :activityId ORDER BY time ASC")
    suspend fun getTrackPoints(activityId: Long): List<TrackPointEntity>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteActivity(id: Long)

    @Query("UPDATE activities SET title = :title WHERE id = :id")
    suspend fun renameActivity(id: Long, title: String)

    // Segments

    @Insert
    suspend fun insertSegment(segment: SegmentEntity): Long

    @Insert
    suspend fun insertSegmentEffort(effort: SegmentEffortEntity)

    @Query("SELECT * FROM segments WHERE activityType = :type")
    suspend fun getSegmentsByType(type: String): List<SegmentEntity>

    @Query("SELECT * FROM segments")
    suspend fun getSegmentsList(): List<SegmentEntity>

    @Query("DELETE FROM segments WHERE id = :id")
    suspend fun deleteSegment(id: Long)

    @Query(
        "SELECT s.id AS id, s.name AS name, s.activityType AS activityType, " +
            "s.distanceMeters AS distanceMeters, COUNT(e.id) AS attempts, " +
            "MIN(e.durationMillis) AS bestMillis " +
            "FROM segments s LEFT JOIN segment_efforts e ON e.segmentId = s.id " +
            "GROUP BY s.id ORDER BY s.createdAt DESC"
    )
    fun observeSegmentStats(): Flow<List<SegmentWithStats>>

    @Query(
        "SELECT s.name AS segmentName, e.durationMillis AS durationMillis, " +
            "(SELECT MIN(durationMillis) FROM segment_efforts WHERE segmentId = s.id) AS bestMillis " +
            "FROM segment_efforts e JOIN segments s ON s.id = e.segmentId " +
            "WHERE e.activityId = :activityId"
    )
    fun observeEffortsForActivity(activityId: Long): Flow<List<EffortForActivity>>
}
