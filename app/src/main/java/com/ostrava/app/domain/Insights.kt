package com.ostrava.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What tapping a tip should do. */
enum class InsightAction(val label: String?) {
    RECORD("Start recording"),
    STATS("See your stats"),
    BACKUP("Back up now"),
    NONE(null),
}

/**
 * A friendly, data-driven nudge shown on the feed and, when [remindable], eligible to be
 * sent as a notification.
 */
data class Insight(
    /** Stable identifier of the kind of tip, e.g. "streak-risk". */
    val id: String,
    val emoji: String,
    val title: String,
    val body: String,
    val priority: Int,
    val action: InsightAction = InsightAction.NONE,
    val remindable: Boolean = false,
)

data class InsightActivity(
    val type: ActivityType,
    val startTime: Long,
    val movingTimeMillis: Long,
    val distanceMeters: Double,
)

data class InsightEffort(
    val type: ActivityType,
    val label: String,
    val durationMillis: Long,
    val achievedAt: Long,
)

data class InsightInput(
    val activities: List<InsightActivity>,
    val efforts: List<InsightEffort>,
    val weeklyGoalKm: Float,
    val imperial: Boolean,
    /** Epoch millis of the last backup, 0 if never. */
    val lastBackupAt: Long,
    val now: ZonedDateTime,
)

/**
 * Turns the athlete's history into a short, prioritised list of tips. Pure Kotlin with an
 * injected clock, so every rule can be unit-tested.
 */
object InsightEngine {

    fun generate(input: InsightInput): List<Insight> {
        val today = input.now.toLocalDate()
        val zone = input.now.zone
        fun dateOf(activity: InsightActivity): LocalDate =
            Instant.ofEpochMilli(activity.startTime).atZone(zone).toLocalDate()

        val insights = mutableListOf<Insight>()
        val activities = input.activities

        if (activities.isEmpty()) {
            insights += Insight(
                id = "welcome",
                emoji = "👋",
                title = "Ready for your first one?",
                body = "Head outside, tap Record and go. A ten-minute walk is a perfectly good start.",
                priority = 100,
                action = InsightAction.RECORD,
            )
            insights += featureTip(today)
            return insights.sortedByDescending { it.priority }
        }

        val weekStart = today.with(DayOfWeek.MONDAY)
        val dates = activities.map(::dateOf)
        val thisWeek = activities.filter { !dateOf(it).isBefore(weekStart) }
        val weekMeters = thisWeek.sumOf { it.distanceMeters }
        val lastDate = dates.max()
        val daysSinceLast = ChronoUnit.DAYS.between(lastDate, today).toInt()
        val activeToday = lastDate == today
        val day = today.dayOfWeek

        // Weekly goal
        val goalMeters = input.weeklyGoalKm * 1000.0
        if (goalMeters > 0) {
            val remaining = goalMeters - weekMeters
            val covered = formatDistanceShort(weekMeters, input.imperial)
            val goal = formatDistanceShort(goalMeters, input.imperial)
            if (remaining <= 0) {
                insights += Insight(
                    id = "goal-done",
                    emoji = "🎉",
                    title = "Weekly goal reached",
                    body = "You've covered $covered of your $goal goal. Anything more this week is a bonus.",
                    priority = 60,
                    action = InsightAction.STATS,
                )
            } else if (weekMeters >= goalMeters * 0.5 || day >= DayOfWeek.THURSDAY) {
                val lateInWeek = day >= DayOfWeek.FRIDAY
                insights += Insight(
                    id = "goal-close",
                    emoji = "🎯",
                    title = "${formatDistanceShort(remaining, input.imperial)} to go this week",
                    body = "You're at $covered of $goal. " +
                        if (lateInWeek) "One more outing before Sunday gets you there."
                        else "You're on track — keep it rolling.",
                    priority = if (lateInWeek) 80 else 65,
                    action = InsightAction.RECORD,
                    remindable = day >= DayOfWeek.THURSDAY && !activeToday,
                )
            }
        }

        // Streaks
        val activeWeeks = dates.map { weekIndex(it) }.toSet()
        val (currentStreak, longestStreak) = weeklyStreaks(activeWeeks, weekIndex(today))
        if (currentStreak >= 2 && thisWeek.isEmpty() && day >= DayOfWeek.WEDNESDAY) {
            insights += Insight(
                id = "streak-risk",
                emoji = "🔥",
                title = "Keep your $currentStreak-week streak alive",
                body = "Nothing logged yet this week. Anything before Sunday night counts — even a short one.",
                priority = 90,
                action = InsightAction.RECORD,
                remindable = true,
            )
        } else if (thisWeek.isNotEmpty() && currentStreak >= 3 && currentStreak == longestStreak) {
            insights += Insight(
                id = "streak-best",
                emoji = "🔥",
                title = "$currentStreak weeks in a row",
                body = "That's your longest streak yet. Consistency beats intensity every time.",
                priority = 40,
                action = InsightAction.STATS,
            )
        }

        // Coming back after a break
        if (daysSinceLast >= 7) {
            insights += Insight(
                id = "comeback",
                emoji = "🌱",
                title = "It's been $daysSinceLast days",
                body = "No pressure. A short, easy one is the best way back — fifteen minutes is plenty.",
                priority = 85,
                action = InsightAction.RECORD,
                remindable = true,
            )
        }

        // Training load
        val minutesByWeek = activities.groupBy { weekIndex(dateOf(it)) }
            .mapValues { (_, list) -> list.sumOf { it.movingTimeMillis } / 60_000L }
        val currentWeek = weekIndex(today)
        val thisWeekMinutes = minutesByWeek[currentWeek] ?: 0L
        val recentAverage = (1..4).sumOf { minutesByWeek[currentWeek - it] ?: 0L } / 4
        if (recentAverage >= 30 && thisWeekMinutes > recentAverage * 1.5) {
            insights += Insight(
                id = "ramp",
                emoji = "🧘",
                title = "Big week — go easy",
                body = "You've done $thisWeekMinutes min, well above your recent $recentAverage min a week. " +
                    "An easy or rest day now lowers injury risk.",
                priority = 75,
            )
        }

        // Several days in a row
        val activeDays = dates.toSet()
        var consecutive = 0
        var cursor = if (activeToday) today else today.minusDays(1)
        while (cursor in activeDays) {
            consecutive++
            cursor = cursor.minusDays(1)
        }
        if (consecutive >= 4) {
            insights += Insight(
                id = "rest-day",
                emoji = "😴",
                title = "$consecutive days in a row",
                body = "Nice run of form. Rest is when the fitness actually builds, so a day off won't cost you anything.",
                priority = 70,
            )
        }

        // The athlete's usual day
        if (!activeToday && daysSinceLast < 7) {
            val weeksOnThisDay = (1..8).count { weeksAgo -> today.minusWeeks(weeksAgo.toLong()) in activeDays }
            if (weeksOnThisDay >= 3) {
                val dayName = day.getDisplayName(TextStyle.FULL, Locale.US)
                insights += Insight(
                    id = "usual-day",
                    emoji = "📅",
                    title = "$dayName is usually your day",
                    body = "You've been active on $weeksOnThisDay of the last 8 ${dayName}s. Fancy keeping that going?",
                    priority = 55,
                    action = InsightAction.RECORD,
                    remindable = true,
                )
            }
        }

        // Close to a personal record
        nearRecord(input, today)?.let { insights += it }

        // Backup
        val daysSinceBackup = if (input.lastBackupAt == 0L) Long.MAX_VALUE
        else ChronoUnit.DAYS.between(Instant.ofEpochMilli(input.lastBackupAt), input.now.toInstant())
        if (activities.size >= 5 && daysSinceBackup > 30) {
            insights += Insight(
                id = "backup",
                emoji = "💾",
                title = "Back up your ${activities.size} activities",
                body = "Your history lives only on this phone. A backup takes a few seconds and survives a lost or reset device.",
                priority = 45,
                action = InsightAction.BACKUP,
                remindable = true,
            )
        }

        insights += featureTip(today)
        return insights.sortedByDescending { it.priority }
    }

