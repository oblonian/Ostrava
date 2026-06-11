package com.ostrava.app.di

import android.content.Context
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.AppDatabase

class AppContainer(context: Context) {
    val activityRepository: ActivityRepository by lazy {
        ActivityRepository(AppDatabase.getInstance(context).activityDao())
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }
}
