package com.ostrava.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.domain.heartRateZones
import com.ostrava.app.ui.AppViewModelProvider
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(viewModel: ProfileViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var nameField by remember { mutableStateOf(settings.userName) }
    LaunchedEffect(settings.userName) { nameField = settings.userName }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.backup(context, it) } }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.restore(context, it) } }
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importGpx(context, it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Profile & settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Athlete", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = nameField,
                    onValueChange = {
                        nameField = it
                        if (it.isNotBlank()) viewModel.setUserName(it.trim())
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text("Weight: ${settings.weightKg.roundToInt()} kg")
                    Slider(
                        value = settings.weightKg,
                        onValueChange = { viewModel.setWeightKg(it) },
                        valueRange = 40f..150f,
                    )
                    Text(
                        "Used for calorie estimates",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Recording", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                SettingSwitch(
                    title = "Imperial units",
                    subtitle = "Miles and feet instead of kilometres and metres",
                    checked = settings.imperialUnits,
                    onCheckedChange = { viewModel.setImperialUnits(it) },
                )
                SettingSwitch(
                    title = "Auto-pause",
                    subtitle = "Pause the clock automatically when you stop moving",
                    checked = settings.autoPauseEnabled,
                    onCheckedChange = { viewModel.setAutoPause(it) },
                )
                SettingSwitch(
                    title = "Audio cues",
                    subtitle = "Spoken split announcements and workout phases",
                    checked = settings.audioCuesEnabled,
                    onCheckedChange = { viewModel.setAudioCues(it) },
                )
                SettingSwitch(
                    title = "Keep screen on",
                    subtitle = "Prevent the display sleeping while recording",
                    checked = settings.keepScreenOn,
                    onCheckedChange = { viewModel.setKeepScreenOn(it) },
                )
                SettingSwitch(
                    title = "Vibration feedback",
                    subtitle = "Buzz on start, pause, auto-pause and splits",
                    checked = settings.hapticsEnabled,
                    onCheckedChange = { viewModel.setHaptics(it) },
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Your training history lives only on this phone. Back it up regularly.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { backupLauncher.launch("ostrava-backup.json") }) {
                        Text("Back up")
                    }
                    OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json")) }) {
                        Text("Restore")
                    }
                    OutlinedButton(onClick = { gpxLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import GPX")
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Training", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Column {
                    Text("Weekly distance goal: ${settings.weeklyGoalKm.roundToInt()} km")
                    Slider(
                        value = settings.weeklyGoalKm,
                        onValueChange = { viewModel.setWeeklyGoalKm(it) },
                        valueRange = 0f..200f,
                    )
                }
                Column {
                    Text("Max heart rate: ${settings.maxHeartRate} bpm")
                    Slider(
                        value = settings.maxHeartRate.toFloat(),
                        onValueChange = { viewModel.setMaxHeartRate(it.roundToInt()) },
                        valueRange = 140f..220f,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Heart rate zones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                heartRateZones(settings.maxHeartRate).forEach { zone ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Z${zone.index} ${zone.name}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${zone.fromBpm}–${zone.toBpm} bpm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Ostrava — open activity tracker. GPS tracking, splits, best efforts, " +
                        "personal records, weekly analytics, GPX export. Map data © OpenStreetMap contributors.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
