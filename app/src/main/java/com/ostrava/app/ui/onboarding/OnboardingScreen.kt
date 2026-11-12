package com.ostrava.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ostrava.app.ui.components.EmojiBadge
import com.ostrava.app.ui.theme.HeroGradient

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onDone() }

    OnboardingContent(
        onStart = {
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            permissionLauncher.launch(permissions.toTypedArray())
        },
    )
}

@Composable
fun OnboardingContent(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                .background(HeroGradient)
                .padding(start = 28.dp, end = 28.dp, top = 72.dp, bottom = 40.dp),
        ) {
            Column {
                Text("👟", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(12.dp))
                Text("Welcome to Ostrava", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Track every run, ride, walk and hike. Your data stays on your phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Feature("🗺️", "Live tracking", "Route, pace and splits as you move, with voice cues so you can keep your eyes up.")
            Feature("🏆", "Records and streaks", "Best efforts over every distance, weekly streaks and gentle, useful nudges.")
            Feature("📍", "Location", "Needed to record your route. Recording carries on with the screen off.")
            Feature("🔔", "Notifications", "Live stats while you record, and at most one reminder a day. You can turn reminders off any time.")

            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(50))
                    .background(HeroGradient)
                    .clickable(role = Role.Button, onClick = onStart),
                contentAlignment = Alignment.Center,
            ) {
                Text("Let's go", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.ExtraBold)
            }
            Text(
                "Next, Android will ask for location and notification access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun Feature(emoji: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        EmojiBadge(emoji, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
