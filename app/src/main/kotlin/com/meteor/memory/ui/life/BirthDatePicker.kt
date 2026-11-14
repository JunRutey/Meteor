package com.meteor.memory.ui.life

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

private data class LunarDateSelection(
    val year: Int,
    val month: Int,
    val day: Int,
    val leapMonth: Boolean,
)

private data class LunarDateConversion(
    val selection: LunarDateSelection,
    val gregorianDate: LocalDate?,
)

/**
 * A lunar year spans two Gregorian years, so a usable conversion has to land in
 * the selected year or the one right after it; anything else means the ICU
 * result does not belong to this selection.
 */
private fun isUsableLunarDate(date: LocalDate?, lunarYear: Int): Boolean =
    date != null && (date.year == lunarYear || date.year == lunarYear + 1)

/** Height of one wheel row, including the gap below it. */
private val DateWheelRowHeight = 38.dp
private val DateWheelRowGap = 2.dp

/**
 * The scrolling wheel: four rows plus the gaps between them (158dp). The
 * window must be a whole number of rows, or the row resting on the centre line
 * would sit half an item off when the wheel snaps back.
 */
private val DateWheelHeight = 158.dp

/**
 * Shared birth-date editor for first launch and for Settings.
 *
 * The wheels only scroll: dragging one never changes the date, because picking
 * mid-flick would save whichever day happened to pass the centre line. A
 * released wheel snaps back (or continues its fling) to the row that was
 * selected before, and only a tap commits a value.
 *
 * The lunar conversion never runs on the main thread: enumerating a lunar year
 * reads one ICU field per Gregorian day, which is too slow for a composable
 * that reacts to every wheel change. [onProfileChanged] receives `null`
 * while the current calendar has no valid, explicitly chosen date; it is not
 * called while the conversion for the latest selection is still running, so
 * callers keep the last settled profile instead of seeing a flash of `null`.
 */
