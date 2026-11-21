package com.meteor.memory.ui.life

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LunarMonth(
    val month: Int,
    val leapMonth: Boolean,
    val lastDay: Int,
)

data class LunarDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val leapMonth: Boolean,
)

/**
 * Lunar/Gregorian conversion backed by the published [LunarYearTable] data
 * (ADR-047). Table lookups replace the former day-by-day enumeration through
 * `android.icu.util.ChineseCalendar`, whose simplified ephemeris could put a
 * month start one day away from the official calendar when the new moon falls
 * minutes before a midnight (2027 正月 is the known case).
 *
 * All lookups are pure arithmetic, so every entry point is safe to call from
 * the main thread and needs no cross-instance caching.
 */
class ChineseLunarCalendar {

    /**
     * Months of the lunar [year] in calendar order (正月, its leap month when
     * present, 二月, …). Empty when the year is outside the table's range.
     */
    fun monthsInYear(year: Int): List<LunarMonth> {
        if (!LunarYearTable.covers(year)) return emptyList()
        val months = mutableListOf<LunarMonth>()
        val leap = LunarYearTable.leapMonth(year)
        for (month in 1..12) {
            months += LunarMonth(
                month = month,
                leapMonth = false,
                lastDay = LunarYearTable.monthDays(year, month),
            )
            if (month == leap) {
                months += LunarMonth(
                    month = month,
                    leapMonth = true,
                    lastDay = LunarYearTable.leapMonthDays(year),
                )
            }
        }
        return months
    }

    /**
     * Gregorian date of the given lunar date, or null when the combination is
     * invalid (outside the table, no such leap month, or day past month end).
     */
    fun toGregorian(year: Int, month: Int, day: Int, leapMonth: Boolean): LocalDate? {
        if (!LunarYearTable.covers(year)) return null
        if (month !in 1..12 || day !in 1..30) return null
        val months = monthsInYear(year)
        val target = months.firstOrNull { it.month == month && it.leapMonth == leapMonth } ?: return null
        if (day > target.lastDay) return null
        var offset = 0
        for (earlier in LunarYearTable.MIN_YEAR until year) {
            offset += LunarYearTable.yearDays(earlier)
        }
        for (entry in months) {
            if (entry.month == month && entry.leapMonth == leapMonth) {
                return LunarYearTable.BASE_DATE.plusDays((offset + day - 1).toLong())
            }
            offset += entry.lastDay
        }
        return null
    }

    /** Lunar date of the given Gregorian date, or null outside the table. */
    fun toLunar(date: LocalDate): LunarDate? {
        var offset = date.toEpochDay() - LunarYearTable.BASE_DATE.toEpochDay()
        if (offset < 0) return null
        var year = LunarYearTable.MIN_YEAR
        while (year <= LunarYearTable.MAX_YEAR && offset >= LunarYearTable.yearDays(year)) {
            offset -= LunarYearTable.yearDays(year)
            year++
        }
        if (!LunarYearTable.covers(year)) return null
        for (entry in monthsInYear(year)) {
            if (offset < entry.lastDay) {
                return LunarDate(
                    year = year,
                    month = entry.month,
                    day = offset.toInt() + 1,
                    leapMonth = entry.leapMonth,
                )
            }
            offset -= entry.lastDay
        }
        return null
    }

    /**
     * The first occurrence of [birth]'s lunar month/day that is not before
     * [now]. A birth in a leap month falls back to the regular month in years
     * that have no such leap month, and day 30 falls back to the last day of a
     * 29-day month.
     */
    fun nextBirthday(
        birth: BirthProfile.Lunar,
        now: Instant,
        zone: ZoneId,
    ): LocalDate? {
        val today = now.atZone(zone).toLocalDate()
        for (year in (today.year - 1)..(today.year + 2)) {
            if (!LunarYearTable.covers(year)) continue
            val months = monthsInYear(year)
            val chosenMonth = if (birth.leapMonth) {
                months.firstOrNull { it.month == birth.month && it.leapMonth }
                    ?: months.firstOrNull { it.month == birth.month && !it.leapMonth }
            } else {
                months.firstOrNull { it.month == birth.month && !it.leapMonth }
            } ?: continue
            val day = minOf(birth.day, chosenMonth.lastDay)
            val candidate = toGregorian(year, chosenMonth.month, day, chosenMonth.leapMonth) ?: continue
            if (!candidate.atStartOfDay(zone).toInstant().isBefore(now)) return candidate
        }
        return null
    }

    /**
     * Repairs a stored profile whose Gregorian reference date disagrees with
     * its lunar fields. Older builds computed that date through ICU and could
     * store it one day off; the lunar fields are authoritative.
     */
    fun normalize(profile: BirthProfile): BirthProfile {
        if (profile !is BirthProfile.Lunar) return profile
        val correct = toGregorian(
            year = profile.year,
            month = profile.month,
            day = profile.day,
            leapMonth = profile.leapMonth,
        ) ?: return profile
        return if (correct == profile.gregorianDate) {
            profile
        } else {
            profile.copy(gregorianDate = correct)
        }
    }
}
