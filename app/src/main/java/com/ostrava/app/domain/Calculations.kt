package com.ostrava.app.domain

import android.location.Location

private fun distanceBetween(a: TrackPoint, b: TrackPoint): Double {
    val results = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return results[0].toDouble()
}

/**
 * Buckets the track into consecutive splits of [splitLengthMeters], linearly interpolating
 * the boundary crossing time so split durations are accurate.
 */
fun computeSplits(points: List<TrackPoint>, splitLengthMeters: Double): List<Split> {
    if (points.size < 2) return emptyList()
    val splits = mutableListOf<Split>()
    var splitDistance = 0.0
    var splitStartTime = points.first().timeMillis
    var splitStartAltitude = points.first().altitude
    var index = 1

    var prev = points.first()
    for (point in points.drop(1)) {
        if (point.segment != prev.segment) {
            // Across a pause: no distance accrues and time gap is skipped by shifting the split start.
            splitStartTime += point.timeMillis - prev.timeMillis
            prev = point
            continue
        }
        var legDistance = distanceBetween(prev, point)
        var legStartTime = prev.timeMillis
        var legDuration = (point.timeMillis - prev.timeMillis).toDouble()

        while (splitDistance + legDistance >= splitLengthMeters && legDistance > 0) {
            val needed = splitLengthMeters - splitDistance
            val fraction = needed / legDistance
            val crossingTime = legStartTime + (legDuration * fraction).toLong()
            splits += Split(
                index = index,
                distanceMeters = splitLengthMeters,
                durationMillis = crossingTime - splitStartTime,
                elevationDelta = point.altitude - splitStartAltitude,
            )
            index++
            splitStartTime = crossingTime
            splitStartAltitude = point.altitude
            legDuration -= legDuration * fraction
            legStartTime = crossingTime
            legDistance -= needed
            splitDistance = 0.0
        }
        splitDistance += legDistance
        prev = point
    }
    if (splitDistance > splitLengthMeters * 0.05) {
        splits += Split(
            index = index,
            distanceMeters = splitDistance,
            durationMillis = points.last().timeMillis - splitStartTime,
            elevationDelta = points.last().altitude - splitStartAltitude,
        )
    }
    return splits
}

/**
 * Fastest contiguous effort covering [targetMeters], found with a two-pointer sweep over
 * cumulative distance/time. Returns null if the activity is shorter than the target.
 */
fun bestEffort(points: List<TrackPoint>, targetMeters: Double, label: String): BestEffort? {
    if (points.size < 2) return null
    val n = points.size
    val cumDistance = DoubleArray(n)
    val cumTime = LongArray(n)
    for (i in 1 until n) {
        val sameSegment = points[i].segment == points[i - 1].segment
        cumDistance[i] = cumDistance[i - 1] +
            if (sameSegment) distanceBetween(points[i - 1], points[i]) else 0.0
        cumTime[i] = cumTime[i - 1] +
            if (sameSegment) points[i].timeMillis - points[i - 1].timeMillis else 0L
    }
    if (cumDistance[n - 1] < targetMeters) return null

    var best = Long.MAX_VALUE
    var start = 0
    for (end in 1 until n) {
        while (cumDistance[end] - cumDistance[start + 1] >= targetMeters && start + 1 < end) {
            start++
        }
        if (cumDistance[end] - cumDistance[start] >= targetMeters) {
            val duration = cumTime[end] - cumTime[start]
            if (duration in 1 until best) best = duration
        }
    }
    return if (best == Long.MAX_VALUE) null else BestEffort(label, targetMeters, best)
}

fun bestEffortsFor(type: ActivityType, points: List<TrackPoint>): List<BestEffort> {
    val targets = when (type) {
        ActivityType.RUN -> listOf("1 km" to 1000.0, "5 km" to 5000.0, "10 km" to 10000.0, "Half marathon" to 21097.5)
        ActivityType.RIDE -> listOf("5 km" to 5000.0, "20 km" to 20000.0, "40 km" to 40000.0)
        else -> listOf("1 km" to 1000.0, "5 km" to 5000.0)
    }
    return targets.mapNotNull { (label, meters) -> bestEffort(points, meters, label) }
}

/** MET-based calorie estimate. */
fun estimateCalories(type: ActivityType, avgSpeedMps: Double, movingTimeMillis: Long, weightKg: Float): Int {
    val kmh = avgSpeedMps * 3.6
    val met = when (type) {
        ActivityType.RUN -> maxOf(6.0, kmh * 1.0)
        ActivityType.RIDE -> when {
            kmh < 16 -> 6.0
            kmh < 20 -> 8.0
            kmh < 25 -> 10.0
            else -> 12.0
        }
        ActivityType.WALK -> 3.5
        ActivityType.HIKE -> 6.0
    }
    val hours = movingTimeMillis / 3_600_000.0
    return (met * weightKg * hours).toInt()
}
