package com.meteor.memory.feature.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Inclusive calendar window named by the user's own words. */
data class DateWindow(val from: LocalDate, val to: LocalDate, val label: String) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

/**
 * Deterministic, offline parser for the time phrases people use in questions.
 *
 * Only the *question* is read (never record text), and every phrase maps to an
 * inclusive `[from, to]` window. Unrecognised text means "no window", so the
 * caller falls back to keyword retrieval. Labels stay ASCII because they are
 * prompt data, not UI copy.
 */
object TimePhrase {

    private val isoDate = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")
    private val cnDate = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")
    private val cnMonth = Regex("""(\d{4})年(\d{1,2})月""")
    private val monthDay = Regex("""(?<!\d)(\d{1,2})月(\d{1,2})日""")
    private val recentDays = Regex("""最近\s*(\d{1,3})\s*[天日]""")

    fun parse(question: String, today: LocalDate): DateWindow? {
        recentDays.find(question)?.let { match ->
            val days = match.groupValues[1].toIntOrNull()?.coerceIn(1, 366) ?: return null
            return DateWindow(today.minusDays(days - 1L), today, "last ${days} days")
        }
        isoDate.find(question)?.let { match ->
            return dayWindow(
                match.groupValues[1].toIntOrNull(),
                match.groupValues[2].toIntOrNull(),
                match.groupValues[3].toIntOrNull(),
            )
        }
        cnDate.find(question)?.let { match ->
            return dayWindow(
                match.groupValues[1].toIntOrNull(),
                match.groupValues[2].toIntOrNull(),
                match.groupValues[3].toIntOrNull(),
            )
        }
        cnMonth.find(question)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return null
            val month = match.groupValues[2].toIntOrNull() ?: return null
            return monthWindow(year, month)
        }
        monthDay.find(question)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return null
            val day = match.groupValues[2].toIntOrNull() ?: return null
            val candidate = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
                ?: return null
            // A bare "M月D日" in January usually means the previous year.
            val date = if (candidate.isAfter(today)) candidate.minusYears(1) else candidate
            return DateWindow(date, date, date.toString())
        }

        return when {
            "前天" in question -> single(today.minusDays(2), "day before yesterday")
            "昨天" in question || "昨日" in question -> single(today.minusDays(1), "yesterday")
            "今天" in question || "今日" in question -> single(today, "today")
            "明天" in question -> single(today.plusDays(1), "tomorrow")
            "上周" in question || "上星期" in question || "上礼拜" in question -> week(today, -1)
            "本周" in question || "这周" in question || "本星期" in question || "这礼拜" in question ->
                week(today, 0)
            "上个月" in question || "上月" in question -> monthOf(today, -1)
            "这个月" in question || "本月" in question -> monthOf(today, 0)
            "去年" in question -> yearOf(today, -1)
            "今年" in question -> yearOf(today, 0)
            else -> null
        }
    }

    private fun dayWindow(year: Int?, month: Int?, day: Int?): DateWindow? {
        if (year == null || month == null || day == null) return null
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return single(date, date.toString())
    }

    private fun monthWindow(year: Int, month: Int): DateWindow? {
        if (month !in 1..12) return null
        val first = runCatching { LocalDate.of(year, month, 1) }.getOrNull() ?: return null
        return DateWindow(first, first.withDayOfMonth(first.lengthOfMonth()), "${year}-${month.toString().padStart(2, '0')}")
    }

    private fun single(date: LocalDate, label: String) = DateWindow(date, date, label)

    private fun week(today: LocalDate, offset: Long): DateWindow {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
        return DateWindow(monday, monday.plusDays(6), if (offset == 0L) "this week" else "last week")
    }

    private fun monthOf(today: LocalDate, offset: Long): DateWindow {
        val first = today.withDayOfMonth(1).plusMonths(offset)
        val label = if (offset == 0L) "this month" else "last month"
        return DateWindow(first, first.withDayOfMonth(first.lengthOfMonth()), label)
    }

    private fun yearOf(today: LocalDate, offset: Long): DateWindow {
        val year = today.year + offset
        val label = if (offset == 0L) "this year" else "last year"
        return DateWindow(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), label)
    }
}
