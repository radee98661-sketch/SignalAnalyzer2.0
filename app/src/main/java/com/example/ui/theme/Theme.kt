package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SignalDarkColorScheme = darkColorScheme(
    primary = EmeraldAccent,
    onPrimary = OnEmerald,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = EmeraldAccent,
    secondary = SignalBlue,
    onSecondary = NavyCanvas,
    secondaryContainer = SignalBlueContainer,
    onSecondaryContainer = SignalBlue,
    tertiary = AmberWarn,
    onTertiary = NavyCanvas,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberWarn,
    error = CoralRed,
    onError = NavyCanvas,
    errorContainer = CoralContainer,
    onErrorContainer = CoralRed,
    background = NavyCanvas,
    onBackground = TextPrimary,
    surface = NavyCard,
    onSurface = TextPrimary,
    surfaceVariant = NavyCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = NavyBorder,
    outlineVariant = NavyBorderActive
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun SignalAnalyzerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SignalDarkColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
