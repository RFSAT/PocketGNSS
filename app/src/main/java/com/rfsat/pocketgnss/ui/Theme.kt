package com.rfsat.pocketgnss.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AlarmRed = Color(0xFFE53935)
val SafeGreen = Color(0xFF43A047)
/** Amber for graphics only (fix colour, CEP circle, lightbar); text uses [AlarmRed] or [InfoViolet], which read better. */
val WarnAmber = Color(0xFFFFA000)
/**
 * Text colour of informative notes (a setting to check, a limitation): violet, readable on both themes. Critical
 * messages (no solution, no ephemerides, an error) use [AlarmRed]. Orange text was too faint to read while driving.
 */
val InfoViolet: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFCE93D8) else Color(0xFF7B1FA2)

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

/**
 * Comments and notes (bodySmall) as large as body text (14 sp instead of Material's 12 sp): smaller text was hard to
 * read at arm's length, in a car or on a tractor.
 */
private val AppTypography = androidx.compose.material3.Typography().let { t ->
    t.copy(bodySmall = t.bodySmall.copy(fontSize = t.bodyMedium.fontSize, lineHeight = t.bodyMedium.lineHeight))
}

@Composable
fun RtkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}
