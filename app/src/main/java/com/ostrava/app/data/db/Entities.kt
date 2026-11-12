package com.ostrava.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val movingTimeMillis: Long,
    val distanceMeters: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val calories: Int,
    // v2: nullable so the 1->2 migration is a plain ALTER TABLE ADD COLUMN.
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val feel: String? = null,
    // v3: null until best efforts have been computed for this activity.
    val effortsVersion: Int? = null,
)

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("activityId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val time: Long,
    val speedMps: Float,
    val segment: Int,
    // v4: nullable so the 3->4 migration is a plain ALTER TABLE ADD COLUMN.
    val heartRate: Int? = null,
)

/**
 * GPS fixes of the recording in progress, written as they arrive so a crash or a
 * killed process doesn't lose the activity. Cleared when the recording is saved or discarded.
 */
@Entity(tableName = "draft_points")
data class DraftPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val time: Long,
    val speedMps: Float,
    val segment: Int,
    val heartRate: Int? = null,
)

/** Progress of the recording in progress; a single row with id 1. */
@Entity(tableName = "recording_draft")
data class RecordingDraftEntity(
    @PrimaryKey val id: Int = 1,
    val type: String,
    val startTime: Long,
    val movingTimeMillis: Long,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val maxSpeedMps: Double,
    val segment: Int,
    val updatedAt: Long,
)

@Entity(tableName = "segments")
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val activityType: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val distanceMeters: Double,
    val createdAt: Long,
)

@Entity(
    tableName = "segment_efforts",
    foreignKeys = [
        ForeignKey(
            entity = SegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("segmentId"), Index("activityId")],
)
data class SegmentEffortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val segmentId: Long,
    val activityId: Long,
    val durationMillis: Long,
    val startTime: Long,
)

/** Cached fastest time over a benchmark distance within one activity (drives all-time PRs). */
@Entity(
    tableName = "best_efforts",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("activityId")],
)
data class BestEffortEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val activityType: String,
    val label: String,
    val distanceMeters: Double,
    val durationMillis: Long,
    val achievedAt: Long,
)
