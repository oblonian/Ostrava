package com.ostrava.app.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import java.time.Instant
import java.time.ZoneId

/**
 * Writes finished activities to Health Connect so other health apps can see them.
 * Entirely optional: nothing here runs unless the athlete turns the sync on and grants access.
 */
object HealthConnectSync {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
    )

    fun isAvailable(context: Context): Boolean = try {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    } catch (_: Exception) {
        false
    }

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun hasPermissions(context: Context): Boolean = try {
        isAvailable(context) &&
            HealthConnectClient.getOrCreate(context).permissionController
                .getGrantedPermissions().containsAll(permissions)
    } catch (_: Exception) {
        false
    }

    /** Returns true when the activity was written. Failures are swallowed: sync is best-effort. */
    suspend fun write(context: Context, activity: ActivityEntity): Boolean {
        if (activity.endTime <= activity.startTime || !hasPermissions(context)) return false
        return try {
            val start = Instant.ofEpochMilli(activity.startTime)
            val end = Instant.ofEpochMilli(activity.endTime)
            val zone = ZoneId.systemDefault().rules
            val startOffset = zone.getOffset(start)
            val endOffset = zone.getOffset(end)
            // A stable client id makes re-syncing the same activity an update, not a duplicate.
            fun metadata(kind: String) = Metadata(clientRecordId = "ostrava-${activity.id}-$kind")

            val records = buildList<Record> {
                add(
                    ExerciseSessionRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        exerciseType = exerciseType(ActivityType.fromName(activity.type)),
                        title = activity.title,
                        metadata = metadata("session"),
                    )
                )
                if (activity.distanceMeters > 0) {
                    add(
                        DistanceRecord(
                            startTime = start,
                            startZoneOffset = startOffset,
                            endTime = end,
                            endZoneOffset = endOffset,
                            distance = Length.meters(activity.distanceMeters),
                            metadata = metadata("distance"),
                        )
                    )
                }
                if (activity.calories > 0) {
                    add(
                        TotalCaloriesBurnedRecord(
                            startTime = start,
                            startZoneOffset = startOffset,
                            endTime = end,
                            endZoneOffset = endOffset,
                            energy = Energy.kilocalories(activity.calories.toDouble()),
                            metadata = metadata("calories"),
                        )
                    )
                }
                if (activity.elevationGainMeters > 0) {
                    add(
                        ElevationGainedRecord(
                            startTime = start,
                            startZoneOffset = startOffset,
                            endTime = end,
                            endZoneOffset = endOffset,
                            elevation = Length.meters(activity.elevationGainMeters),
                            metadata = metadata("elevation"),
                        )
                    )
                }
            }
            HealthConnectClient.getOrCreate(context).insertRecords(records)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun exerciseType(type: ActivityType): Int = when (type) {
        ActivityType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        ActivityType.RIDE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        ActivityType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        ActivityType.HIKE -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
    }
}
