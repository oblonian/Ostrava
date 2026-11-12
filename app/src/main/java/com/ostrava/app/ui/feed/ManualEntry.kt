package com.ostrava.app.ui.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ostrava.app.domain.ActivityType
import com.ostrava.app.domain.FEET_PER_METER
import com.ostrava.app.domain.METERS_PER_MILE
import com.ostrava.app.domain.defaultActivityTitle
import com.ostrava.app.domain.formatDate
import com.ostrava.app.ui.components.icon
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class ManualEntry(
    val type: ActivityType,
    val title: String,
    val startTime: Long,
    val durationMillis: Long,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
)

/** Lets the athlete log an activity without a GPS track, e.g. a treadmill run. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryDialog(
    imperial: Boolean,
    onDismiss: () -> Unit,
    onSave: (ManualEntry) -> Unit,
) {
    var type by remember { mutableStateOf(ActivityType.RUN) }
    var title by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var climb by remember { mutableStateOf("") }
    // The date picker works in UTC midnights.
    var dateUtcMillis by remember { mutableLongStateOf(todayUtcMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val durationMillis = ((hours.toLongOrNull() ?: 0L) * 60 + (minutes.toLongOrNull() ?: 0L)) * 60_000L
    val distanceValue = distance.replace(',', '.').toDoubleOrNull() ?: 0.0
    val valid = durationMillis > 0 && distanceValue > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActivityType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label) },
                            leadingIcon = { Icon(option.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  ${formatDate(localNoonMillis(dateUtcMillis))}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(hours, { hours = it.filter(Char::isDigit).take(2) }, "Hours", Modifier.weight(1f))
                    NumberField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, "Minutes", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        distance, { distance = it.take(7) },
                        if (imperial) "Distance (mi)" else "Distance (km)", Modifier.weight(1f), decimal = true,
                    )
                    NumberField(
                        climb, { climb = it.filter(Char::isDigit).take(5) },
                        if (imperial) "Climb (ft)" else "Climb (m)", Modifier.weight(1f),
                    )
                }
                Text(
                    "Manual activities count towards your totals and goals, but have no route or split records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val startTime = startTimeFor(dateUtcMillis)
                    onSave(
                        ManualEntry(
                            type = type,
                            title = title.trim().ifBlank { defaultActivityTitle(type, startTime) },
                            startTime = startTime,
                            durationMillis = durationMillis,
                            distanceMeters = distanceValue * if (imperial) METERS_PER_MILE else 1000.0,
                            elevationGainMeters = (climb.toDoubleOrNull() ?: 0.0) /
                                if (imperial) FEET_PER_METER else 1.0,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dateUtcMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // Activities can't be logged in the future.
                    state.selectedDateMillis?.let { dateUtcMillis = minOf(it, todayUtcMillis()) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

private fun todayUtcMillis(): Long =
    java.time.LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun localNoonMillis(dateUtcMillis: Long): Long {
    val date = Instant.ofEpochMilli(dateUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Today's entries start "now minus nothing"; earlier days are logged at midday. */
private fun startTimeFor(dateUtcMillis: Long): Long =
    if (dateUtcMillis >= todayUtcMillis()) System.currentTimeMillis() else localNoonMillis(dateUtcMillis)
