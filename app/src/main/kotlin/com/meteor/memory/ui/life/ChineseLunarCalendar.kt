package com.meteor.memory.ui.life

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

data class LunarMonth(
    val month: Int,
    val leapMonth: Boolean,
    val lastDay: Int,
)

class ChineseLunarCalendar {

    /**
     * Enumerating a lunar year costs roughly one ICU field read per Gregorian
     * day, and the picker asks for the same year over and over while the user
     * scrolls. Caching keeps a year to a single enumeration; the bound keeps
     * the memory constant if someone drags through the whole year list.
     */
    private val yearCache = ConcurrentHashMap<Int, List<LunarDay>>()

    fun monthsInYear(year: Int): List<LunarMonth> =
        daysInYear(year)
            .groupBy { it.month to it.leapMonth }
            .map { (key, days) ->
                LunarMonth(
                    month = key.first,
                    leapMonth = key.second,
                    lastDay = days.maxOf { it.day },
                )
            }

    fun toGregorian(year: Int, month: Int, day: Int, leapMonth: Boolean): LocalDate? {
        if (year !in BirthProfile.MIN_BIRTH_YEAR..MAX_LUNAR_YEAR) return null
        if (month !in 1..12 || day !in 1..30) return null
        val lunarMonth = monthsInYear(year).firstOrNull {
            it.month == month && it.leapMonth == leapMonth
        } ?: return null
        if (day > lunarMonth.lastDay) return null
        return daysInYear(year).firstOrNull {
            it.month == month && it.leapMonth == leapMonth && it.day == day
        }?.date
    }

    fun nextBirthday(
        birth: BirthProfile.Lunar,
        now: java.time.Instant,
        zone: ZoneId,
    ): LocalDate? {
        val today = now.atZone(zone).toLocalDate()
        for (year in (today.year - 1)..(today.year + 2)) {
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

    private fun daysInYear(year: Int): List<LunarDay> {
        require(year in BirthProfile.MIN_BIRTH_YEAR..MAX_LUNAR_YEAR)
        yearCache[year]?.let { return it }
        val computed = enumerateDays(year)
        if (yearCache.size >= MAX_CACHED_YEARS) yearCache.clear()
        yearCache[year] = computed
        return computed
    }

    private fun enumerateDays(year: Int): List<LunarDay> {
        val calendar = ChineseCalendar(CHINA_TIME_ZONE, ULocale.CHINA)
        val anchor = calendarFields(calendar, LocalDate.of(year, 6, 1))
        val result = mutableListOf<LunarDay>()
        var date = LocalDate.of(year, 1, 1)
        val end = LocalDate.of(year + 1, 3, 1)
        while (date.isBefore(end)) {
            val fields = calendarFields(calendar, date)
            if (fields.era == anchor.era && fields.year == anchor.year) {
                result += LunarDay(
                    date = date,
                    month = fields.month + 1,
                    day = fields.day,
                    leapMonth = fields.leapMonth,
                )
            }
            date = date.plusDays(1)
        }
        return result
    }

    private fun calendarFields(calendar: ChineseCalendar, date: LocalDate): ChineseFields {
        calendar.timeInMillis = date.atTime(12, 0).atZone(CHINA_ZONE_ID).toInstant().toEpochMilli()
        return ChineseFields(
            // ERA / YEAR / MONTH / DAY_OF_MONTH are declared on the ICU Calendar
            // base class; name them there so the reference cannot depend on how
            // Kotlin treats inherited Java statics.
            era = calendar.get(Calendar.ERA),
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH),
            day = calendar.get(Calendar.DAY_OF_MONTH),
            leapMonth = calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1,
        )
    }

    private data class ChineseFields(
        val era: Int,
        val year: Int,
        val month: Int,
        val day: Int,
        val leapMonth: Boolean,
    )

    private data class LunarDay(
        val date: LocalDate,
        val month: Int,
        val day: Int,
        val leapMonth: Boolean,
    )

    private companion object {
        const val MAX_LUNAR_YEAR = 2200
        const val MAX_CACHED_YEARS = 8
        val CHINA_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
        val CHINA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
}
