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
    val audioCuesEnabled: Boolean = true,
    val keepScreenOn: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
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

/** Perceived-effort rating stored on an activity. */
object Feel {
    const val EASY = "EASY"
    const val MODERATE = "MODERATE"
    const val HARD = "HARD"

    val options: List<Pair<String, String>> = listOf(
        EASY to "😌 Easy",
        MODERATE to "😤 Moderate",
        HARD to "🥵 Hard",
    )

    fun label(value: String?): String? = options.firstOrNull { it.first == value }?.second
}

/** Structured interval workout: warmup, repeats of work/rest, cooldown (all seconds). */
data class IntervalConfig(
    val warmupSec: Int,
    val workSec: Int,
    val restSec: Int,
    val repeats: Int,
    val cooldownSec: Int,
) {
    /** Ordered (name, durationSec) phases; rest is skipped after the final work bout. */
    fun phases(): List<Pair<String, Int>> = buildList {
        if (warmupSec > 0) add("Warm-up" to warmupSec)
        for (i in 1..repeats) {
            add("Work $i/$repeats" to workSec)
            if (restSec > 0 && i < repeats) add("Rest $i/$repeats" to restSec)
        }
        if (cooldownSec > 0) add("Cool-down" to cooldownSec)
    }

    fun summary(): String {
        val work = formatDuration(workSec * 1000L)
        return if (restSec > 0) "$repeats × $work / ${formatDuration(restSec * 1000L)}"
        else "$repeats × $work"
    }

    fun toIntArray(): IntArray = intArrayOf(warmupSec, workSec, restSec, repeats, cooldownSec)

    companion object {
        fun fromIntArray(values: IntArray?): IntervalConfig? {
            if (values == null || values.size != 5) return null
            val config = IntervalConfig(values[0], values[1], values[2], values[3], values[4])
            return if (config.workSec > 0 && config.repeats > 0) config else null
        }
    }
}

/** Live interval state surfaced on the record screen. */
data class IntervalPhase(
    val name: String,
    val index: Int,
    val total: Int,
    val remainingSec: Int,
)
