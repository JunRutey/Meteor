package com.meteor.memory.feature.question

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Presentation models for 每日一问 (ADR-068).
 *
 * Everything here is **already formatted**: the view models resolve localized
 * dates and minute-precision times, so the Compose screens stay purely
 * presentational — no formatting, no `core-storage` types, no clock.
 */

/** One row of the question list. */
data class QuestionRow(
    val id: String,
    val text: String,
    /** When the question was written, e.g. `11月27日 22:40`. */
    val createdLabel: String,
    val answeredCount: Int,
    /** The last answer, first line only — the list is a glance, not a reader. */
    val answerPreview: String?,
    /** When the last answer was written; null while the question is still open. */
    val lastAnsweredLabel: String?,
    /** Next pending reminder; null when nothing is scheduled. */
    val nextReminderLabel: String?,
) {
    val isAnswered: Boolean get() = answeredCount > 0
}

/** One reminder of the question being read. */
data class ReminderRow(
    val at: Instant,
    /** `今天 21:00` / `明天 09:00` / `11月29日 09:00`. */
    val label: String,
    /** True when this instant has already passed; it can only be removed. */
    val expired: Boolean,
)

/** One answer, in the order it was written. */
data class AnswerRow(
    val id: String,
    val text: String,
    /** Minute precision: 回答时间必须精确到分钟. */
    val answeredAtLabel: String,
)

/**
 * State of the compose page.
 *
 * [reminders] is the set the question will be created with. It starts with one
 * entry (tomorrow, same clock time) so a person who writes a thought and taps
 * 保存 immediately still gets a reminder without configuring anything.
 *
 * [dateLabel] is non-null only when the question is being backfilled for a day
 * other than today (ADR-070), so the page can say "为 11月20日 提问".
 */
data class QuestionComposerState(
    val draft: String = "",
    val reminders: List<ReminderRow> = emptyList(),
    val dateLabel: String? = null,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = draft.isNotBlank() && !submitting
}

/**
 * State of one question's detail page.
 *
 * [editing] is owned by the view model: the page always opens in reading mode,
 * and only the explicit answer action (or its cancel) flips it. The screen never
 * decides the mode from "has answers".
 */
data class QuestionDetailUi(
    val id: String,
    val text: String,
    val createdLabel: String,
    val reminders: List<ReminderRow>,
    val answers: List<AnswerRow>,
    /** Draft of the answer currently being written. */
    val draft: String = "",
    val editing: Boolean = false,
    /** Preview of the draft, i.e. the 编辑 / 预览 switch of the answer editor. */
    val previewing: Boolean = false,
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    /** False once the question file is gone (deleted here or elsewhere). */
    val exists: Boolean = true,
    val loading: Boolean = true,
) {
    /** True while a saved answer is showing, i.e. the reading view is possible. */
    val hasAnswers: Boolean get() = answers.isNotEmpty()

    /**
     * True when the editor holds text that is not in the archive yet.
     *
     * [saved] resets on the next keystroke, so "just saved" never reads as dirty.
     */
    val hasUnsavedChanges: Boolean get() = editing && draft.isNotBlank() && !saved
}

/**
 * Date/time rendering for 每日一问 (ADR-068).
 *
 * Pure functions so the labels are unit-testable and the Compose screens never
 * format anything themselves. Two rules from the feature request drive this:
 *
 * 1. **回答时间精确到分钟** — an answer is stamped `11月28日 09:01`, never just a date.
 * 2. A reminder that lands tomorrow is named by its clock time (`明天 09:00`)
 *    rather than by a full date, because that is how a person says it out loud.
 */
object QuestionFormat {

    /** `11月27日 22:40` — minute precision; the year only when it is not this year. */
    fun stamp(instant: Instant, now: ZonedDateTime): String {
        val at = instant.atZone(now.zone)
        val date = if (at.year == now.year) MONTH_DAY.format(at) else YEAR_MONTH_DAY.format(at)
        return "$date ${MINUTE.format(at)}"
    }

