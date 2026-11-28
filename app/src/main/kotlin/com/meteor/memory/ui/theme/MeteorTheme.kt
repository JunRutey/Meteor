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
 * True when Material You wallpaper colours are actually driving the scheme.
 *
 * A screen that paints a decorative background must check this: the hand-tuned
 * paper gradient only belongs to [MeteorLight], so under dynamic colour it has
 * to be replaced by a scheme-derived one instead of staying blue.
 */
val LocalDynamicColorEnabled = staticCompositionLocalOf { false }

/**
 * Colour roles are the app icon's blue, **desaturated**: the icon art
 * (`res/drawable-xxxhdpi/splash_icon.png`) clusters around #4C77FC, a 97%
 * saturation royal blue, but that intensity is too loud for a diary you read
 * every day. The sheet keeps the icon's hue family (~210-215°) and drops the
 * saturation to ~30-37%, which is the same restrained register the previous
 * slate palette lived in — just brighter, cleaner and clearly blue rather than
 * grey-blue. Two deliberately mixed partners keep it from reading as one flat
 * blue: a soft teal secondary and a muted warm sand tertiary.
 *
 * Light — a clean white canvas (#FFFFFF, matching the home drawer panel),
 * ink (#1B2A38), desaturated blue accents (#4A6D96 / #3F7286), pale blue
 * fills (#DCE8F4 / #DFEDF1 / #EAF2FA) and a single soft hairline (#D8E5F0).
 * Buttons keep their original accent colours; only the page background moved.
 *
 * Dark — deep blue-slate night (#111823) with the accent lifted to a soft
 * #9CB3CE so it still reads as the same hue, ink (#DFE8F1), and matching
 * desaturated fills (#2B3F55 / #24414C / #212D3B).
 *
 * Contrast bar (all computed, not eyeballed): `primary` carries text and icons
 * all over the light UI, so it must stay legible on paper — #4A6D96 is 5.11:1
 * on `background` and 5.36:1 on white, and white `onPrimary` on it is 5.36:1.
 * Body ink stays >= 13.9:1 on every light surface and >= 14.3:1 on dark.
 * Every accent sits at 30-37% saturation, so nothing is a saturated "pop".
 */
private val MeteorDark = darkColorScheme(
    primary = Color(0xFF9CB3CE),
    onPrimary = Color(0xFF101A26),
    primaryContainer = Color(0xFF2B3F55),
    onPrimaryContainer = Color(0xFFD6E4F2),
    secondary = Color(0xFF8FB8C6),
    onSecondary = Color(0xFF0C2229),
    secondaryContainer = Color(0xFF24414C),
    onSecondaryContainer = Color(0xFFCFE7EE),
    tertiary = Color(0xFFDCBB9A),
    onTertiary = Color(0xFF2A1C0E),
    tertiaryContainer = Color(0xFF4F3B27),
    onTertiaryContainer = Color(0xFFF8E3CD),
    background = Color(0xFF111823),
    onBackground = Color(0xFFDFE8F1),
    surface = Color(0xFF161F2B),
    onSurface = Color(0xFFDFE8F1),
    surfaceVariant = Color(0xFF212D3B),
    onSurfaceVariant = Color(0xFFA6B6C6),
    surfaceContainerLowest = Color(0xFF0C131C),
    surfaceContainerLow = Color(0xFF131B26),
    surfaceContainer = Color(0xFF171F2B),
    surfaceContainerHigh = Color(0xFF1C2634),
    surfaceContainerHighest = Color(0xFF232E3E),
    outline = Color(0xFF7C90A3),
    outlineVariant = Color(0xFF2F4053),
)

private val MeteorLight = lightColorScheme(
    primary = Color(0xFF4A6D96),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE8F4),
    onPrimaryContainer = Color(0xFF1B3049),
    secondary = Color(0xFF3F7286),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFEDF1),
    onSecondaryContainer = Color(0xFF123039),
    tertiary = Color(0xFF8A6440),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E7D6),
    onTertiaryContainer = Color(0xFF331A00),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1B2A38),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B2A38),
    surfaceVariant = Color(0xFFEAF2FA),
    onSurfaceVariant = Color(0xFF55677A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFCFE),
    surfaceContainer = Color(0xFFF6FAFE),
    surfaceContainerHigh = Color(0xFFEEF5FC),
    surfaceContainerHighest = Color(0xFFE7F0F9),
    outline = Color(0xFF93A7B8),
    outlineVariant = Color(0xFFD8E5F0),
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
    val dynamicColorActive = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = remember(context, darkTheme, dynamicColorActive) {
        when {
            dynamicColorActive -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> MeteorDark
            else -> MeteorLight
        }
    }

    CompositionLocalProvider(
        LocalAnimationsEnabled provides animationsEnabled,
        LocalDynamicColorEnabled provides dynamicColorActive,
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
