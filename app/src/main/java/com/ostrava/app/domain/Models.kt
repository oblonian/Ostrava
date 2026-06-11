package com.ostrava.app.domain

enum class ActivityType(val label: String) {
    RUN("Run"),
    RIDE("Ride"),
    WALK("Walk"),
    HIKE("Hike");

    /** True for foot sports where pace (min/km) is the natural metric instead of speed. */
    val usesPace: Boolean get() = this != RIDE

    companion object {
        fun fromName(name: String): ActivityType =
            entries.firstOrNull { it.name == name } ?: RUN
    }
}

/** A single recorded GPS fix. [segment] increments after each pause so the route can be drawn as separate polylines. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timeMillis: Long,
    val speedMps: Float,
    val segment: Int,
)

data class Split(
    val index: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val elevationDelta: Double,
) {
    val paceSecondsPerKm: Double
        get() = if (distanceMeters > 0) durationMillis / 1000.0 / (distanceMeters / 1000.0) else 0.0
}

/** Fastest time over a classic benchmark distance within one activity. */
data class BestEffort(
    val label: String,
    val distanceMeters: Double,
    val durationMillis: Long,
)

data class UserSettings(
    val userName: String = "Athlete",
    val imperialUnits: Boolean = false,
    val weightKg: Float = 70f,
    val autoPauseEnabled: Boolean = true,
    val weeklyGoalKm: Float = 25f,
    val maxHeartRate: Int = 190,
)

data class HeartRateZone(
    val index: Int,
    val name: String,
    val fromBpm: Int,
    val toBpm: Int,
)

fun heartRateZones(maxHr: Int): List<HeartRateZone> {
    val bounds = listOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.0)
    val names = listOf("Recovery", "Endurance", "Tempo", "Threshold", "VO2 Max")
    return names.mapIndexed { i, name ->
        HeartRateZone(
            index = i + 1,
            name = name,
            fromBpm = (bounds[i] * maxHr).toInt(),
            toBpm = (bounds[i + 1] * maxHr).toInt(),
        )
    }
}
