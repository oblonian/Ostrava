package com.ostrava.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.ostrava.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

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

        createNotificationChannel()

        // Activities recorded before personal records were cached get their efforts computed once.
        appScope.launch {
            runCatching { container.activityRepository.backfillBestEfforts() }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracking_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"
    }
}
