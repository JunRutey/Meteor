package com.meteor.memory.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meteor.memory.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 生辰页：淡蓝晨光渐变 + 在线时长 + 大字号年龄 + 下次生日倒计时。
 *
 * 视觉按设计稿：76sp 整数部分与 32sp 小数部分同行、20dp 白色倒计时卡片、
 * 8dp 进度条与 13% 留白。年龄每秒刷新，直接复用 [LifePageState] 的指标。
 */
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

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (dark) {
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                        ),
                    )
                } else {
                    Brush.verticalGradient(
                        0f to Color(0xFFEAF2FB),
                        0.55f to Color(0xFFF4F8FC),
                        1f to Color(0xFFE3ECF7),
                    )
                },
            ),
    ) {
        when (state) {
            LifePageState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )

            LifePageState.Missing -> Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.life_missing_profile),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.life_missing_profile_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.life_retry))
                }
            }

            is LifePageState.Ready -> LifeClockContent(
                profile = state.profile,
                metrics = state.metrics,
            )
        }
    }
}

@Composable
private fun LifeClockContent(
    profile: BirthProfile,
    metrics: BirthdayMetrics,
) {
    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    }
    // A lunar birthday only shows its lunar form: pairing it with the Gregorian
    // date of the birth year invites a comparison against the next birthday's
    // Gregorian date, and those two legitimately differ because a fixed lunar
    // date drifts across Gregorian years.
    val birthday = when (profile) {
        is BirthProfile.Solar -> stringResource(
            R.string.life_birthday_solar,
            dateFormatter.format(profile.gregorianDate),
        )

        is BirthProfile.Lunar -> if (profile.leapMonth) {
            stringResource(
                R.string.life_birthday_lunar_leap,
                profile.year,
                profile.month,
                profile.day,
            )
        } else {
            stringResource(
                R.string.life_birthday_lunar,
                profile.year,
                profile.month,
                profile.day,
            )
        }
    }

    val ageText = String.format(locale, "%.9f", metrics.ageYears)
    val wholeAge = ageText.substringBefore('.')
    val fractionAge = "." + ageText.substringAfter('.')
    val days = metrics.secondsUntilBirthday / SECONDS_PER_DAY
    val hours = (metrics.secondsUntilBirthday % SECONDS_PER_DAY) / SECONDS_PER_HOUR
    val minutes = (metrics.secondsUntilBirthday % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = metrics.secondsUntilBirthday % SECONDS_PER_MINUTE
    val nextBirthdayLabel = dateFormatter.format(metrics.nextBirthday)
    val progress = ((metrics.ageYears % 1.0) * 100.0).toInt().coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.life_online_status),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.life_elapsed_hours, metrics.elapsedHours),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.life_age_prefix),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = wholeAge,
                    fontSize = 76.sp,
                    lineHeight = 80.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
                Text(
                    text = fractionAge,
                    fontSize = 32.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.life_age_suffix),
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.life_age_caption),
                modifier = Modifier.padding(top = 12.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            // A fixed height clipped the chip once the label wrapped (long lunar
            // dates and large font scales). Let it grow; single-line content
            // still keeps the 36dp pill shape.
            modifier = Modifier
                .padding(top = 12.dp)
                .heightIn(min = 36.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = birthday,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(20.dp),
                )
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.life_next_birthday_label, nextBirthdayLabel),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CountdownUnit(
                    value = days,
                    label = stringResource(R.string.life_countdown_days),
                    modifier = Modifier.weight(1f),
                )
                CountdownUnit(
                    value = hours,
                    label = stringResource(R.string.life_countdown_hours),
                    modifier = Modifier.weight(1f),
                )
                CountdownUnit(
                    value = minutes,
                    label = stringResource(R.string.life_countdown_minutes),
                    modifier = Modifier.weight(1f),
                )
                CountdownUnit(
                    value = seconds,
                    label = stringResource(R.string.life_countdown_seconds),
                    modifier = Modifier.weight(1f),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(8.dp)
                    .background(Color(0xFFE3ECF7), RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress / 100f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = stringResource(R.string.life_year_progress, progress),
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.life_footer),
            modifier = Modifier.padding(top = 28.dp, bottom = 24.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CountdownUnit(
    value: Long,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 76.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(16.dp),
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = value.toString(),
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
        )
        Text(
            text = label,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val SECONDS_PER_DAY = 24 * 60 * 60
private const val SECONDS_PER_HOUR = 60 * 60
private const val SECONDS_PER_MINUTE = 60
