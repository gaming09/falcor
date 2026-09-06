package com.falcor.viewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FalcorDark = darkColorScheme(
    primary = Color(0xFF3DDCFF),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004E62),
    onPrimaryContainer = Color(0xFFB8EAFF),
    secondary = Color(0xFF7C5CFF),
    onSecondary = Color(0xFF1E005E),
    secondaryContainer = Color(0xFF3A1D99),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFF5CFFB0),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE1E3E8),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE1E3E8),
    surfaceVariant = Color(0xFF1A222D),
    onSurfaceVariant = Color(0xFFBFC7D4),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF89939F)
)

@Composable
fun FalcorTheme(
    content: @Composable () -> Unit
) {
    // Dark theme is the default product look.
    MaterialTheme(
        colorScheme = FalcorDark,
        typography = FalcorTypography,
        content = content
    )
}
