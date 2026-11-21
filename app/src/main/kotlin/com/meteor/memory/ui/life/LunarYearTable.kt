package com.meteor.memory.ui.life

import java.time.LocalDate

/**
 * Precomputed Chinese lunar year data for 1900–2100 (ADR-047).
 *
 * One packed [Int] per lunar year:
 *  - bits 0–3: which month is followed by a leap month, 0 when the year has none
 *  - bit 16: the leap month has 30 days when set, 29 otherwise
 *  - bits 4–15: month lengths; month `m` (1..12) is 30 days when mask
 *    `0x10000 shr m` is set, 29 otherwise
 *
 * Anchor: 1900-01-31 Gregorian is lunar 1900-01-01 (正月初一).
 *
 * The table follows the widely published 1900–2100 lunar data set (the same
 * one behind most Chinese calendar libraries), cross-checked year by year
 * against the official calendar: Spring Festival dates 1933–2028, leap
 * months 1900–2039 (including the 2033 leap eleventh month), the 1933 month
 * lengths published from Purple Mountain Observatory data, and a full
 * round-trip over every day of the range. The 1933 entry is deliberately
 * `0x16a95`, not the `0x06e95` found in some copies of that table: those
 * copies swap the lengths of 闰五月 and 六月.
 *
 * Why a table instead of `android.icu.util.ChineseCalendar`: ICU derives the
 * month boundaries from its own simplified ephemeris, so any computed new moon
 * that lands within a few minutes of a midnight can disagree with the official
 * calendar by one day. 2027-02-06 23:56 (UTC+8) is exactly such a case and put
 * 正月初一 one day late. A published table is deterministic, matches the
 * official calendar, and needs no device-side astronomy.
 */
internal object LunarYearTable {

    const val MIN_YEAR: Int = 1900
    const val MAX_YEAR: Int = 2100

    /** Gregorian date of lunar 1900-01-01. */
    val BASE_DATE: LocalDate = LocalDate.of(1900, 1, 31)

    private val YEAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2, // 1900-1909
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977, // 1910-1919
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, // 1920-1929
        0x06566, 0x0d4a0, 0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, // 1930-1939
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557, // 1940-1949
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, // 1950-1959
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, // 1960-1969
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, // 1970-1979
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570, // 1980-1989
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0, // 1990-1999
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, // 2000-2009
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, // 2010-2019
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530, // 2020-2029
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, // 2030-2039
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, // 2040-2049
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0, // 2050-2059
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4, // 2060-2069
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, // 2070-2079
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, // 2080-2089
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, // 2090-2099
        0x0d520, // 2100
    )

    fun covers(year: Int): Boolean = year in MIN_YEAR..MAX_YEAR

    /** Month followed by a leap month, or 0 when the year has no leap month. */
    fun leapMonth(year: Int): Int = info(year) and 0xF

    /** Days in the leap month; only meaningful when [leapMonth] is non-zero. */
    fun leapMonthDays(year: Int): Int = if (info(year) and 0x10000 != 0) 30 else 29

    /** Days in the regular month [month] (1..12), ignoring any leap month. */
    fun monthDays(year: Int, month: Int): Int {
        require(month in 1..12) { "month out of range: $month" }
        return if (info(year) and (0x10000 shr month) != 0) 30 else 29
    }

    fun yearDays(year: Int): Int {
        var total = 0
        for (month in 1..12) total += monthDays(year, month)
        if (leapMonth(year) != 0) total += leapMonthDays(year)
        return total
    }

    private fun info(year: Int): Int {
        require(covers(year)) { "lunar year outside $MIN_YEAR..$MAX_YEAR: $year" }
        return YEAR_INFO[year - MIN_YEAR]
    }
}
