package com.ostrava.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.tracking.RecordingState
import com.ostrava.app.tracking.TrackingStateHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class RecordViewModel(settingsRepository: SettingsRepository) : ViewModel() {

    val recordingState: StateFlow<RecordingState> = TrackingStateHolder.state

    val lastSavedActivityId: StateFlow<Long?> = TrackingStateHolder.lastSavedActivityId

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun consumeSavedActivityId() {
        TrackingStateHolder.lastSavedActivityId.value = null
    }
}
