package com.meteor.memory.ui.life

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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

/**
 * Shared birth-date editor for first launch and for Settings.
 *
 * The lunar conversion never runs on the main thread: enumerating a lunar year
 * reads one ICU field per Gregorian day, which is too slow for a composable
 * that reacts to every dropdown change. [onProfileChanged] receives `null`
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
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val calendar = remember { ChineseLunarCalendar() }
    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    }

    val initialSolar = (initialProfile as? BirthProfile.Solar)?.gregorianDate
    val initialLunar = initialProfile as? BirthProfile.Lunar

    var useLunar by rememberSaveable { mutableStateOf(initialLunar != null) }
    var solarDateText by rememberSaveable { mutableStateOf(initialSolar?.toString().orEmpty()) }
    var lunarYear by rememberSaveable { mutableStateOf(initialLunar?.year ?: today.year) }
    var lunarMonth by rememberSaveable { mutableStateOf(initialLunar?.month ?: 1) }
    var lunarDay by rememberSaveable { mutableStateOf(initialLunar?.day ?: 1) }
    var leapMonth by rememberSaveable { mutableStateOf(initialLunar?.leapMonth ?: false) }
    var lunarTouched by rememberSaveable { mutableStateOf(false) }
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

    // The conversion runs off the main thread, so right after a dropdown change
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
                val selectedMonthOption = monthOptions.firstOrNull {
                    it.first.month == lunarMonth && it.first.leapMonth == leapMonth
                } ?: monthOptions.firstOrNull {
                    it.first.month == lunarMonth && !it.first.leapMonth
                } ?: monthOptions.first()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceDropdown(
                        label = stringResource(R.string.birth_lunar_year),
                        value = lunarYear.toString(),
                        options = (BirthProfile.MIN_BIRTH_YEAR..today.year)
                            .reversed()
                            .map { it to it.toString() },
                        onSelect = {
                            if (lunarYear != it) {
                                lunarYear = it
                                lunarMonth = 1
                                lunarDay = 1
                                leapMonth = false
                                lunarTouched = true
                            }
                        },
                        modifier = Modifier.weight(1.3f),
                    )
                    ChoiceDropdown(
                        label = stringResource(R.string.birth_lunar_month),
                        value = selectedMonthOption.second,
                        options = monthOptions,
                        onSelect = {
                            lunarMonth = it.month
                            leapMonth = it.leapMonth
                            lunarTouched = true
                        },
                        modifier = Modifier.weight(1.4f),
                    )
                    ChoiceDropdown(
                        label = stringResource(R.string.birth_lunar_day),
                        value = lunarDay.toString(),
                        options = (1..selectedLunarMonth.lastDay).map { it to it.toString() },
                        onSelect = {
                            lunarDay = it
                            lunarTouched = true
                        },
                        modifier = Modifier.weight(0.9f),
                    )
                }
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
        } else {
            val shownDate = solarDate?.let { dateFormatter.format(it) }
            OutlinedButton(
                onClick = {
                    val initial = solarDate ?: today
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            solarDateText = LocalDate.of(year, month + 1, day).toString()
                        },
                        initial.year,
                        initial.monthValue - 1,
                        initial.dayOfMonth,
                    ).apply {
                        datePicker.minDate = LocalDate.of(
                            BirthProfile.MIN_BIRTH_YEAR,
                            1,
                            1,
                        ).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        datePicker.maxDate = System.currentTimeMillis()
                    }.show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = shownDate?.let {
                        stringResource(R.string.birth_date_value, it)
                    } ?: stringResource(R.string.birth_choose_solar_date),
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
private fun <T> ChoiceDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = value, maxLines = 1)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (option, title) ->
                    DropdownMenuItem(
                        text = { Text(text = title) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
