package com.meteor.memory.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

/**
 * One place for every motion value in the app.
 *
 * Before this existed the same "fade a panel in" idea was spelled with ten
 * different hardcoded durations (90/100/120/140/150/160/170/180/190/200ms) and
 * two different exit easings, so panels of the same kind moved at visibly
 * different speeds depending on which screen you were on.
 *
 * The durations follow the Material 3 duration scale; the semantic helpers
 * ([enter], [exit], [pageIn], …) are what call sites should use, so a future
 * retune only has to touch this file.
 */
object MotionTokens {

    // --- durations (ms), M3 scale -------------------------------------------
    const val Short2 = 100
    const val Short3 = 150
    const val Short4 = 200
    const val Medium1 = 250
    const val Medium2 = 300
    const val Medium3 = 350

    // --- easings ------------------------------------------------------------
    /** In-place state change: symmetric, no overshoot. */
    val Standard: Easing = FastOutSlowInEasing

    /** Element arriving on screen: starts fast, settles. */
    val Decelerate: Easing = FastOutSlowInEasing

    /** Element leaving: starts slow, accelerates out. */
    val Accelerate: Easing = FastOutLinearInEasing

    /** Emphasised page entrance, slightly longer than a panel. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    // --- semantic specs -----------------------------------------------------

    /** Small panel / chip / overlay appearing. */
    fun <T> enter(): TweenSpec<T> =
        tween(durationMillis = Short4, easing = Decelerate)

    /** The matching exit; always shorter than [enter]. */
    fun <T> exit(): TweenSpec<T> =
        tween(durationMillis = Short3, easing = Accelerate)

    /** Whole-screen push/pop. */
    fun <T> pageIn(): TweenSpec<T> =
        tween(durationMillis = Medium3, easing = EmphasizedDecelerate)

    /** Whole-screen exit. */
    fun <T> pageOut(): TweenSpec<T> =
        tween(durationMillis = Short4, easing = Accelerate)

    /** Lateral slide used by settings detail pages. */
    fun <T> slide(): TweenSpec<T> =
        tween(durationMillis = Medium2, easing = EmphasizedDecelerate)

    /** Height/colour/offset change of a widget that stays on screen. */
    fun <T> state(): TweenSpec<T> =
        tween(durationMillis = Short4, easing = Standard)

    /** List item insertion / removal / move. */
    fun <T> item(): TweenSpec<T> =
        tween(durationMillis = Medium1, easing = Decelerate)
}
