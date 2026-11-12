package com.ostrava.app.ui.profile

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.domain.METERS_PER_MILE
import com.ostrava.app.domain.POUNDS_PER_KG
import com.ostrava.app.domain.ReminderSlot
import com.ostrava.app.domain.UserSettings
import com.ostrava.app.domain.formatDate
import com.ostrava.app.domain.formatDistanceShort
import com.ostrava.app.domain.formatWeight
import com.ostrava.app.domain.heartRateZones
import com.ostrava.app.health.HealthConnectSync
import com.ostrava.app.ui.AppViewModelProvider
import com.ostrava.app.ui.components.CardLabel
import com.ostrava.app.ui.components.OstravaCard
import com.ostrava.app.ui.components.SettingRow
import com.ostrava.app.ui.detail.ZoneColors
import com.ostrava.app.ui.record.isBatteryUnrestricted
import com.ostrava.app.ui.record.openBatterySettings
import com.ostrava.app.ui.theme.HeroGradient
import kotlin.math.roundToInt

class ProfileActions(
    val onName: (String) -> Unit = {},
    val onWeightKg: (Float) -> Unit = {},
    val onImperial: (Boolean) -> Unit = {},
    val onAutoPause: (Boolean) -> Unit = {},
    val onAudioCues: (Boolean) -> Unit = {},
    val onKeepScreenOn: (Boolean) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onReminders: (Boolean) -> Unit = {},
    val onReminderSlot: (ReminderSlot) -> Unit = {},
    val onWeeklyGoalKm: (Float) -> Unit = {},
    val onMaxHeartRate: (Int) -> Unit = {},
    val onHealthConnect: (Boolean) -> Unit = {},
    val onBatterySettings: () -> Unit = {},
    val onBackup: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onImportGpx: () -> Unit = {},
)

@Composable
fun ProfileScreen(viewModel: ProfileViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.backup(context, it) } }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.restore(context, it) } }
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importGpx(context, it) } }

    // Turning reminders on needs the notification permission on Android 13+.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setReminders(granted) }

    val healthAvailable = remember { HealthConnectSync.isAvailable(context) }
    val healthLauncher = rememberLauncherForActivityResult(HealthConnectSync.permissionContract()) { granted ->
        viewModel.setHealthConnect(granted.containsAll(HealthConnectSync.permissions))
    }

    var batteryUnrestricted by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryUnrestricted = isBatteryUnrestricted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ProfileContent(
        settings = settings,
        healthConnectAvailable = healthAvailable,
        batteryUnrestricted = batteryUnrestricted,
        actions = ProfileActions(
            onName = viewModel::setUserName,
            onWeightKg = viewModel::setWeightKg,
            onImperial = viewModel::setImperialUnits,
            onAutoPause = viewModel::setAutoPause,
            onAudioCues = viewModel::setAudioCues,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onHaptics = viewModel::setHaptics,
            onReminders = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.setReminders(enabled)
                }
            },
            onReminderSlot = viewModel::setReminderSlot,
            onWeeklyGoalKm = viewModel::setWeeklyGoalKm,
            onMaxHeartRate = viewModel::setMaxHeartRate,
            onHealthConnect = { enabled ->
                if (enabled) {
                    try {
                        healthLauncher.launch(HealthConnectSync.permissions)
                    } catch (_: Exception) {
                        viewModel.setHealthConnect(false)
                    }
                } else {
                    viewModel.setHealthConnect(false)
                }
            },
            onBatterySettings = { openBatterySettings(context) },
            onBackup = { backupLauncher.launch("ostrava-backup.json") },
            onRestore = { restoreLauncher.launch(arrayOf("application/json")) },
            onImportGpx = { gpxLauncher.launch(arrayOf("*/*")) },
        ),
    )
}

