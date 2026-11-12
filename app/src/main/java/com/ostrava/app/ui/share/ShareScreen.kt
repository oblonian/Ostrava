package com.ostrava.app.ui.share

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ostrava.app.export.ShareAspect
import com.ostrava.app.export.ShareCardRenderer
import com.ostrava.app.export.ShareOutput
import com.ostrava.app.export.ShareTemplate
import com.ostrava.app.export.ShareTheme
import com.ostrava.app.ui.AppViewModelProvider

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareScreen(
    onBack: () -> Unit,
    viewModel: ShareViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val spec by viewModel.spec.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.setPhoto(context, it) } }

    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { taken ->
        val uri = cameraUri
        if (taken && uri != null) viewModel.setPhoto(context, uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Share activity",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        if (uiState.missing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Activity not found")
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = preview
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Preview of the image that will be shared",
                        modifier = Modifier
                            .aspectRatio(spec.aspect.width.toFloat() / spec.aspect.height)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            Section("Style") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ShareTemplate.entries.forEachIndexed { index, template ->
                        SegmentedButton(
                            selected = spec.template == template,
                            onClick = { viewModel.update { it.copy(template = template) } },
                            shape = SegmentedButtonDefaults.itemShape(index, ShareTemplate.entries.size),
                        ) { Text(template.label) }
                    }
                }
            }

            if (spec.template == ShareTemplate.PHOTO) {
                Section("Background photo") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (uiState.hasPhoto) "Change" else "Choose photo")
                        }
                        OutlinedButton(onClick = {
                            try {
                                val uri = ShareOutput.newCameraUri(context)
                                cameraUri = uri
                                camera.launch(uri)
                            } catch (_: Exception) {
                                // No camera app available; the gallery picker still works.
                            }
                        }) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Camera")
                        }
                        if (uiState.hasPhoto) {
                            TextButton(onClick = { viewModel.removePhoto() }) { Text("Remove") }
                        }
                    }
                    if (uiState.hasPhoto) {
                        Spacer(Modifier.height(8.dp))
                        Text("Darken photo", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = spec.photoDim,
                            onValueChange = { value -> viewModel.update { it.copy(photoDim = value) } },
                            valueRange = 0f..0.7f,
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Pick a photo from your run to put behind the stats.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (spec.template == ShareTemplate.ROUTE) {
                Section("Colour") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShareTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = spec.theme == theme,
                                onClick = { viewModel.update { it.copy(theme = theme) } },
                                label = { Text(theme.label) },
                            )
                        }
                    }
                }
            }

            Section("Format") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareAspect.entries.forEach { aspect ->
                        FilterChip(
                            selected = spec.aspect == aspect,
                            onClick = { viewModel.update { it.copy(aspect = aspect) } },
                            label = { Text(aspect.label) },
                        )
                    }
                }
            }

            Section("Stats (up to ${ShareCardRenderer.MAX_STATS})") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.availableStats.forEach { stat ->
                        FilterChip(
                            selected = stat in spec.stats,
                            onClick = { viewModel.toggleStat(stat) },
                            label = { Text(stat.label) },
                        )
                    }
                }
            }

            Column {
                ToggleRow(
                    label = "Show route",
                    checked = spec.showRoute,
                    onCheckedChange = { checked -> viewModel.update { it.copy(showRoute = checked) } },
                )
                if (uiState.hasPersonalRecord) {
                    ToggleRow(
                        label = "Show personal record badge",
                        checked = spec.showPrBadge,
                        onCheckedChange = { checked -> viewModel.update { it.copy(showPrBadge = checked) } },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ready = !uiState.loading && !uiState.exporting
                Button(
                    onClick = { viewModel.share(context) },
                    enabled = ready,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.exporting) "Preparing…" else "Share", fontWeight = FontWeight.Bold)
                }
                if (ShareOutput.canSaveToGallery) {
                    OutlinedButton(
                        onClick = { viewModel.saveToGallery(context) },
                        enabled = ready,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Save image")
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
