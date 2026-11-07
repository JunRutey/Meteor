package com.meteor.memory.ui.life

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meteor.memory.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun LifeClockScreen(
    state: LifePageState,
    onRetry: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenBirthDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStart = rememberUpdatedState(onStart)
    val currentOnStop = rememberUpdatedState(onStop)
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> currentOnStart.value()
                Lifecycle.Event.ON_STOP -> currentOnStop.value()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // Switching the home tab disposes and re-creates this composable without
        // any lifecycle event, so a screen that is already started must kick the
        // ticker off right here — otherwise the countdown would stay frozen.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentOnStart.value()
        onDispose {
            lifecycle.removeObserver(observer)
            currentOnStop.value()
        }
    }

    val palette = lifePalette()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        LifeBackdrop(Modifier.fillMaxSize(), palette)
        when (state) {
            LifePageState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = palette.primaryText,
            )

            LifePageState.Missing -> Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.life_missing_profile),
                    color = palette.primaryText,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.life_missing_profile_body),
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onOpenBirthDate) {
                    Text(text = stringResource(R.string.life_set_birth_date))
                }
            }

            LifePageState.Failed -> Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.life_load_failed),
                    color = palette.primaryText,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.life_retry))
                }
            }

            is LifePageState.Ready -> LifeClockContent(
                profile = state.profile,
                metrics = state.metrics,
                palette = palette,
            )
        }
    }
}