    /**
     * Reminder label relative to [now]: `今天 21:00` / `明天 09:00` / `11月29日 09:00`.
     *
     * A past instant is rendered as an absolute stamp: it only appears in the
     * "expired" state, where "today" would read as if it were still ahead.
     */
    fun reminderLabel(instant: Instant, now: ZonedDateTime): String {
        val at = instant.atZone(now.zone)
        val today = now.toLocalDate()
        val time = MINUTE.format(at)
        return when (at.toLocalDate()) {
            today -> "$TODAY $time"
            today.plusDays(1) -> "$TOMORROW $time"
            else -> stamp(instant, now)
        }
    }

    /**
     * Day-only label used by the backfill compose page (ADR-070).
     *
     * `今天` / `11月20日` / `2025年11月20日` — the same relative vocabulary the
     * reminder labels use, without a clock time.
     */
    fun dayLabel(date: LocalDate, now: ZonedDateTime): String = when {
        date == now.toLocalDate() -> TODAY
        date.year == now.year -> MONTH_DAY.format(date)
        else -> YEAR_MONTH_DAY.format(date)
    }

    // Fixed Chinese patterns rather than locale-derived ones: the default
    // `values/` resource set is Chinese (archive §17.1), and a locale-formatted
    // month name would mix two scripts inside one label.
    private val MONTH_DAY = DateTimeFormatter.ofPattern("M月d日")
    private val YEAR_MONTH_DAY = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val MINUTE = DateTimeFormatter.ofPattern("HH:mm")

    private const val TODAY = "今天"
    private const val TOMORROW = "明天"
}

/**
 * Reminder arithmetic shared by the composer and the detail page (ADR-068).
 *
 * Pure functions over a pending-reminder list, so "add the default", "add a
 * picked date" and "remove one" exist once, and the limits are testable without
 * Compose. The alarm manager is not involved: this decides *what* the set is,
 * `:app` decides *when* to re-arm the system alarm.
 */
object QuestionSchedule {

    /** Upper bound on pending reminders per question, mirroring `QuestionStore`. */
    const val MAX_REMINDERS = 8

    /**
     * Default reminder: **the same clock time tomorrow**.
     *
     * A fixed morning time was rejected because it decouples the reminder from
     * the moment the thought arrived: someone who writes at 22:40 is a night
     * thinker, and a 09:00 ping reads as an unrelated alarm.
     */
    fun defaultReminder(now: ZonedDateTime): Instant = now.plusDays(1).toInstant()

    /** Reminder set a brand-new question starts with: exactly the default. */
    fun initialReminders(now: ZonedDateTime): List<Instant> = listOf(defaultReminder(now))

    /** Adds the "tomorrow, same time" reminder; a full set is returned unchanged. */
    fun addDefault(reminders: List<Instant>, now: ZonedDateTime): List<Instant> {
        if (reminders.size >= MAX_REMINDERS) return reminders
        return (reminders + defaultReminder(now)).distinct().sorted()
    }

    /**
     * Adds one reminder picked in the date + time dialogs.
     *
     * Returns null when the instant is not in the future or the set is full, so
     * the caller can explain why instead of accepting a past alarm (which would
     * fire immediately).
     */
    fun addAt(
        reminders: List<Instant>,
        date: LocalDate,
        time: LocalTime,
        now: ZonedDateTime,
    ): List<Instant>? {
        if (reminders.size >= MAX_REMINDERS) return null
        val instant = date.atTime(time).atZone(now.zone).toInstant()
        if (!instant.isAfter(now.toInstant())) return null
        return (reminders + instant).distinct().sorted()
    }

    fun remove(reminders: List<Instant>, target: Instant): List<Instant> =
        reminders.filter { it != target }

    fun canAdd(reminders: List<Instant>): Boolean = reminders.size < MAX_REMINDERS

    /**
     * Reminders still worth arming, expired ones dropped.
     *
     * Applied when a question is saved and on every re-sync: an instant that has
     * already passed can only produce an immediate notification, which is never
     * what the person configured.
     */
    fun pending(reminders: List<Instant>, now: ZonedDateTime): List<Instant> =
        reminders.filter { it.isAfter(now.toInstant()) }.distinct().sorted()
}
