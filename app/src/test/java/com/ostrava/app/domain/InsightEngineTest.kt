package com.ostrava.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class InsightEngineTest {

    private val zone = ZoneId.of("Europe/Prague")

    /** Thursday 12 November 2026, 17:00. */
    private val now = ZonedDateTime.of(2026, 11, 12, 17, 0, 0, 0, zone)

    private fun activity(daysAgo: Long, km: Double = 5.0, minutes: Long = 30, hour: Int = 7) = InsightActivity(
        type = ActivityType.RUN,
        startTime = now.minusDays(daysAgo).withHour(hour).toInstant().toEpochMilli(),
        movingTimeMillis = minutes * 60_000,
        distanceMeters = km * 1000,
    )

    private fun generate(
        activities: List<InsightActivity>,
        efforts: List<InsightEffort> = emptyList(),
        goalKm: Float = 25f,
        lastBackupAt: Long = now.minusDays(1).toInstant().toEpochMilli(),
    ) = InsightEngine.generate(InsightInput(activities, efforts, goalKm, false, lastBackupAt, now))

    private fun List<Insight>.ids() = map { it.id }

    @Test
    fun `a new athlete is welcomed and always gets a tip`() {
        val insights = generate(emptyList())
        assertEquals("welcome", insights.first().id)
        assertTrue(insights.any { it.id.startsWith("tip-") })
    }

    @Test
    fun `a streak about to lapse is the top priority and can be sent as a reminder`() {
        // Active in each of the last three weeks, nothing since Monday.
        val insights = generate(listOf(activity(6), activity(13), activity(20)))
        val top = insights.first()
        assertEquals("streak-risk", top.id)
        assertTrue(top.remindable)
        assertTrue(top.title.contains("3-week"))
    }

    @Test
    fun `goal progress is shown with the distance left`() {
        // Tuesday and Wednesday of this week: 18 km of a 25 km goal.
        val insights = generate(listOf(activity(2, km = 9.0), activity(1, km = 9.0)))
        val goal = insights.first { it.id == "goal-close" }
        assertTrue(goal.title.startsWith("7.0 km"))
        assertFalse(insights.ids().contains("streak-risk"))
    }

    @Test
    fun `reaching the goal congratulates instead of nagging`() {
        val insights = generate(listOf(activity(2, km = 15.0), activity(1, km = 12.0)))
        assertTrue(insights.ids().contains("goal-done"))
        assertFalse(insights.ids().contains("goal-close"))
    }

    @Test
    fun `a long break gets a gentle comeback nudge`() {
        val insights = generate(listOf(activity(12)))
        val comeback = insights.first { it.id == "comeback" }
        assertTrue(comeback.title.contains("12 days"))
        assertTrue(comeback.remindable)
    }

    @Test
    fun `a sudden jump in training load suggests an easy day`() {
        val history = (1..4).map { activity(it * 7L + 1, minutes = 60) }
        val bigWeek = listOf(activity(3, minutes = 80), activity(2, minutes = 80), activity(1, minutes = 80))
        assertTrue(generate(history + bigWeek).ids().contains("ramp"))
        assertFalse(generate(history + activity(1, minutes = 60)).ids().contains("ramp"))
    }

    @Test
    fun `several days in a row suggests rest`() {
        val insights = generate((0L..4L).map { activity(it) })
        assertTrue(insights.first { it.id == "rest-day" }.title.startsWith("5 days"))
    }

    @Test
    fun `the athlete's usual weekday is recognised`() {
        // The last four Thursdays, plus something earlier this week so it isn't a comeback.
        val insights = generate(listOf(activity(7), activity(14), activity(21), activity(28), activity(2)))
        val usual = insights.first { it.id == "usual-day" }
        assertTrue(usual.title.startsWith("Thursday"))
        // Already out today: no need to suggest it.
        assertFalse(generate(listOf(activity(7), activity(14), activity(21), activity(0))).ids().contains("usual-day"))
    }

    @Test
    fun `a near miss on a record is pointed out`() {
        fun effort(daysAgo: Long, seconds: Long) = InsightEffort(
            ActivityType.RUN, "5 km", seconds * 1000, now.minusDays(daysAgo).toInstant().toEpochMilli(),
        )
        val activities = listOf(activity(40), activity(2))
        val near = generate(activities, listOf(effort(40, 1500), effort(2, 1510)))
        assertTrue(near.first { it.id == "pr-close" }.body.contains("0:10"))
        // 5% off is not close enough to mention.
        assertFalse(generate(activities, listOf(effort(40, 1500), effort(2, 1580))).ids().contains("pr-close"))
    }

    @Test
    fun `backups are suggested once there is something to lose`() {
        val five = (1L..5L).map { activity(it) }
        assertTrue(generate(five, lastBackupAt = 0L).ids().contains("backup"))
        assertFalse(generate(five).ids().contains("backup"))
        assertFalse(generate(five.take(2), lastBackupAt = 0L).ids().contains("backup"))
    }

    @Test
    fun `insights come out highest priority first`() {
        val insights = generate(listOf(activity(6), activity(13), activity(20)), lastBackupAt = 0L)
        assertEquals(insights.map { it.priority }.sortedDescending(), insights.map { it.priority })
    }

    @Test
    fun `at most one reminder a day and no immediate repeats`() {
        val streak = Insight("streak-risk", "🔥", "t", "b", 90, remindable = true)
        val backup = Insight("backup", "💾", "t", "b", 45, remindable = true)
        val tip = Insight("tip-1", "💡", "t", "b", 10)
        val nowMillis = now.toInstant().toEpochMilli()
        val hour = 3_600_000L

        assertEquals(streak, ReminderPlanner.pick(listOf(tip, backup, streak), null, 0L, now))
        assertNull(ReminderPlanner.pick(listOf(tip), null, 0L, now))
        // Reminded five hours ago: stay quiet.
        assertNull(ReminderPlanner.pick(listOf(streak), "backup", nowMillis - 5 * hour, now))
        // The same nudge yesterday is not repeated today; the next most useful one is sent.
        assertEquals(backup, ReminderPlanner.pick(listOf(streak, backup), "streak-risk", nowMillis - 24 * hour, now))
        assertNotNull(ReminderPlanner.pick(listOf(streak), "streak-risk", nowMillis - 72 * hour, now))
    }

    @Test
    fun `smart reminder time follows the athlete's habits`() {
        assertEquals(17, ReminderPlanner.preferredHour(ReminderSlot.AUTO, emptyList()))
        assertEquals(6, ReminderPlanner.preferredHour(ReminderSlot.AUTO, listOf(7, 7, 6, 8, 7)))
        assertEquals(17, ReminderPlanner.preferredHour(ReminderSlot.AUTO, listOf(18, 18, 19, 17)))
        assertEquals(12, ReminderPlanner.preferredHour(ReminderSlot.MIDDAY, listOf(7, 7, 7)))
    }
}
