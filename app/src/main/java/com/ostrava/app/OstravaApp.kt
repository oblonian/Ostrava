package com.ostrava.app

import android.app.Application
import com.ostrava.app.data.InsightSource
import com.ostrava.app.data.db.ActivityEntity
import com.ostrava.app.di.AppContainer
import com.ostrava.app.health.HealthConnectSync
import com.ostrava.app.notify.Notifications
import com.ostrava.app.notify.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File
import java.time.ZonedDateTime

class OstravaApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        Notifications.createChannels(this)

        // Activities recorded before personal records were cached get their efforts computed once.
        appScope.launch {
            runCatching { container.activityRepository.backfillBestEfforts() }
        }
        appScope.launch { keepRemindersScheduled() }
    }

    /** Reschedules the daily reminder check whenever the reminder settings change. */
    private suspend fun keepRemindersScheduled() {
        container.settingsRepository.settings
            .map { it.remindersEnabled to it.reminderSlot }
            .distinctUntilChanged()
            .collect { (enabled, slot) ->
                runCatching {
                    val startHours = InsightSource.startHours(
                        container.activityRepository.getActivitiesList(), ZonedDateTime.now(),
                    )
                    ReminderScheduler.sync(this, enabled, ReminderScheduler.hourFor(slot, startHours))
                }
            }
    }

    /** Called after an activity is stored; runs follow-ups that must outlive the screen or service. */
    fun onActivitySaved(activity: ActivityEntity) {
        appScope.launch {
            val settings = container.settingsRepository.settings.first()
            if (settings.healthConnectEnabled) {
                HealthConnectSync.write(this@OstravaApp, activity)
            }
        }
    }

    companion object {
        const val TRACKING_CHANNEL_ID = Notifications.CHANNEL_TRACKING
    }
}
