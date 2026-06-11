package com.ostrava.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.ostrava.app.domain.ActivityType

val ActivityType.icon: ImageVector
    get() = when (this) {
        ActivityType.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
        ActivityType.RIDE -> Icons.AutoMirrored.Filled.DirectionsBike
        ActivityType.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        ActivityType.HIKE -> Icons.Filled.Terrain
    }

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = if (emphasized) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