@Composable
internal fun BirthDatePicker(
    initialProfile: BirthProfile?,
    modifier: Modifier = Modifier,
    onProfileChanged: (BirthProfile?) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val calendar = remember { ChineseLunarCalendar() }
    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    }

    val initialSolar = (initialProfile as? BirthProfile.Solar)?.gregorianDate
    val initialLunar = initialProfile as? BirthProfile.Lunar

    var useLunar by rememberSaveable { mutableStateOf(initialProfile !is BirthProfile.Solar) }
    var solarDateText by rememberSaveable { mutableStateOf(initialSolar?.toString().orEmpty()) }
    var solarWheelDateText by rememberSaveable {
        mutableStateOf((initialSolar ?: today).toString())
    }
    var lunarYear by rememberSaveable { mutableStateOf(initialLunar?.year ?: today.year) }
    var lunarMonth by rememberSaveable { mutableStateOf(initialLunar?.month ?: 1) }
    var lunarDay by rememberSaveable { mutableStateOf(initialLunar?.day ?: 1) }
    var leapMonth by rememberSaveable { mutableStateOf(initialLunar?.leapMonth ?: false) }
    var lunarTouched by rememberSaveable { mutableStateOf(false) }
    var pickerExpanded by rememberSaveable { mutableStateOf(false) }
    val lunarSelection = LunarDateSelection(lunarYear, lunarMonth, lunarDay, leapMonth)

    var lunarMonths by remember { mutableStateOf<List<LunarMonth>>(emptyList()) }
    LaunchedEffect(useLunar, lunarYear) {
        lunarMonths = if (useLunar) {
            withContext(Dispatchers.Default) { calendar.monthsInYear(lunarYear) }
        } else {
            emptyList()
        }
    }

    var lunarConversion by remember { mutableStateOf<LunarDateConversion?>(null) }
    LaunchedEffect(useLunar, lunarSelection) {
        val date = if (useLunar) {
            withContext(Dispatchers.Default) {
                calendar.toGregorian(
                    lunarSelection.year,
                    lunarSelection.month,
                    lunarSelection.day,
                    lunarSelection.leapMonth,
                )
            }
        } else {
            null
        }
        lunarConversion = LunarDateConversion(lunarSelection, date)
    }

    // The conversion runs off the main thread, so right after a wheel change
    // the stored result still belongs to the previous selection. Publishing
    // `null` for those frames would flicker the Save button and the "choose a
    // date" hint; keep the last settled profile until the new result lands.
    val conversionPending = useLunar && lunarConversion?.selection != lunarSelection
    val settledLunarDate = lunarConversion
        ?.takeIf { it.selection == lunarSelection }
        ?.gregorianDate

    val selectedLunarMonth = lunarMonths.firstOrNull {
        it.month == lunarMonth && it.leapMonth == leapMonth
    } ?: lunarMonths.firstOrNull()

    LaunchedEffect(lunarMonths, lunarMonth, leapMonth) {
        val month = lunarMonths.firstOrNull {
            it.month == lunarMonth && it.leapMonth == leapMonth
        } ?: return@LaunchedEffect
        if (lunarDay > month.lastDay) lunarDay = month.lastDay
    }

    val solarDate = remember(solarDateText) {
        solarDateText.takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
    }
    val solarWheelDate = remember(solarWheelDateText) {
        runCatching { LocalDate.parse(solarWheelDateText) }.getOrNull() ?: today
    }

    val computedProfile: BirthProfile? = if (useLunar) {
        val date = settledLunarDate
        if (date != null && isUsableLunarDate(date, lunarYear) && !date.isAfter(today)) {
            BirthProfile.Lunar(
                year = lunarYear,
                month = lunarMonth,
                day = lunarDay,
                leapMonth = leapMonth,
                gregorianDate = date,
            )
        } else {
            null
        }
    } else {
        solarDate?.takeIf { !it.isAfter(today) }?.let { BirthProfile.Solar(it) }
    }

    // An explicit choice is required: keeping a default date would silently save
    // "today" (or this year's first lunar month) for anyone who just taps through.
    val hasSelection = if (useLunar) lunarTouched else solarDate != null
    val initialForCalendar = when {
        useLunar && initialProfile is BirthProfile.Lunar -> initialProfile
        !useLunar && initialProfile is BirthProfile.Solar -> initialProfile
        else -> null
    }
    if (!conversionPending) {
        SideEffect {
            onProfileChanged(if (hasSelection) computedProfile else initialForCalendar)
        }
    }

    val invalidSelection = if (useLunar) {
        val date = settledLunarDate
        date != null && (date.isAfter(today) || !isUsableLunarDate(date, lunarYear))
    } else {
        solarDate?.isAfter(today) == true
    }

    fun updateSolarDate(year: Int, month: Int, day: Int) {
        val safeMonth = month.coerceAtMost(if (year == today.year) today.monthValue else 12)
        val lastDay = if (year == today.year && safeMonth == today.monthValue) {
            today.dayOfMonth
        } else {
            YearMonth.of(year, safeMonth).lengthOfMonth()
        }
        val date = LocalDate.of(year, safeMonth, day.coerceAtMost(lastDay))
        solarWheelDateText = date.toString()
        solarDateText = date.toString()
    }

    val solarYearOptions = remember(today.year) {
        (BirthProfile.MIN_BIRTH_YEAR..today.year).reversed().toList()
    }
    val solarMonthOptions = remember(solarWheelDate.year, today) {
        (1..if (solarWheelDate.year == today.year) today.monthValue else 12).toList()
    }
    val solarDayOptions = remember(solarWheelDate.year, solarWheelDate.monthValue, today) {
        val lastDay = if (
            solarWheelDate.year == today.year &&
            solarWheelDate.monthValue == today.monthValue
        ) {
            today.dayOfMonth
        } else {
            solarWheelDate.lengthOfMonth()
        }
        (1..lastDay).toList()
    }
    val lunarYearOptions = remember(today.year) {
        (BirthProfile.MIN_BIRTH_YEAR..today.year).reversed().toList()
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!useLunar) {
                Button(onClick = {}) {
                    Text(text = stringResource(R.string.birth_solar_calendar))
                }
                OutlinedButton(onClick = { useLunar = true }) {
                    Text(text = stringResource(R.string.birth_lunar_calendar))
                }
            } else {
                OutlinedButton(onClick = { useLunar = false }) {
                    Text(text = stringResource(R.string.birth_solar_calendar))
                }
                Button(onClick = {}) {
                    Text(text = stringResource(R.string.birth_lunar_calendar))
                }
            }
        }

        val selectedDateLabel = if (useLunar) {
            val month = selectedLunarMonth
            if (month == null || (!lunarTouched && initialLunar == null)) {
                stringResource(R.string.birth_choose_lunar_date)
            } else {
                val monthLabel = if (month.leapMonth) {
                    stringResource(R.string.birth_lunar_leap_month_option, month.month)
                } else {
                    stringResource(R.string.birth_lunar_month_option, month.month)
                }
                stringResource(R.string.birth_lunar_date_value, lunarYear, monthLabel, lunarDay)
            }
        } else {
            solarDate?.let { stringResource(R.string.birth_date_value, dateFormatter.format(it)) }
                ?: stringResource(R.string.birth_choose_solar_date)
        }
        OutlinedButton(
            onClick = { pickerExpanded = !pickerExpanded },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(text = selectedDateLabel, maxLines = 1)
        }

        AnimatedVisibility(
            visible = pickerExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            if (useLunar) {
                if (selectedLunarMonth == null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
                    }
                } else {
                    val monthOptions = lunarMonths.map { month ->
                        val label = if (month.leapMonth) {
                            stringResource(R.string.birth_lunar_leap_month_option, month.month)
                        } else {
                            stringResource(R.string.birth_lunar_month_option, month.month)
                        }
                        month to label
                    }
                    DateWheelPanel {
                        DateWheelColumn(
                            label = stringResource(R.string.birth_date_year),
                            options = lunarYearOptions,
                            selectedIndex = lunarYearOptions.indexOf(lunarYear).coerceAtLeast(0),
                            optionLabel = { it.toString() },
                            onSelect = {
                                if (lunarYear != it) {
                                    lunarYear = it
                                    lunarMonth = 1
                                    lunarDay = 1
                                    leapMonth = false
                                    lunarTouched = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        DateWheelColumn(
                            label = stringResource(R.string.birth_date_month),
                            options = monthOptions,
                            selectedIndex = monthOptions.indexOfFirst {
                                it.first.month == lunarMonth && it.first.leapMonth == leapMonth
                            }.coerceAtLeast(0),
                            optionLabel = { it.second },
                            onSelect = {
                                lunarMonth = it.first.month
                                leapMonth = it.first.leapMonth
                                lunarTouched = true
                            },
                            modifier = Modifier.weight(1.25f),
                        )
                        DateWheelColumn(
                            label = stringResource(R.string.birth_date_day),
                            options = (1..selectedLunarMonth.lastDay).toList(),
                            selectedIndex = (lunarDay - 1).coerceAtLeast(0),
                            optionLabel = { it.toString() },
                            onSelect = {
                                lunarDay = it
                                lunarTouched = true
                            },
                            modifier = Modifier.weight(0.8f),
                        )
                    }
                }
            } else {
                DateWheelPanel {
                    DateWheelColumn(
                        label = stringResource(R.string.birth_date_year),
                        options = solarYearOptions,
                        selectedIndex = solarYearOptions.indexOf(solarWheelDate.year).coerceAtLeast(0),
                        optionLabel = { it.toString() },
                        onSelect = {
                            updateSolarDate(it, solarWheelDate.monthValue, solarWheelDate.dayOfMonth)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DateWheelColumn(
                        label = stringResource(R.string.birth_date_month),
                        options = solarMonthOptions,
                        selectedIndex = (solarWheelDate.monthValue - 1).coerceAtLeast(0),
                        optionLabel = { it.toString() },
                        onSelect = {
                            updateSolarDate(solarWheelDate.year, it, solarWheelDate.dayOfMonth)
                        },
                        modifier = Modifier.weight(0.8f),
                    )
                    DateWheelColumn(
                        label = stringResource(R.string.birth_date_day),
                        options = solarDayOptions,
                        selectedIndex = (solarWheelDate.dayOfMonth - 1).coerceAtLeast(0),
                        optionLabel = { it.toString() },
                        onSelect = {
                            updateSolarDate(solarWheelDate.year, solarWheelDate.monthValue, it)
                        },
                        modifier = Modifier.weight(0.8f),
                    )
                }
            }
        }

        if (pickerExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (useLunar) {
                    settledLunarDate?.let { date ->
                        Text(
                            text = stringResource(
                                R.string.birth_date_value,
                                dateFormatter.format(date),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.birth_wheel_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (invalidSelection) {
            Text(
                text = stringResource(R.string.birth_date_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DateWheelPanel(content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Where the first item starts so that [index] sits exactly on the centre line. */
internal fun wheelScrollOffsetFor(
    index: Int,
    viewportHeight: Int,
    itemBlockSize: Int,
): Int = index * itemBlockSize - (viewportHeight - itemBlockSize) / 2

/** One row plus the gap below it: how far the wheel moves per item. */
internal fun wheelItemBlockSize(rowHeight: Dp, rowGap: Dp): Int =
    (rowHeight + rowGap).roundToPx()

/**
 * One wheel column. Scrolling is preview only: the list eases back to
 * [selectedIndex] once the finger lifts, and a row is committed only when it
 * is tapped.
 */
@Composable
private fun <T> DateWheelColumn(
    label: String,
    options: List<T>,
    selectedIndex: Int,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemBlockSize = with(LocalDensity.current) {
        wheelItemBlockSize(DateWheelRowHeight, DateWheelRowGap)
    }
    val settledIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun settledScrollOffset(): Int = wheelScrollOffsetFor(
        index = settledIndex,
        viewportHeight = listState.layoutInfo.viewportSize.height,
        itemBlockSize = itemBlockSize,
    )

    LaunchedEffect(options, selectedIndex) {
        if (options.isNotEmpty()) {
            listState.scrollToItem(index = settledIndex, scrollOffset = settledScrollOffset())
        }
    }

    // Dragging is preview only. Once it stops, ease back to the committed row
    // instead of adopting whatever passed the centre line: a flick without a
    // tap must leave the date untouched.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                val targetOffset = settledScrollOffset()
                if (listState.firstVisibleItemIndex != settledIndex ||
                    listState.firstVisibleItemScrollOffset != targetOffset
                ) {
                    listState.animateScrollToItem(
                        index = settledIndex,
                        scrollOffset = targetOffset,
                    )
                }
            }
    }

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth().height(DateWheelHeight)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(DateWheelHeight),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(DateWheelRowGap),
                flingBehavior = rememberSnapFlingBehavior(listState),
            ) {
                itemsIndexed(options) { index, option ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(DateWheelRowHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The tap target is the visual row, so picking a value
                        // does not depend on hitting the label precisely.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                )
                                .clickable {
                                    currentOptions.getOrNull(index)?.let(currentOnSelect)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = optionLabel(option),
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
            // A hairline marks the row the committed date sits on. It never
            // intercepts touches, so a drag anywhere on the wheel still scrolls.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
