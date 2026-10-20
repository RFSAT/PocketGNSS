package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AlarmRed = Color(0xFFE53935)
val SafeGreen = Color(0xFF43A047)
val WarnAmber = Color(0xFFFFA000)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF8A65),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF121416),
    surface = Color(0xFF1A1D20),
    surfaceVariant = Color(0xFF262A2E),
)

private val Light = lightColorScheme(
    primary = Color(0xFFBF360C),
    secondary = Color(0xFF00796B),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEDEA),
)

@Composable
fun RtkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