@Composable
fun ProfileContent(
    settings: UserSettings,
    healthConnectAvailable: Boolean,
    batteryUnrestricted: Boolean,
    actions: ProfileActions,
) {
    val imperial = settings.imperialUnits
    var nameField by remember { mutableStateOf(settings.userName) }
    LaunchedEffect(settings.userName) { nameField = settings.userName }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Athlete
        OstravaCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(HeroGradient, CircleShape)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = settings.userName.trim().take(1).uppercase().ifEmpty { "A" },
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                    )
                }
                OutlinedTextField(
                    value = nameField,
                    onValueChange = {
                        nameField = it
                        if (it.isNotBlank()) actions.onName(it.trim())
                    },
                    label = { Text("Your name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            SliderSetting(
                label = "Weight",
                valueLabel = formatWeight(settings.weightKg, imperial),
                hint = "Used to estimate calories",
                value = settings.weightKg,
                range = 40f..150f,
                onValueChange = actions.onWeightKg,
            )
        }

        // Recording
        OstravaCard {
            CardLabel("Recording")
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                Icons.Filled.PauseCircle, "Auto-pause", "Stop the clock when you stop moving",
                settings.autoPauseEnabled, actions.onAutoPause,
            )
            ToggleRow(
                Icons.AutoMirrored.Filled.VolumeUp, "Voice cues", "Time and pace read out at every split",
                settings.audioCuesEnabled, actions.onAudioCues,
            )
            ToggleRow(
                Icons.Filled.Vibration, "Vibration", "A buzz on start, pause and each interval",
                settings.hapticsEnabled, actions.onHaptics,
            )
            ToggleRow(
                Icons.Filled.LightMode, "Keep screen on", "The display stays awake while recording",
                settings.keepScreenOn, actions.onKeepScreenOn,
            )
            ToggleRow(
                Icons.Filled.Straighten, "Miles and feet", "Use imperial units everywhere",
                settings.imperialUnits, actions.onImperial,
            )
            SettingRow(
                icon = Icons.Filled.BatteryChargingFull,
                title = "Background recording",
                subtitle = if (batteryUnrestricted) "Allowed — recordings keep going with the screen off"
                else "Your phone may stop long recordings to save battery",
                tint = if (batteryUnrestricted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                trailing = {
                    if (!batteryUnrestricted) {
                        TextButton(onClick = actions.onBatterySettings) { Text("Allow") }
                    }
                },
            )
        }

        // Reminders
        OstravaCard {
            CardLabel("Reminders and tips")
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                Icons.Filled.Notifications, "Smart reminders",
                "At most one a day: a streak about to lapse, a goal within reach, time for a backup",
                settings.remindersEnabled, actions.onReminders,
            )
            if (settings.remindersEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "When to check in",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReminderSlot.entries.forEach { slot ->
                        FilterChip(
                            selected = settings.reminderSlot == slot,
                            onClick = { actions.onReminderSlot(slot) },
                            label = { Text(slot.label) },
                        )
                    }
                }
                Text(
                    if (settings.reminderSlot == ReminderSlot.AUTO) "Smart picks a time just before you usually head out."
                    else "Around ${settings.reminderSlot.hour}:00 each day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Training
        OstravaCard {
            CardLabel("Training")
            Spacer(Modifier.height(10.dp))
            val goalMeters = settings.weeklyGoalKm * 1000.0
            if (imperial) {
                SliderSetting(
                    label = "Weekly distance goal",
                    valueLabel = if (settings.weeklyGoalKm <= 0f) "Off" else formatDistanceShort(goalMeters, true),
                    hint = null,
                    value = (goalMeters / METERS_PER_MILE).toFloat(),
                    range = 0f..125f,
                    onValueChange = { miles -> actions.onWeeklyGoalKm((miles.roundToInt() * METERS_PER_MILE / 1000.0).toFloat()) },
                )
            } else {
                SliderSetting(
                    label = "Weekly distance goal",
                    valueLabel = if (settings.weeklyGoalKm <= 0f) "Off" else "${settings.weeklyGoalKm.roundToInt()} km",
                    hint = null,
                    value = settings.weeklyGoalKm,
                    range = 0f..200f,
                    onValueChange = { km -> actions.onWeeklyGoalKm(km.roundToInt().toFloat()) },
                )
            }
            Spacer(Modifier.height(8.dp))
            SliderSetting(
                label = "Max heart rate",
                valueLabel = "${settings.maxHeartRate} bpm",
                hint = "Sets the zones below. A common estimate is 220 minus your age.",
                value = settings.maxHeartRate.toFloat(),
                range = 140f..220f,
                onValueChange = { actions.onMaxHeartRate(it.roundToInt()) },
            )
            Spacer(Modifier.height(8.dp))
            heartRateZones(settings.maxHeartRate).forEach { zone ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(ZoneColors[zone.index - 1], RoundedCornerShape(4.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Z${zone.index} ${zone.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${zone.fromBpm}–${zone.toBpm} bpm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Data and connections
        OstravaCard {
            CardLabel("Your data")
            Spacer(Modifier.height(4.dp))
            SettingRow(
                icon = Icons.Filled.CloudUpload,
                title = "Back up everything",
                subtitle = if (settings.lastBackupAt == 0L) "Never backed up — your history lives only on this phone"
                else "Last backup ${formatDate(settings.lastBackupAt)}",
                onClick = actions.onBackup,
            )
            SettingRow(
                icon = Icons.Filled.CloudDownload,
                title = "Restore from a backup",
                subtitle = "Adds the activities from a backup file",
                onClick = actions.onRestore,
            )
            SettingRow(
                icon = Icons.Filled.FileOpen,
                title = "Import a GPX file",
                subtitle = "Bring in an activity from another app or watch",
                onClick = actions.onImportGpx,
            )
            ToggleRow(
                icon = Icons.Filled.HealthAndSafety,
                title = "Health Connect",
                subtitle = if (healthConnectAvailable) "Share finished activities with your other health apps"
                else "Not available on this phone",
                checked = settings.healthConnectEnabled && healthConnectAvailable,
                onCheckedChange = actions.onHealthConnect,
                enabled = healthConnectAvailable,
            )
        }

        Text(
            "Ostrava · open-source activity tracker\nMap data © OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    )
}

@Composable
private fun SliderSetting(
    label: String,
    valueLabel: String,
    hint: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
