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
)
