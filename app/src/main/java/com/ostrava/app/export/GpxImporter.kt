package com.ostrava.app.export

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.TrackPoint
import com.ostrava.app.domain.computeActivityStats
import com.ostrava.app.domain.estimateCalories
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import java.time.OffsetDateTime

object GpxImporter {

    /** Parses a GPX file and saves it as an activity. Returns the new id, or null if unusable. */
    suspend fun import(
        context: Context,
        repository: ActivityRepository,
        uri: Uri,
        weightKg: Float,
    ): Long? {
        val (title, typeHint, points) = context.contentResolver.openInputStream(uri)?.use { stream ->
            parse(stream)
        } ?: return null
        if (points.size < 2) return null

        val stats = computeActivityStats(points)
        if (stats.distanceMeters < 10.0) return null

        val startTime = points.first().timeMillis
        if (repository.existsByStartTime(startTime)) return null

        val type = when {
            typeHint.contains("bik", ignoreCase = true) ||
                typeHint.contains("cycl", ignoreCase = true) ||
                typeHint.contains("ride", ignoreCase = true) -> ActivityType.RIDE
            typeHint.contains("walk", ignoreCase = true) -> ActivityType.WALK
            typeHint.contains("hik", ignoreCase = true) -> ActivityType.HIKE
            else -> ActivityType.RUN
        }
        val entity = ActivityEntity(
            type = type.name,
            title = title.ifBlank { "Imported ${type.label}" },
            startTime = startTime,
            endTime = points.last().timeMillis,
            movingTimeMillis = stats.movingTimeMillis,
            distanceMeters = stats.distanceMeters,
            avgSpeedMps = stats.avgSpeedMps,
            maxSpeedMps = stats.maxSpeedMps,
            elevationGainMeters = stats.elevationGainMeters,
            calories = estimateCalories(type, stats.avgSpeedMps, stats.movingTimeMillis, weightKg),
        )
        return repository.saveActivityWithEfforts(entity, points)
    }

    private fun parse(stream: java.io.InputStream): Triple<String, String, List<TrackPoint>> {
        val parser = Xml.newPullParser()
        parser.setInput(stream, null)

        var title = ""
        var type = ""
        val points = mutableListOf<TrackPoint>()
        var segment = -1
        var inTrk = false

        var lat = 0.0
        var lon = 0.0
        var ele = 0.0
        var time = 0L
        var inTrkpt = false
        var currentTag = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (parser.name) {
                        "trk" -> inTrk = true
                        "trkseg" -> segment++
                        "trkpt" -> {
                            inTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            ele = 0.0
                            time = 0L
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when {
                            inTrkpt && currentTag == "ele" -> ele = text.toDoubleOrNull() ?: 0.0
                            inTrkpt && currentTag == "time" -> time = parseTime(text)
                            inTrk && !inTrkpt && currentTag == "name" && title.isEmpty() -> title = text
                            inTrk && !inTrkpt && currentTag == "type" && type.isEmpty() -> type = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "trkpt" -> {
                            inTrkpt = false
                            if (time > 0) {
                                points += TrackPoint(
                                    latitude = lat,
                                    longitude = lon,
                                    altitude = ele,
                                    timeMillis = time,
                                    speedMps = 0f,
                                    segment = maxOf(segment, 0),
                                )
                            }
                        }
                        "trk" -> inTrk = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return Triple(title, type, points)
    }

    private fun parseTime(text: String): Long = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
