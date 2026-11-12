package com.ostrava.app.domain

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Decides whether today's reminder check should notify, and about what. */
object ReminderPlanner {

    /**
     * The tip to send as a notification, or null to stay quiet. At most one reminder a day,
     * and the same kind of reminder is not repeated until its cool-down has passed.
     */
    fun pick(
        insights: List<Insight>,
        lastReminderId: String?,
        lastReminderAt: Long,
        now: ZonedDateTime,
    ): Insight? {
        val sinceLast = if (lastReminderAt == 0L) null
        else ChronoUnit.HOURS.between(Instant.ofEpochMilli(lastReminderAt), now.toInstant())
        if (sinceLast != null && sinceLast < MIN_HOURS_BETWEEN) return null
        return insights
            .filter { it.remindable }
            .sortedByDescending { it.priority }
            .firstOrNull { candidate ->
                candidate.id != lastReminderId || sinceLast == null || sinceLast >= cooldownDays(candidate.id) * 24L
            }
    }

    private fun cooldownDays(id: String): Int = when (id) {
        "comeback" -> 4
        "backup" -> 30
        "usual-day" -> 6
        else -> 2
    }

    /**
     * The hour of day to run the check. With [ReminderSlot.AUTO] it is an hour before the
     * athlete's typical start time, so the nudge arrives when they might actually go.
     */
    fun preferredHour(slot: ReminderSlot, activityStartHours: List<Int>): Int {
        slot.hour?.let { return it }
        if (activityStartHours.size < 3) return DEFAULT_HOUR
        val median = activityStartHours.sorted()[activityStartHours.size / 2]
        return (median - 1).coerceIn(6, 20)
    }

    private const val MIN_HOURS_BETWEEN = 20L
    private const val DEFAULT_HOUR = 17
}
