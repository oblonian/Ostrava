package com.ostrava.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ostrava.app.domain.ReminderSlot
import com.ostrava.app.domain.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val AUDIO_CUES = booleanPreferencesKey("audio_cues")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val HAPTICS = booleanPreferencesKey("haptics")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val REMINDERS = booleanPreferencesKey("reminders")
        val REMINDER_SLOT = stringPreferencesKey("reminder_slot")
        val HEALTH_CONNECT = booleanPreferencesKey("health_connect")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val DISMISSED_INSIGHTS = stringSetPreferencesKey("dismissed_insights")
        val BATTERY_PROMPT_DISMISSED = booleanPreferencesKey("battery_prompt_dismissed")
        val LAST_REMINDER_KEY = stringPreferencesKey("last_reminder_key")
        val LAST_REMINDER_AT = longPreferencesKey("last_reminder_at")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            userName = prefs[Keys.USER_NAME] ?: "Athlete",
            imperialUnits = prefs[Keys.IMPERIAL_UNITS] ?: false,
            weightKg = prefs[Keys.WEIGHT_KG] ?: 70f,
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            weeklyGoalKm = prefs[Keys.WEEKLY_GOAL_KM] ?: 25f,
            maxHeartRate = prefs[Keys.MAX_HEART_RATE] ?: 190,
            audioCuesEnabled = prefs[Keys.AUDIO_CUES] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            remindersEnabled = prefs[Keys.REMINDERS] ?: true,
            reminderSlot = ReminderSlot.entries.firstOrNull { it.name == prefs[Keys.REMINDER_SLOT] }
                ?: ReminderSlot.AUTO,
            healthConnectEnabled = prefs[Keys.HEALTH_CONNECT] ?: false,
            lastBackupAt = prefs[Keys.LAST_BACKUP_AT] ?: 0L,
            dismissedInsights = prefs[Keys.DISMISSED_INSIGHTS] ?: emptySet(),
            batteryPromptDismissed = prefs[Keys.BATTERY_PROMPT_DISMISSED] ?: false,
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

    suspend fun setAudioCues(value: Boolean) =
        context.dataStore.edit { it[Keys.AUDIO_CUES] = value }

    suspend fun setKeepScreenOn(value: Boolean) =
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = value }

    suspend fun setHaptics(value: Boolean) =
        context.dataStore.edit { it[Keys.HAPTICS] = value }

    suspend fun setOnboardingDone() =
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }

    suspend fun setReminders(value: Boolean) =
        context.dataStore.edit { it[Keys.REMINDERS] = value }

    suspend fun setReminderSlot(value: ReminderSlot) =
        context.dataStore.edit { it[Keys.REMINDER_SLOT] = value.name }

    suspend fun setHealthConnect(value: Boolean) =
        context.dataStore.edit { it[Keys.HEALTH_CONNECT] = value }

    suspend fun setLastBackupAt(value: Long) =
        context.dataStore.edit { it[Keys.LAST_BACKUP_AT] = value }

    suspend fun setBatteryPromptDismissed() =
        context.dataStore.edit { it[Keys.BATTERY_PROMPT_DISMISSED] = true }

    /** Hides a tip. Only the most recent keys are kept so the set can't grow without bound. */
    suspend fun dismissInsight(key: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.DISMISSED_INSIGHTS] ?: emptySet()
        prefs[Keys.DISMISSED_INSIGHTS] = (current + key).toList().takeLast(60).toSet()
    }

    /** The reminder last shown as a notification: its key and when it was posted. */
    suspend fun lastReminder(): Pair<String?, Long> {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.LAST_REMINDER_KEY] to (prefs[Keys.LAST_REMINDER_AT] ?: 0L)
    }

    suspend fun setLastReminder(key: String, at: Long) = context.dataStore.edit {
        it[Keys.LAST_REMINDER_KEY] = key
        it[Keys.LAST_REMINDER_AT] = at
    }
}
