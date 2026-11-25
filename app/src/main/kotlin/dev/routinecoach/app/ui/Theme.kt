package dev.routinecoach.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val Light = lightColorScheme(
    primary = Color(0xFF2E5E4E), onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBD6), onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF8A4F1C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC2), onSecondaryContainer = Color(0xFF2E1500),
    tertiary = Color(0xFF3E5F90), error = Color(0xFFB3261E),
    background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7), surfaceVariant = Color(0xFFDDE5DF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CD0BA), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF14503E), onPrimaryContainer = Color(0xFFB8EBD6),
    secondary = Color(0xFFFFB77C), onSecondary = Color(0xFF4C2700),
    secondaryContainer = Color(0xFF6C3A06), onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFFA8C8FF), background = Color(0xFF101413), surface = Color(0xFF101413),
)
/** High-contrast variants: pure black/white text and saturated accents. */
private val LightHc = Light.copy(
    primary = Color(0xFF00332A), onSurface = Color.Black, onBackground = Color.Black, background = Color.White, surface = Color.White,
    onSurfaceVariant = Color.Black, outline = Color.Black, secondary = Color(0xFF5A2D00),
)
private val DarkHc = Dark.copy(
    primary = Color(0xFFC4FFE9), onSurface = Color.White, onBackground = Color.White, background = Color.Black, surface = Color.Black,
    onSurfaceVariant = Color.White, outline = Color.White, secondary = Color(0xFFFFD2AE),
)

@Composable
fun RoutineCoachTheme(fontScale: Float = 1f, highContrast: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        dark && highContrast -> DarkHc
        dark -> Dark
        highContrast -> LightHc
        else -> Light
    }
    val density = LocalDensity.current
    // The user's in-app text size multiplies the system font scale (e.g. 130% system * 110% app).
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
