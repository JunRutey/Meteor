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
 * Colour roles are sampled from the app icon art
 * (`res/drawable-xxxhdpi/splash_icon.png`, clustered around the periwinkle
 * royal blue #4C77FC with a lighter tint at #7599FC) and lifted into a usable
 * Material 3 sheet. The result is a fresh periwinkle-blue identity with two
 * deliberately mixed partners so the scheme never reads as monochrome blue:
 * a cool sky/teal secondary and a warm amber tertiary accent.
 *
 * Light — periwinkle paper (#F7FAFF) over white cards, ink (#131A2A),
 * icon-blue accents darkened for white text (#3B60E4 / #0F6E8C),
 * soft blue fills (#DCE4FF / #D5EFF9 / #E7EEFC) and a single
 * periwinkle hairline (#D5DEF7).
 *
 * Dark — deep periwinkle night (#0D1226) with the icon blue lifted toward
 * #A9BEFF so it still reads as the same hue, ink (#E6EBFA), and matching
 * desaturated fills (#2A3A85 / #16495C / #232C46).
 *
 * Contrast bar: `onPrimary` is >= 5.2:1 on `primary` in light mode (the raw
 * icon blue #4C77FC is only 3.9:1 against white, so light `primary` is shifted
 * to a deeper periwinkle); body ink stays >= 15:1 on every surface.
 */
private val MeteorDark = darkColorScheme(
    primary = Color(0xFFA9BEFF),
    onPrimary = Color(0xFF0E1A4A),
    primaryContainer = Color(0xFF2A3A85),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF8FD3E8),
    onSecondary = Color(0xFF00333F),
    secondaryContainer = Color(0xFF16495C),
    onSecondaryContainer = Color(0xFFC9ECF8),
    tertiary = Color(0xFFF3BE8A),
    onTertiary = Color(0xFF46290E),
    tertiaryContainer = Color(0xFF6B4420),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF0D1226),
    onBackground = Color(0xFFE6EBFA),
    surface = Color(0xFF141A2E),
    onSurface = Color(0xFFE6EBFA),
    surfaceVariant = Color(0xFF232C46),
    onSurfaceVariant = Color(0xFFAFB9D4),
    surfaceContainerLowest = Color(0xFF090D1C),
    surfaceContainerLow = Color(0xFF111729),
    surfaceContainer = Color(0xFF151C31),
    surfaceContainerHigh = Color(0xFF1A2239),
    surfaceContainerHighest = Color(0xFF212A44),
    outline = Color(0xFF8290B4),
    outlineVariant = Color(0xFF364260),
)

private val MeteorLight = lightColorScheme(
    primary = Color(0xFF3B60E4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF16225E),
    secondary = Color(0xFF0F6E8C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5EFF9),
    onSecondaryContainer = Color(0xFF04303D),
    tertiary = Color(0xFFA85B22),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDFC0),
    onTertiaryContainer = Color(0xFF391A00),
    background = Color(0xFFF7FAFF),
    onBackground = Color(0xFF131A2A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF131A2A),
    surfaceVariant = Color(0xFFE7EEFC),
    onSurfaceVariant = Color(0xFF4C5A73),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFDFF),
    surfaceContainer = Color(0xFFF7FAFF),
    surfaceContainerHigh = Color(0xFFEFF4FE),
    surfaceContainerHighest = Color(0xFFE8EFFC),
    outline = Color(0xFF8A9AC0),
    outlineVariant = Color(0xFFD5DEF7),
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
