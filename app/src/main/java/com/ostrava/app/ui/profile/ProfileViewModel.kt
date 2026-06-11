package com.ostrava.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.domain.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun setUserName(value: String) = viewModelScope.launch { settingsRepository.setUserName(value) }

    fun setImperialUnits(value: Boolean) =
        viewModelScope.launch { settingsRepository.setImperialUnits(value) }

    fun setWeightKg(value: Float) = viewModelScope.launch { settingsRepository.setWeightKg(value) }

    fun setAutoPause(value: Boolean) = viewModelScope.launch { settingsRepository.setAutoPause(value) }

    fun setWeeklyGoalKm(value: Float) =
        viewModelScope.launch { settingsRepository.setWeeklyGoalKm(value) }

    fun setMaxHeartRate(value: Int) =
        viewModelScope.launch { settingsRepository.setMaxHeartRate(value) }
}
