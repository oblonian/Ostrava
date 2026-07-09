package com.ostrava.app.ui.profile

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ostrava.app.data.ActivityRepository
import com.ostrava.app.data.SettingsRepository
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.export.BackupManager
import com.ostrava.app.export.GpxImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileViewModel(
    private val settingsRepository: SettingsRepository,
    private val repository: ActivityRepository,
) : ViewModel() {

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

    fun setAudioCues(value: Boolean) = viewModelScope.launch { settingsRepository.setAudioCues(value) }

    fun setKeepScreenOn(value: Boolean) =
        viewModelScope.launch { settingsRepository.setKeepScreenOn(value) }

    fun setHaptics(value: Boolean) = viewModelScope.launch { settingsRepository.setHaptics(value) }

    fun backup(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { BackupManager.export(context, repository, uri) }
            }
            toast(
                context,
                result.fold(
                    onSuccess = { "Backed up $it activities" },
                    onFailure = { "Backup failed: ${it.message}" },
                ),
            )
        }
    }

    fun restore(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { BackupManager.import(context, repository, uri) }
            }
            toast(
                context,
                result.fold(
                    onSuccess = { "Restored $it activities" },
                    onFailure = { "Restore failed: ${it.message}" },
                ),
            )
        }
    }

    fun importGpx(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val weightKg = settingsRepository.settings.first().weightKg
                    GpxImporter.import(context, repository, uri, weightKg)
                }
            }
            toast(
                context,
                result.fold(
                    onSuccess = { id ->
                        if (id != null) "GPX imported" else "Nothing to import (empty or duplicate file)"
                    },
                    onFailure = { "Import failed: ${it.message}" },
                ),
            )
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
