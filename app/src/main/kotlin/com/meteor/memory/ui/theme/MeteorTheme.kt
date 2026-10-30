package com.meteor.memory.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Global animation gate (archive R11).
 *
 * When the user turns animations off, every screen must read this local and
 * skip transitions / list animations / emoji bounces.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

private val MeteorDark = darkColorScheme(
    primary = Color(0xFF9CC7FF),
    secondary = Color(0xFFB8C6DA),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
)

private val MeteorLight = lightColorScheme(
    primary = Color(0xFF2F6DAE),
    secondary = Color(0xFF52606E),
    background = Color(0xFFF8F9FB),
    surface = Color(0xFFF8F9FB),
)

@Composable
fun MeteorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAnimationsEnabled provides animationsEnabled) {
        MaterialTheme(
            colorScheme = if (darkTheme) MeteorDark else MeteorLight,
            content = content,
        )
    }
}
