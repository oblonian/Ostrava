package com.ostrava.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val OstravaOrange = Color(0xFFFC5200)
val OstravaOrangeDark = Color(0xFFD94600)
val DeepNavy = Color(0xFF1B2733)

private val LightColors = lightColorScheme(
    primary = OstravaOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = DeepNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F2),
    onSecondaryContainer = Color(0xFF101C28),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1EDEA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A50),
    onPrimary = Color(0xFF5B1A00),
    primaryContainer = OstravaOrangeDark,
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFB8C8DA),
    onSecondary = Color(0xFF22323F),
    background = Color(0xFF121212),
    surface = Color(0xFF1C1B1F),
)

@Composable
fun OstravaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
