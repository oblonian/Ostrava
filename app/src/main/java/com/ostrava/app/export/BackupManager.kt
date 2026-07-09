package com.ostrava.app.export

import android.content.Context
import android.net.Uri
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.SegmentEntity
import com.ostrava.app.domain.TrackPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-fidelity JSON backup of activities (with GPS tracks) and segments.
 * Segment efforts are recomputed on restore by re-matching each activity,
 * so they don't need id-remapping in the file format.
 */
object BackupManager {

    private const val FORMAT_VERSION = 1

    suspend fun export(context: Context, repository: ActivityRepository, uri: Uri): Int {
        val activities = repository.getActivitiesList()
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("app", "ostrava")

        val activitiesJson = JSONArray()
        for (activity in activities) {
            val points = repository.getTrackPoints(activity.id)
            activitiesJson.put(JSONObject().apply {
                put("type", activity.type)
                put("title", activity.title)
                put("startTime", activity.startTime)
                put("endTime", activity.endTime)
                put("movingTimeMillis", activity.movingTimeMillis)
                put("distanceMeters", activity.distanceMeters)
                put("avgSpeedMps", activity.avgSpeedMps)
                put("maxSpeedMps", activity.maxSpeedMps)
                put("elevationGainMeters", activity.elevationGainMeters)
                put("calories", activity.calories)
                activity.avgHeartRate?.let { put("avgHeartRate", it) }
                activity.maxHeartRate?.let { put("maxHeartRate", it) }
                activity.feel?.let { put("feel", it) }
                put("points", JSONArray().apply {
                    points.forEach { p ->
                        put(JSONArray().apply {
                            put(p.latitude)
                            put(p.longitude)
                            put(p.altitude)
                            put(p.timeMillis)
                            put(p.speedMps.toDouble())
                            put(p.segment)
                        })
                    }
                })
            })
        }
        root.put("activities", activitiesJson)

        val segmentsJson = JSONArray()
        repository.getSegmentsList().forEach { s ->
            segmentsJson.put(JSONObject().apply {
                put("name", s.name)
                put("activityType", s.activityType)
                put("startLat", s.startLat)
                put("startLon", s.startLon)
                put("endLat", s.endLat)
                put("endLon", s.endLon)
                put("distanceMeters", s.distanceMeters)
                put("createdAt", s.createdAt)
            })
        }
        root.put("segments", segmentsJson)

        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(root.toString().toByteArray())
        } ?: return 0
        return activities.size
    }

    /** Returns the number of activities imported (duplicates by startTime are skipped). */
    suspend fun import(context: Context, repository: ActivityRepository, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().decodeToString()
        } ?: return 0
        val root = JSONObject(text)

        // Restore segments first so restored activities can match against them.
        val existingSegments = repository.getSegmentsList()
        val segmentsJson = root.optJSONArray("segments") ?: JSONArray()
        for (i in 0 until segmentsJson.length()) {
            val s = segmentsJson.getJSONObject(i)
            val createdAt = s.getLong("createdAt")
            if (existingSegments.none { it.createdAt == createdAt && it.name == s.getString("name") }) {
                repository.insertSegment(
                    SegmentEntity(
                        name = s.getString("name"),
                        activityType = s.getString("activityType"),
                        startLat = s.getDouble("startLat"),
                        startLon = s.getDouble("startLon"),
                        endLat = s.getDouble("endLat"),
                        endLon = s.getDouble("endLon"),
                        distanceMeters = s.getDouble("distanceMeters"),
                        createdAt = createdAt,
                    )
                )
            }
        }

        var imported = 0
        val activitiesJson = root.optJSONArray("activities") ?: JSONArray()
        for (i in 0 until activitiesJson.length()) {
            val a = activitiesJson.getJSONObject(i)
            val startTime = a.getLong("startTime")
            if (repository.existsByStartTime(startTime)) continue

            val pointsJson = a.getJSONArray("points")
            val points = ArrayList<TrackPoint>(pointsJson.length())
            for (j in 0 until pointsJson.length()) {
                val p = pointsJson.getJSONArray(j)
                points += TrackPoint(
                    latitude = p.getDouble(0),
                    longitude = p.getDouble(1),
                    altitude = p.getDouble(2),
                    timeMillis = p.getLong(3),
                    speedMps = p.getDouble(4).toFloat(),
                    segment = p.getInt(5),
                )
            }
            repository.saveActivityWithEfforts(
                ActivityEntity(
                    type = a.getString("type"),
                    title = a.getString("title"),
                    startTime = startTime,
                    endTime = a.getLong("endTime"),
                    movingTimeMillis = a.getLong("movingTimeMillis"),
                    distanceMeters = a.getDouble("distanceMeters"),
                    avgSpeedMps = a.getDouble("avgSpeedMps"),
                    maxSpeedMps = a.getDouble("maxSpeedMps"),
                    elevationGainMeters = a.getDouble("elevationGainMeters"),
                    calories = a.getInt("calories"),
                    avgHeartRate = if (a.has("avgHeartRate")) a.getInt("avgHeartRate") else null,
                    maxHeartRate = if (a.has("maxHeartRate")) a.getInt("maxHeartRate") else null,
                    feel = if (a.has("feel")) a.getString("feel") else null,
                ),
                points,
            )
            imported++
        }
        return imported
    }
}
