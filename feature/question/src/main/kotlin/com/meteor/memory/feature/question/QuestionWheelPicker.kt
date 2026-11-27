package com.meteor.memory.feature.question

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

/**
 * 提醒时间选择（ADR-069）：与生辰页 `BirthDatePicker` 同一套滚轮。
 *
 * 为什么是滚轮而不是系统 `DatePickerDialog` / `TimePickerDialog`：应用里唯一
 * 另一处日期选择就是生辰页，那里是「三列滚轮 + 点一下选中」。两个入口用两种
 * 控件会让人以为它们不是同一件事；这里把日期与时间压进同一排五列，视觉与
 * 交互都向生辰页看齐。
 *
 * 滚轮本身不提交：拖动只改变预览，点某一行才选中。这一点和生辰页一致，
 * 免得滑过头的那一帧被当成选择。
 */

/** 一行滚轮的高度（与生辰页同值）。 */
private val WheelItemHeight = 36.dp
private val WheelItemSpacing = 2.dp

/** 144dp 视口的一半：选中行正好落在中间。 */
private val WheelCenterPadding = 54.dp
private val WheelViewportHeight = 144.dp

private val WheelPanelShape = RoundedCornerShape(18.dp)
private val WheelRowShape = RoundedCornerShape(12.dp)

@Composable
internal fun QuestionWheelPicker(
    onCancel: () -> Unit,
    onConfirm: (ReminderPick) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「现在」在打开面板时锁定：预览标签与未来校验都以它为准，面板开着时
    // 时间流逝不会把已经选好的时刻突然判成非法。
    val now = remember { ZonedDateTime.now() }
    val seed = remember(now) {
        val at = QuestionSchedule.defaultReminder(now).atZone(now.zone)
        ReminderPick(
            year = at.year,
            month = at.monthValue,
            day = at.dayOfMonth,
            hour = at.hour,
            minute = at.minute,
        )
    }

    var year by remember { mutableIntStateOf(seed.year) }
    var month by remember { mutableIntStateOf(seed.month) }
    var day by remember { mutableIntStateOf(seed.day) }
    var hour by remember { mutableIntStateOf(seed.hour) }
    var minute by remember { mutableIntStateOf(seed.minute) }

    val years = remember(now) { (now.year..(now.year + 5)).toList() }
    val months = remember { (1..12).toList() }
    val days = remember(year, month) { (1..YearMonth.of(year, month).lengthOfMonth()).toList() }
    val hours = remember { (0..23).toList() }
    val minutes = remember { (0..59).toList() }

    fun clampDay(newYear: Int, newMonth: Int) {
        val last = YearMonth.of(newYear, newMonth).lengthOfMonth()
        if (day > last) day = last
    }

    val instant = remember(year, month, day, hour, minute) {
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(now.zone).toInstant()
    }
    val valid = instant.isAfter(now.toInstant())
    val preview = QuestionFormat.reminderLabel(instant, now)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = WheelPanelShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.question_reminder_pick_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QuestionWheelColumn(
                    label = stringResource(R.string.question_wheel_year),
                    options = years,
                    selectedIndex = years.indexOf(year).coerceAtLeast(0),
                    optionLabel = { it.toString() },
                    onSelect = { picked ->
                        year = picked
                        clampDay(picked, month)
                    },
                    modifier = Modifier.weight(1.2f),
                )
                QuestionWheelColumn(
                    label = stringResource(R.string.question_wheel_month),
                    options = months,
                    selectedIndex = (month - 1).coerceAtLeast(0),
                    optionLabel = { it.toString() },
                    onSelect = { picked ->
                        month = picked
                        clampDay(year, picked)
                    },
                    modifier = Modifier.weight(0.7f),
                )
                QuestionWheelColumn(
                    label = stringResource(R.string.question_wheel_day),
                    options = days,
                    selectedIndex = (day - 1).coerceAtLeast(0),
                    optionLabel = { it.toString() },
                    onSelect = { day = it },
                    modifier = Modifier.weight(0.7f),
                )
                QuestionWheelColumn(
                    label = stringResource(R.string.question_wheel_hour),
                    options = hours,
                    selectedIndex = hour,
                    optionLabel = { it.toString().padStart(2, '0') },
                    onSelect = { hour = it },
                    modifier = Modifier.weight(0.7f),
                )
                QuestionWheelColumn(
                    label = stringResource(R.string.question_wheel_minute),
                    options = minutes,
                    selectedIndex = minute,
                    optionLabel = { it.toString().padStart(2, '0') },
                    onSelect = { minute = it },
                    modifier = Modifier.weight(0.7f),
                )
            }

            Text(
                text = stringResource(R.string.question_wheel_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (valid) {
                Text(
                    text = stringResource(R.string.question_reminder_pick_preview, preview),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Text(
                    text = stringResource(R.string.question_reminder_future_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(text = stringResource(R.string.question_picker_cancel))
                }
                Button(
                    onClick = { onConfirm(ReminderPick(year, month, day, hour, minute)) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = valid,
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Text(text = stringResource(R.string.question_picker_confirm))
                }
            }
        }
    }
}

/**
 * 一列滚轮。滚动只预览，点某一行才提交；[selectedIndex] 负责把选中行停在
 * 中间。列表重建（例如换月导致日数变少）时直接定位，不做长距离动画。
 */
@Composable
private fun <T> QuestionWheelColumn(
    label: String,
    options: List<T>,
    selectedIndex: Int,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val centerOffsetPx = with(LocalDensity.current) { WheelCenterPadding.roundToPx() }
    val settledIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    fun restOffset(index: Int): Int = if (index == 0) 0 else -centerOffsetPx

    // Start the wheel on the selected row instead of index 0: the first frame is
    // already in place, so opening the picker never flashes the top value.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = settledIndex,
        initialFirstVisibleItemScrollOffset = restOffset(settledIndex),
    )
    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var lastOptions by remember { mutableStateOf(options) }
    var placedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(options, selectedIndex) {
        if (options.isEmpty()) return@LaunchedEffect
        val optionsChanged = options != lastOptions
        lastOptions = options
        val targetOffset = restOffset(settledIndex)
        if (!placedOnce || optionsChanged) {
            placedOnce = true
            listState.scrollToItem(index = settledIndex, scrollOffset = targetOffset)
        } else {
            // A tap glides the chosen row to the middle instead of jumping.
            listState.animateScrollToItem(index = settledIndex, scrollOffset = targetOffset)
        }
    }

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height(WheelViewportHeight),
            state = listState,
            contentPadding = PaddingValues(vertical = WheelCenterPadding),
            verticalArrangement = Arrangement.spacedBy(WheelItemSpacing),
            flingBehavior = rememberSnapFlingBehavior(listState),
        ) {
            itemsIndexed(options) { index, option ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier.fillMaxWidth().height(WheelItemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    // The tap target is the visual row, not just the digits.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(WheelRowShape)
                            .clickable {
                                currentOptions.getOrNull(index)?.let(currentOnSelect)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = WheelRowShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = optionLabel(option),
                                    fontSize = 15.sp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
