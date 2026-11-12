package com.ostrava.app.data

import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.data.db.EffortWithActivity
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.InsightActivity
import com.ostrava.app.domain.InsightEffort
import com.ostrava.app.domain.InsightInput
import com.ostrava.app.domain.UserSettings
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZonedDateTime

/** Adapts stored activities and settings to the plain inputs the tips engine works on. */
object InsightSource {

    fun input(
        activities: List<ActivityEntity>,
        efforts: List<EffortWithActivity>,
        settings: UserSettings,
        now: ZonedDateTime,
    ) = InsightInput(
        activities = activities.map {
            InsightActivity(
                type = ActivityType.fromName(it.type),
                startTime = it.startTime,
                movingTimeMillis = it.movingTimeMillis,
                distanceMeters = it.distanceMeters,
            )
        },
        efforts = efforts.map {
            InsightEffort(
                type = ActivityType.fromName(it.activityType),
                label = it.label,
                durationMillis = it.durationMillis,
                achievedAt = it.achievedAt,
            )
        },
        weeklyGoalKm = settings.weeklyGoalKm,
        imperial = settings.imperialUnits,
        lastBackupAt = settings.lastBackupAt,
        now = now,
    )

    suspend fun load(repository: ActivityRepository, settings: UserSettings, now: ZonedDateTime): InsightInput =
        input(repository.getActivitiesList(), repository.observeAllBestEfforts().first(), settings, now)

    /** Local start hour of each recent activity, for timing reminders. */
    fun startHours(activities: List<ActivityEntity>, now: ZonedDateTime): List<Int> =
        activities.take(20).map { Instant.ofEpochMilli(it.startTime).atZone(now.zone).hour }
}
