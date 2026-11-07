package com.meteor.memory.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Global animation gate (archive R11).
 *
 * When the user turns animations off, every screen must read this local and
 * skip transitions / list animations / emoji bounces.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * Colour roles follow the shipped design sheet (see `docs/design/`):
 * misty blue paper (#F4F8FC), ink (#1B2A38), slate blue accents
 * (#426989 / #2E4F6B), soft blue fills (#D6E8F7 / #E6EFF8 / #EAF1F8)
 * and a single hairline colour (#D3E1EE).
 */
private val MeteorDark = darkColorScheme(
    primary = Color(0xFF9CC0DF),
    onPrimary = Color(0xFF12293C),
    primaryContainer = Color(0xFF2C4A63),
    onPrimaryContainer = Color(0xFFD6E8F7),
    secondary = Color(0xFFB6CEE2),
    onSecondary = Color(0xFF152230),
    secondaryContainer = Color(0xFF263D4F),
    onSecondaryContainer = Color(0xFFDCE8F3),
    tertiary = Color(0xFFE2B98C),
    onTertiary = Color(0xFF402C17),
    tertiaryContainer = Color(0xFF5A432B),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF0F1720),
    onBackground = Color(0xFFE3EBF2),
    surface = Color(0xFF16202A),
    onSurface = Color(0xFFE3EBF2),
    surfaceVariant = Color(0xFF223140),
    onSurfaceVariant = Color(0xFFA9B9C8),
    surfaceContainerLowest = Color(0xFF0C141C),
    surfaceContainerLow = Color(0xFF141E28),
    surfaceContainer = Color(0xFF182330),
    surfaceContainerHigh = Color(0xFF1D2937),
    surfaceContainerHighest = Color(0xFF24313F),
    outline = Color(0xFF7E93A6),
    outlineVariant = Color(0xFF33465A),
)

private val MeteorLight = lightColorScheme(
    primary = Color(0xFF426989),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E8F7),
    onPrimaryContainer = Color(0xFF142F45),
    secondary = Color(0xFF2E4F6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6EFF8),
    onSecondaryContainer = Color(0xFF16283A),
    tertiary = Color(0xFF815C37),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2D1600),
    background = Color(0xFFF4F8FC),
    onBackground = Color(0xFF1B2A38),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2A38),
    surfaceVariant = Color(0xFFEAF1F8),
    onSurfaceVariant = Color(0xFF5F7385),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FBFE),
    surfaceContainer = Color(0xFFF4F8FC),
    surfaceContainerHigh = Color(0xFFEDF3FA),
    surfaceContainerHighest = Color(0xFFE6EEF6),
    outline = Color(0xFF8FA6BA),
    outlineVariant = Color(0xFFD3E1EE),
)

private val MeteorShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
)

@Composable
fun MeteorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    animationsEnabled: Boolean = true,
    /** Material You wallpaper colours; ignored below Android 12. */
    dynamicColor: Boolean = false,
    /** Multiplier on top of the system font scale. 1f = follow the system. */
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaledDensity = remember(density, fontScale) {
        Density(density = density.density, fontScale = density.fontScale * fontScale)
    }

    val context = LocalContext.current
    val colorScheme = remember(context, darkTheme, dynamicColor) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> MeteorDark
            else -> MeteorLight
        }
    }

    CompositionLocalProvider(
        LocalAnimationsEnabled provides animationsEnabled,
        LocalDensity provides scaledDensity,
    ) {
        // The app paints its own background behind the status/nav bars (edge-to-edge),
        // so the system icons have to follow *this* theme rather than the system one:
        // in light mode the default white icons are invisible against the pale surface.
        val view = LocalView.current
        val context = LocalContext.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }

        MaterialTheme(
            colorScheme = colorScheme,
            shapes = MeteorShapes,
            content = content,
        )
    }
}
