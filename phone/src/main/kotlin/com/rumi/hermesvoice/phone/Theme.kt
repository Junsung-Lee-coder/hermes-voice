package com.rumi.hermesvoice.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark is the default (see [com.rumi.hermesvoice.core.settings.ThemeMode]). Text/background pairs
 * keep at least WCAG AA contrast: onSurface on surface is about 14:1, onSurfaceVariant on the
 * containers about 9:1, and onPrimary on primary about 8:1.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF1C4A78),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF354457),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFF8AD7B0),
    onTertiary = Color(0xFF003824),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE2E4E9),
    surface = Color(0xFF0F1216),
    onSurface = Color(0xFFE2E4E9),
    surfaceVariant = Color(0xFF2B3139),
    onSurfaceVariant = Color(0xFFC3C8D0),
    surfaceContainerLowest = Color(0xFF0A0D10),
    surfaceContainerLow = Color(0xFF161A1F),
    surfaceContainer = Color(0xFF1A1F25),
    surfaceContainerHigh = Color(0xFF232930),
    surfaceContainerHighest = Color(0xFF2D333B),
    outline = Color(0xFF8D939B),
    outlineVariant = Color(0xFF43484F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1F5F99),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondaryContainer = Color(0xFFDAE3F2),
    onSecondaryContainer = Color(0xFF131C2B),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE6E8EC),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun HermesVoiceTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
