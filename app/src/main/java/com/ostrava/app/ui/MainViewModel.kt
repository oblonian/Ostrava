package com.ostrava.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.DraftRepository
import com.ostrava.app.data.RecordingRecovery
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.data.db.RecordingDraftEntity
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.tracking.TrackingStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-level state: the onboarding gate and recovery of an interrupted recording. */
class MainViewModel(
    private val settingsRepository: SettingsRepository,
    draftRepository: DraftRepository,
    private val recovery: RecordingRecovery,
) : ViewModel() {

    /** Null until settings load, so onboarding never flashes for existing users. */
    val settings: StateFlow<UserSettings?> = settingsRepository.settings
        .map { it as UserSettings? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * A recording that was cut short (crash, or the phone killed the app) and is waiting
     * to be resumed or saved. Null while a recording is running normally.
     */
    val interruptedRecording: StateFlow<RecordingDraftEntity?> =
        combine(draftRepository.observeDraft(), TrackingStateHolder.state) { draft, recording ->
            draft.takeIf { !recording.isActive }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingDone() }
    }

    /** Saves the interrupted recording as it stands; [onSaved] gets the new activity's id. */
    fun saveInterruptedRecording(onSaved: (Long) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { runCatching { recovery.saveDraft() }.getOrNull() }
            if (id != null) onSaved(id)
        }
    }

    fun discardInterruptedRecording() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { recovery.discardDraft() } }
    }
}
