package com.ostrava.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val METERS_PER_MILE = 1609.344
const val FEET_PER_METER = 3.28084

fun formatDistance(meters: Double, imperial: Boolean): String =
    if (imperial) String.format(Locale.US, "%.2f mi", meters / METERS_PER_MILE)
    else String.format(Locale.US, "%.2f km", meters / 1000.0)

fun formatDistanceShort(meters: Double, imperial: Boolean): String =
    if (imperial) String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE)
    else String.format(Locale.US, "%.1f km", meters / 1000.0)

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Pace from speed, e.g. "5:30 /km" or "8:51 /mi". */
fun formatPace(speedMps: Double, imperial: Boolean): String {
    if (speedMps < 0.2) return "--:--"
    val secondsPerUnit = if (imperial) METERS_PER_MILE / speedMps else 1000.0 / speedMps
    return formatPaceSeconds(secondsPerUnit, imperial)
}

fun formatPaceSeconds(secondsPerUnit: Double, imperial: Boolean): String {
    if (secondsPerUnit <= 0 || secondsPerUnit > 5400) return "--:--"
    val minutes = (secondsPerUnit / 60).toInt()
    val seconds = (secondsPerUnit % 60).toInt()
    return String.format(Locale.US, "%d:%02d /%s", minutes, seconds, if (imperial) "mi" else "km")
}

fun formatSpeed(speedMps: Double, imperial: Boolean): String =
    if (imperial) String.format(Locale.US, "%.1f mph", speedMps * 3600 / METERS_PER_MILE)
    else String.format(Locale.US, "%.1f km/h", speedMps * 3.6)

fun formatElevation(meters: Double, imperial: Boolean): String =
    if (imperial) String.format(Locale.US, "%.0f ft", meters * FEET_PER_METER)
    else String.format(Locale.US, "%.0f m", meters)

/** Duration phrased for text-to-speech, e.g. "1 hour 5 minutes 20 seconds". */
fun spokenDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append("$hours hour${if (hours != 1L) "s" else ""} ")
        if (minutes > 0) append("$minutes minute${if (minutes != 1L) "s" else ""} ")
        if (seconds > 0 || (hours == 0L && minutes == 0L)) {
            append("$seconds second${if (seconds != 1L) "s" else ""}")
        }
    }.trim()
}

private val dateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm", Locale.US)
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

fun formatDateTime(epochMillis: Long): String =
    dateTimeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun formatDate(epochMillis: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** Default title like "Morning Run" based on start hour. */
fun defaultActivityTitle(type: ActivityType, startMillis: Long): String {
    val hour = Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).hour
    val period = when (hour) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        else -> "Night"
    }
    return "$period ${type.label}"
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val shortDayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/** "Today · 07:12", "Yesterday · 18:30" or "Mon 9 Nov · 07:12" (with the year when it isn't this one). */
fun formatRelativeDay(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val date = moment.toLocalDate()
    val day = when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> shortDayFormatter.format(moment)
        else -> dateFormatter.format(moment)
    }
    return "$day · ${timeFormatter.format(moment)}"
}

/** "Good morning", "Good afternoon" or "Good evening" for the given hour of the day. */
fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

fun formatWeight(kg: Float, imperial: Boolean): String =
    if (imperial) String.format(Locale.US, "%.0f lb", kg * POUNDS_PER_KG)
    else String.format(Locale.US, "%.0f kg", kg)

const val POUNDS_PER_KG = 2.20462f
