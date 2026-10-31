package com.meteor.memory.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

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
    /** Material You wallpaper colours; ignored below Android 12. */
    dynamicColor: Boolean = true,
    /** Multiplier on top of the system font scale. 1f = follow the system. */
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaledDensity = remember(density, fontScale) {
        Density(density = density.density, fontScale = density.fontScale * fontScale)
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MeteorDark
        else -> MeteorLight
    }

    CompositionLocalProvider(
        LocalAnimationsEnabled provides animationsEnabled,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content,
        )
    }
}
