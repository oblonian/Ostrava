package com.ostrava.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val OstravaOrange = Color(0xFFFC5200)
val OstravaOrangeLight = Color(0xFFFF8A3D)
val DeepNavy = Color(0xFF1B2733)
val Mint = Color(0xFF0E9F8E)

/** The warm orange gradient used for hero cards and the main call to action. */
val HeroGradient: Brush
    get() = Brush.linearGradient(listOf(OstravaOrange, OstravaOrangeLight))

private val LightColors = lightColorScheme(
    primary = OstravaOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4D6),
    onPrimaryContainer = Color(0xFF3A1100),
    secondary = DeepNavy,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F0),
    onSecondaryContainer = Color(0xFF101C28),
    tertiary = Mint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3F4EE),
    onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFFAF6F2),
    onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFAF6F2),
    onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFF1EAE3),
    onSurfaceVariant = Color(0xFF625C56),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF5EFE9),
    surfaceContainerHigh = Color(0xFFEFE8E1),
    surfaceContainerHighest = Color(0xFFE9E1D9),
    outline = Color(0xFFB3AAA2),
    outlineVariant = Color(0xFFE6DED6),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE0DD),
    onErrorContainer = Color(0xFF5A0B07),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7A3D),
    onPrimary = Color(0xFF2B0C00),
    primaryContainer = Color(0xFF5E2607),
    onPrimaryContainer = Color(0xFFFFDCCB),
    secondary = Color(0xFFBCCADA),
    onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF2B3947),
    onSecondaryContainer = Color(0xFFD9E5F3),
    tertiary = Color(0xFF5FD8C6),
    onTertiary = Color(0xFF003730),
    tertiaryContainer = Color(0xFF0B4F46),
    onTertiaryContainer = Color(0xFFBFF3EA),
    background = Color(0xFF111315),
    onBackground = Color(0xFFECE6E1),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFECE6E1),
    surfaceVariant = Color(0xFF2A2D31),
    onSurfaceVariant = Color(0xFFB7B0A9),
    surfaceContainerLowest = Color(0xFF0C0E0F),
    surfaceContainerLow = Color(0xFF1B1E21),
    surfaceContainer = Color(0xFF202327),
    surfaceContainerHigh = Color(0xFF282C30),
    surfaceContainerHighest = Color(0xFF32363B),
    outline = Color(0xFF8C857E),
    outlineVariant = Color(0xFF33373C),
)

private val OstravaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val Base = Typography()

private val OstravaTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

@Composable
fun OstravaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        shapes = OstravaShapes,
        typography = OstravaTypography,
    ) {
        // Text drawn straight on the page background (outside any card) must follow the
        // theme too; without this it stays black in dark mode.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