    private fun nearRecord(input: InsightInput, today: LocalDate): Insight? {
        val zone = input.now.zone
        val recentCutoff = input.now.minusDays(14).toInstant().toEpochMilli()
        var closest: Triple<InsightEffort, Long, Long>? = null // effort, record, gap
        input.efforts.groupBy { it.type to it.label }.values.forEach { sameDistance ->
            val record = sameDistance.minOf { it.durationMillis }
            sameDistance
                .filter { it.achievedAt >= recentCutoff && it.durationMillis > record }
                .forEach { effort ->
                    val gap = effort.durationMillis - record
                    if (effort.durationMillis <= record * 1.03 && (closest == null || gap < closest!!.third)) {
                        closest = Triple(effort, record, gap)
                    }
                }
        }
        val (effort, record, gap) = closest ?: return null
        val date = Instant.ofEpochMilli(effort.achievedAt).atZone(zone).toLocalDate()
        val dayText = if (date == today) "today" else "on ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}"
        return Insight(
            id = "pr-close",
            emoji = "⚡",
            title = "So close to a record",
            body = "Your ${effort.label} $dayText was only ${formatDuration(gap.coerceAtLeast(1000))} off " +
                "your best of ${formatDuration(record)}. It's there for the taking.",
            priority = 50,
            action = InsightAction.STATS,
        )
    }

    /** A rotating how-to tip so there is always something useful on the feed. */
    private fun featureTip(today: LocalDate): Insight {
        val (emoji, title, body) = FEATURE_TIPS[today.dayOfYear % FEATURE_TIPS.size]
        return Insight(
            id = "tip-${today.dayOfYear % FEATURE_TIPS.size}",
            emoji = emoji,
            title = title,
            body = body,
            priority = 10,
        )
    }

    /** Monday-based week number, continuous across years. */
    private fun weekIndex(date: LocalDate): Long = Math.floorDiv(date.with(DayOfWeek.MONDAY).toEpochDay(), 7L)

    private val FEATURE_TIPS = listOf(
        Triple("⏱️", "Try an interval session", "On the Record tab, tap Intervals to build a warm-up, repeats and cool-down. Ostrava calls out each phase so you never look at the screen."),
        Triple("🚩", "Race your own routes", "Open any activity and choose Make segment. Every later activity over that stretch gets timed against your best."),
        Triple("❤️", "Add your heart rate", "Tap the heart on the Record screen to connect a Bluetooth strap. You'll get time in each zone after the activity."),
        Triple("📸", "Share with your own photo", "On an activity, tap Share and pick the Photo style to put your stats over a picture from the day."),
        Triple("🔊", "Eyes up, ears on", "Voice cues read out your time and pace at every kilometre. Turn them on or off in Profile."),
        Triple("⏸️", "Stopped at a light?", "Auto-pause stops the clock when you stop, so waiting at crossings doesn't hurt your pace."),
        Triple("✂️", "Forgot to stop the clock?", "Open the activity and choose Trim to cut the drive home off the end."),
        Triple("📥", "Bring your history along", "Import GPX files from other apps in Profile to see all your activities and records in one place."),
        Triple("🗺️", "Put your route on a map", "The Map style in Share draws your route over a real map, ready to post."),
    )
}
