package com.ostrava.app.domain

import android.location.Location

private fun distanceBetween(a: TrackPoint, b: TrackPoint): Double {
    val results = FloatArray(1)
    Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
    return results[0].toDouble()
}

private fun distanceToPoint(lat: Double, lon: Double, point: TrackPoint): Double {
    val results = FloatArray(1)
    Location.distanceBetween(lat, lon, point.latitude, point.longitude, results)
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

/** Summary stats derived from a raw track, used by GPX import and backup restore. */
data class ComputedStats(
    val distanceMeters: Double,
    val movingTimeMillis: Long,
    val elevationGainMeters: Double,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
)

fun computeActivityStats(points: List<TrackPoint>): ComputedStats {
    if (points.size < 2) return ComputedStats(0.0, 0L, 0.0, 0.0, 0.0)
    var distance = 0.0
    var movingTime = 0L
    var gain = 0.0
    var maxSpeed = 0.0
    var altitudeBaseline = points.first().altitude

    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val point = points[i]
        if (point.segment != prev.segment) {
            altitudeBaseline = point.altitude
            continue
        }
        val d = distanceBetween(prev, point)
        val dt = point.timeMillis - prev.timeMillis
        distance += d
        // Gaps longer than 15 s are treated as standing still, not moving time.
        if (dt in 1..15_000) {
            movingTime += dt
            val legSpeed = d / (dt / 1000.0)
            if (legSpeed > maxSpeed && legSpeed < 40.0) maxSpeed = legSpeed
        }
        when {
            point.altitude - altitudeBaseline >= 2.0 -> {
                gain += point.altitude - altitudeBaseline
                altitudeBaseline = point.altitude
            }
            point.altitude < altitudeBaseline -> altitudeBaseline = point.altitude
        }
    }
    val avgSpeed = if (movingTime > 0) distance / (movingTime / 1000.0) else 0.0
    return ComputedStats(distance, movingTime, gain, avgSpeed, maxSpeed)
}

/** A matched segment traversal within an activity. */
data class SegmentMatch(
    val durationMillis: Long,
    val startTimeMillis: Long,
)

private const val SEGMENT_MATCH_RADIUS_METERS = 40.0

/**
 * Finds the fastest traversal of a segment (start gate -> end gate) within a track.
 * A traversal counts when the path passes within [SEGMENT_MATCH_RADIUS_METERS] of both
 * gates and covers a path length comparable to the segment's distance.
 */
fun matchSegment(
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double,
    segmentDistanceMeters: Double,
    points: List<TrackPoint>,
): SegmentMatch? {
    if (points.size < 2 || segmentDistanceMeters <= 0) return null
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

    val minPath = segmentDistanceMeters * 0.75
    val maxPath = segmentDistanceMeters * 1.35
    var best: SegmentMatch? = null

    var i = 0
    while (i < n) {
        if (distanceToPoint(startLat, startLon, points[i]) <= SEGMENT_MATCH_RADIUS_METERS) {
            var j = i + 1
            while (j < n && cumDistance[j] - cumDistance[i] <= maxPath) {
                if (cumDistance[j] - cumDistance[i] >= minPath &&
                    distanceToPoint(endLat, endLon, points[j]) <= SEGMENT_MATCH_RADIUS_METERS
                ) {
                    val duration = cumTime[j] - cumTime[i]
                    if (duration > 0 && (best == null || duration < best.durationMillis)) {
                        best = SegmentMatch(duration, points[i].timeMillis)
                    }
                    break
                }
                j++
            }
            // Skip past this start-gate cluster before looking for another traversal.
            while (i + 1 < n &&
                distanceToPoint(startLat, startLon, points[i + 1]) <= SEGMENT_MATCH_RADIUS_METERS
            ) {
                i++
            }
        }
        i++
    }
    return best
}

/**
 * Weekly streaks from the set of week indices that contain at least one activity.
 * Returns (current, longest). The current streak tolerates the present week being
 * empty so a Monday doesn't zero everyone's streak.
 */
fun weeklyStreaks(activeWeekIndices: Set<Long>, currentWeekIndex: Long): Pair<Int, Int> {
    if (activeWeekIndices.isEmpty()) return 0 to 0

    var current = 0
    var week = if (currentWeekIndex in activeWeekIndices) currentWeekIndex else currentWeekIndex - 1
    while (week in activeWeekIndices) {
        current++
        week--
    }

    var longest = 0
    var run = 0
    var previous: Long? = null
    for (w in activeWeekIndices.sorted()) {
        run = if (previous != null && w == previous + 1) run + 1 else 1
        if (run > longest) longest = run
        previous = w
    }
    return current to longest
}