@Composable
private fun LifeClockContent(
    profile: BirthProfile,
    metrics: BirthdayMetrics,
    palette: LifePalette,
) {
    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    }
    val solarDate = dateFormatter.format(profile.gregorianDate)
    val birthday = when (profile) {
        is BirthProfile.Solar -> stringResource(R.string.life_birthday_solar, solarDate)
        is BirthProfile.Lunar -> if (profile.leapMonth) {
            stringResource(
                R.string.life_birthday_lunar_leap,
                profile.year,
                profile.month,
                profile.day,
                solarDate,
            )
        } else {
            stringResource(
                R.string.life_birthday_lunar,
                profile.year,
                profile.month,
                profile.day,
                solarDate,
            )
        }
    }
    val age = String.format(locale, "%.9f", metrics.ageYears)
    val days = metrics.secondsUntilBirthday / SECONDS_PER_DAY
    val hours = (metrics.secondsUntilBirthday % SECONDS_PER_DAY) / SECONDS_PER_HOUR
    val minutes = (metrics.secondsUntilBirthday % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = metrics.secondsUntilBirthday % SECONDS_PER_MINUTE
    val nextBirthdayLabel = dateFormatter.format(metrics.nextBirthday)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(palette.badgeBackground, CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.life_online_status),
                color = palette.primaryText,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.size(10.dp))
            Box(
                modifier = Modifier
                    .background(palette.badgeBackground, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.life_elapsed_hours, metrics.elapsedHours),
                    color = palette.badgeText,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Text(
            text = stringResource(R.string.life_age_prefix),
            modifier = Modifier.padding(top = 42.dp),
            color = palette.secondaryText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = age,
            modifier = Modifier.padding(top = 8.dp),
            color = palette.primaryText,
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.life_age_suffix),
            modifier = Modifier.padding(top = 2.dp),
            color = palette.secondaryText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.life_age_caption),
            modifier = Modifier.padding(top = 12.dp),
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        Surface(
            modifier = Modifier.padding(top = 22.dp),
            color = palette.badgeBackground.copy(alpha = 0.16f),
            shape = CircleShape,
        ) {
            Text(
                text = birthday,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(30.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.countdownCard,
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(width = 1.dp, color = palette.countdownBorder),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.life_next_birthday),
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = nextBirthdayLabel,
                    color = palette.primaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CountdownUnit(
                        value = days,
                        label = stringResource(R.string.life_countdown_days),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                    CountdownUnit(
                        value = hours,
                        label = stringResource(R.string.life_countdown_hours),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                    CountdownUnit(
                        value = minutes,
                        label = stringResource(R.string.life_countdown_minutes),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                    CountdownUnit(
                        value = seconds,
                        label = stringResource(R.string.life_countdown_seconds),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            text = stringResource(R.string.life_footer),
            color = palette.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CountdownUnit(
    value: Long,
    label: String,
    palette: LifePalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = palette.card,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, palette.cardBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value.toString(),
                color = palette.primaryText,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = label,
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun LifeBackdrop(modifier: Modifier = Modifier, palette: LifePalette) {
    val stars = remember {
        listOf(
            0.08f to 0.14f, 0.21f to 0.26f, 0.73f to 0.13f, 0.91f to 0.22f,
            0.16f to 0.48f, 0.83f to 0.44f, 0.29f to 0.69f, 0.69f to 0.73f,
            0.9f to 0.82f, 0.1f to 0.91f, 0.52f to 0.17f, 0.46f to 0.86f,
        )
    }
    Canvas(modifier) {
        stars.forEachIndexed { index, (x, y) ->
            drawCircle(
                color = palette.star.copy(alpha = if (index % 3 == 0) 0.75f else 0.4f),
                radius = if (index % 3 == 0) 2.dp.toPx() else 1.dp.toPx(),
                center = Offset(size.width * x, size.height * y),
            )
        }

        val mesh = Path().apply {
            moveTo(size.width * 0.72f, size.height * 0.32f)
            lineTo(size.width * 0.96f, size.height * 0.28f)
            lineTo(size.width * 0.88f, size.height * 0.47f)
            close()
            moveTo(size.width * 0.02f, size.height * 0.62f)
            lineTo(size.width * 0.18f, size.height * 0.57f)
            lineTo(size.width * 0.25f, size.height * 0.72f)
        }
        drawPath(
            path = mesh,
            color = palette.mesh.copy(alpha = 0.28f),
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawLine(
            color = palette.beam.copy(alpha = 0.35f),
            start = Offset(size.width * 0.75f, size.height * 0.05f),
            end = Offset(size.width * 0.59f, size.height * 0.2f),
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = palette.horizon.copy(alpha = 0.14f),
            start = Offset(size.width * 0.02f, size.height * 0.35f),
            end = Offset(size.width * 0.98f, size.height * 0.35f),
            strokeWidth = 0.6.dp.toPx(),
        )
    }
}

/**
 * Same "starry sky" idea in both themes: deep space at night, pale dawn sky in
 * light mode, so the hero screen never fights the themed header and tab bar.
 */
private data class LifePalette(
    val background: Brush,
    val star: Color,
    val mesh: Color,
    val beam: Color,
    val horizon: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val badgeBackground: Color,
    val badgeText: Color,
    val card: Color,
    val cardBorder: Color,
    val countdownCard: Color,
    val countdownBorder: Color,
)

@Composable
private fun lifePalette(): LifePalette {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return if (dark) {
        LifePalette(
            background = Brush.verticalGradient(
                listOf(Color(0xFF07111F), Color(0xFF101A2C), Color(0xFF080D19)),
            ),
            star = Color(0xFFB7D3F5),
            mesh = Color(0xFF8DAFD8),
            beam = Color(0xFFB9D7FC),
            horizon = Color.White,
            primaryText = Color(0xFFF3F7FF),
            secondaryText = Color(0xFFA8B8CF),
            badgeBackground = Color(0xFF426989),
            badgeText = Color.White,
            card = Color.White.copy(alpha = 0.075f),
            cardBorder = Color.White.copy(alpha = 0.14f),
            countdownCard = Color(0xFF152338),
            countdownBorder = Color(0xFF829CBF),
        )
    } else {
        LifePalette(
            background = Brush.verticalGradient(
                listOf(Color(0xFFE9F2FD), Color(0xFFF8FBFF), Color(0xFFDCE7F6)),
            ),
            star = Color(0xFF4C6F9C),
            mesh = Color(0xFF6C8FB8),
            beam = Color(0xFF7592B5),
            horizon = Color(0xFF2A4664),
            primaryText = scheme.onSurface,
            secondaryText = scheme.onSurfaceVariant,
            badgeBackground = scheme.primary,
            badgeText = scheme.onPrimary,
            card = scheme.surfaceContainerHigh,
            cardBorder = scheme.outlineVariant,
            countdownCard = scheme.surfaceContainerLow,
            countdownBorder = scheme.primary.copy(alpha = 0.3f),
        )
    }
}

private const val SECONDS_PER_DAY = 24 * 60 * 60
private const val SECONDS_PER_HOUR = 60 * 60
private const val SECONDS_PER_MINUTE = 60
