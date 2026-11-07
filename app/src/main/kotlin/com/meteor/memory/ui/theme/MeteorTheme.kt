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

private val MeteorDark = darkColorScheme(
    primary = Color(0xFFA8CDBD),
    onPrimary = Color(0xFF19372D),
    primaryContainer = Color(0xFF315347),
    onPrimaryContainer = Color(0xFFD0E9DA),
    secondary = Color(0xFFC6C8B9),
    onSecondary = Color(0xFF30332B),
    secondaryContainer = Color(0xFF46493F),
    onSecondaryContainer = Color(0xFFE2E3D4),
    tertiary = Color(0xFFE2B98C),
    onTertiary = Color(0xFF402C17),
    tertiaryContainer = Color(0xFF5A432B),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF111A17),
    onBackground = Color(0xFFDFE5DF),
    surface = Color(0xFF18211D),
    onSurface = Color(0xFFDFE5DF),
    surfaceVariant = Color(0xFF293630),
    onSurfaceVariant = Color(0xFFC0CBC2),
    outline = Color(0xFF89968C),
    outlineVariant = Color(0xFF414D45),
)

private val MeteorLight = lightColorScheme(
    primary = Color(0xFF42675C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E7D9),
    onPrimaryContainer = Color(0xFF132F27),
    secondary = Color(0xFF625F53),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E3D3),
    onSecondaryContainer = Color(0xFF201F17),
    tertiary = Color(0xFF815C37),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2D1600),
    background = Color(0xFFF6F6F0),
    onBackground = Color(0xFF191D1A),
    surface = Color(0xFFFFFEF9),
    onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFE9EAE2),
    onSurfaceVariant = Color(0xFF454A44),
    outline = Color(0xFF747A73),
    outlineVariant = Color(0xFFC5CBC2),
)

private val MeteorShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
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
