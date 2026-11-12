package com.ostrava.app.di

import android.content.Context
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.DraftRepository
import com.ostrava.app.data.RecordingRecovery
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.AppDatabase

class AppContainer(context: Context) {
    val activityRepository: ActivityRepository by lazy {
        ActivityRepository(AppDatabase.getInstance(context).activityDao())
    }
    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context)
    }
    val draftRepository: DraftRepository by lazy {
        DraftRepository(AppDatabase.getInstance(context).draftDao())
    }
    val recordingRecovery: RecordingRecovery by lazy {
        RecordingRecovery(draftRepository, activityRepository, settingsRepository)
    }
}
