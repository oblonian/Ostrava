package com.ostrava.app.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ostrava.app.domain.Feel
import com.ostrava.app.domain.IntervalConfig
import com.ostrava.app.domain.formatDuration
import com.ostrava.app.tracking.HeartRateMonitor

/** Post-activity save dialog: name it, rate how it felt. */
@Composable
fun FinishDialog(
    defaultTitle: String,
    onSave: (title: String, feel: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(defaultTitle) }
    var feel by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save activity") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("How did it feel?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Feel.options.forEach { (value, label) ->
                        FilterChip(
                            selected = feel == value,
                            onClick = { feel = if (feel == value) null else value },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, feel) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun DiscardDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard activity?") },
        text = { Text("The recording will be thrown away permanently. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Discard") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep recording") }
        },
    )
}

/** Interval workout builder. */
@Composable
fun IntervalDialog(
    initial: IntervalConfig?,
    onApply: (IntervalConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    var warmupMin by remember { mutableIntStateOf(initial?.warmupSec?.div(60) ?: 5) }
    var workSec by remember { mutableIntStateOf(initial?.workSec ?: 120) }
    var restSec by remember { mutableIntStateOf(initial?.restSec ?: 60) }
    var repeats by remember { mutableIntStateOf(initial?.repeats ?: 6) }
    var cooldownMin by remember { mutableIntStateOf(initial?.cooldownSec?.div(60) ?: 5) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interval workout") },
        text = {
            Column {
                LabeledSlider("Warm-up: $warmupMin min", warmupMin.toFloat(), 0f..30f) {
                    warmupMin = it.toInt()
                }
                LabeledSlider("Work: ${formatDuration(workSec * 1000L)}", workSec.toFloat(), 15f..600f) {
                    workSec = (it.toInt() / 15) * 15
                }
                LabeledSlider("Rest: ${formatDuration(restSec * 1000L)}", restSec.toFloat(), 0f..300f) {
                    restSec = (it.toInt() / 15) * 15
                }
                LabeledSlider("Repeats: $repeats", repeats.toFloat(), 1f..20f) {
                    repeats = it.toInt()
                }
                LabeledSlider("Cool-down: $cooldownMin min", cooldownMin.toFloat(), 0f..30f) {
                    cooldownMin = it.toInt()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    IntervalConfig(
                        warmupSec = warmupMin * 60,
                        workSec = workSec,
                        restSec = restSec,
                        repeats = repeats,
                        cooldownSec = cooldownMin * 60,
                    )
                )
            }) { Text("Use workout") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onApply(null) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** Heart-rate sensor scan/connect dialog. */
@Composable
fun HeartRateDialog(onDismiss: () -> Unit, onScan: () -> Unit, onConnect: (String) -> Unit) {
    val connectionState by HeartRateMonitor.state.collectAsStateWithLifecycle()
    val devices by HeartRateMonitor.foundDevices.collectAsStateWithLifecycle()
    val deviceName by HeartRateMonitor.deviceName.collectAsStateWithLifecycle()
    val bpm by HeartRateMonitor.bpm.collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heart rate sensor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (connectionState) {
                    HeartRateMonitor.ConnectionState.CONNECTED -> {
                        Text("Connected to ${deviceName ?: "sensor"}")
                        Text(
                            text = bpm?.let { "♥ $it bpm" } ?: "Waiting for data…",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HeartRateMonitor.ConnectionState.CONNECTING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(20.dp))
                            Spacer(Modifier.height(0.dp))
                            Text("  Connecting…")
                        }
                    }
                    else -> {
                        if (connectionState == HeartRateMonitor.ConnectionState.SCANNING) {
                            Text("Scanning for sensors…")
                        } else {
                            Text("Scan for a Bluetooth LE heart-rate strap or watch broadcasting HR.")
                        }
                        devices.forEach { device ->
                            TextButton(onClick = { onConnect(device.address) }) {
                                Text(device.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (connectionState) {
                HeartRateMonitor.ConnectionState.CONNECTED ->
                    TextButton(onClick = { HeartRateMonitor.disconnect() }) { Text("Disconnect") }
                HeartRateMonitor.ConnectionState.SCANNING ->
                    TextButton(onClick = { HeartRateMonitor.stopScan() }) { Text("Stop scan") }
                else ->
                    TextButton(onClick = onScan) { Text("Scan") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
