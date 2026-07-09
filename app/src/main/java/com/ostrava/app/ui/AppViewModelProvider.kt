package com.ostrava.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ostrava.app.OstravaApp
import com.ostrava.app.ui.detail.ActivityDetailViewModel
import com.ostrava.app.ui.feed.FeedViewModel
import com.ostrava.app.ui.profile.ProfileViewModel
import com.ostrava.app.ui.record.RecordViewModel
import com.ostrava.app.ui.stats.StatsViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer {
            MainViewModel(app().container.settingsRepository)
        }
        initializer {
            FeedViewModel(app().container.activityRepository, app().container.settingsRepository)
        }
        initializer {
            RecordViewModel(app().container.settingsRepository)
        }
        initializer {
            StatsViewModel(app().container.activityRepository, app().container.settingsRepository)
        }
        initializer {
            ProfileViewModel(app().container.settingsRepository, app().container.activityRepository)
        }
        initializer {
            ActivityDetailViewModel(
                savedStateHandle = createSavedStateHandle(),
                repository = app().container.activityRepository,
                settingsRepository = app().container.settingsRepository,
            )
        }
    }
}

private fun CreationExtras.app(): OstravaApp =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OstravaApp
