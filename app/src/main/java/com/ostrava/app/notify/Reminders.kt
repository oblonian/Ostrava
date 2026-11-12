package com.ostrava.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ostrava.app.OstravaApp
import com.ostrava.app.data.InsightSource
import com.ostrava.app.domain.InsightEngine
import com.ostrava.app.domain.ReminderPlanner
import com.ostrava.app.domain.ReminderSlot
import com.ostrava.app.tracking.TrackingStateHolder
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Runs once a day and posts at most one reminder, chosen from the athlete's own data. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? OstravaApp ?: return Result.success()
        val settingsRepository = app.container.settingsRepository
        val settings = settingsRepository.settings.first()
        if (!settings.remindersEnabled || TrackingStateHolder.state.value.isActive) return Result.success()

        val now = ZonedDateTime.now()
        val input = InsightSource.load(app.container.activityRepository, settings, now)
        val (lastId, lastAt) = settingsRepository.lastReminder()
        val reminder = ReminderPlanner.pick(InsightEngine.generate(input), lastId, lastAt, now)
            ?: return Result.success()

        Notifications.showReminder(applicationContext, reminder)
        settingsRepository.setLastReminder(reminder.id, now.toInstant().toEpochMilli())
        return Result.success()
    }
}

object ReminderScheduler {

    private const val WORK_NAME = "smart-reminders"

    /** (Re)schedules the daily check for [hour] o'clock, or cancels it when reminders are off. */
    fun sync(context: Context, enabled: Boolean, hour: Int) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = ZonedDateTime.now()
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
    }

    fun hourFor(slot: ReminderSlot, activityStartHours: List<Int>): Int =
        ReminderPlanner.preferredHour(slot, activityStartHours)
}
