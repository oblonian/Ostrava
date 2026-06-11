package com.ostrava.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ostrava.app.domain.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val USER_NAME = stringPreferencesKey("user_name")
        val IMPERIAL_UNITS = booleanPreferencesKey("imperial_units")
        val WEIGHT_KG = floatPreferencesKey("weight_kg")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause")
        val WEEKLY_GOAL_KM = floatPreferencesKey("weekly_goal_km")
        val MAX_HEART_RATE = intPreferencesKey("max_heart_rate")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            userName = prefs[Keys.USER_NAME] ?: "Athlete",
            imperialUnits = prefs[Keys.IMPERIAL_UNITS] ?: false,
            weightKg = prefs[Keys.WEIGHT_KG] ?: 70f,
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            weeklyGoalKm = prefs[Keys.WEEKLY_GOAL_KM] ?: 25f,
            maxHeartRate = prefs[Keys.MAX_HEART_RATE] ?: 190,
        )
    }

    suspend fun setUserName(value: String) =
        context.dataStore.edit { it[Keys.USER_NAME] = value }

    suspend fun setImperialUnits(value: Boolean) =
        context.dataStore.edit { it[Keys.IMPERIAL_UNITS] = value }

    suspend fun setWeightKg(value: Float) =
        context.dataStore.edit { it[Keys.WEIGHT_KG] = value }

    suspend fun setAutoPause(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_PAUSE] = value }

    suspend fun setWeeklyGoalKm(value: Float) =
        context.dataStore.edit { it[Keys.WEEKLY_GOAL_KM] = value }

    suspend fun setMaxHeartRate(value: Int) =
        context.dataStore.edit { it[Keys.MAX_HEART_RATE] = value }
}
