package com.meteor.memory.feature.question

/**
 * One reminder picked in the 日期 + 时间 dialogs (ADR-068).
 *
 * A plain calendar triple rather than an [java.time.Instant]: the dialogs run in
 * the device zone and the caller decides when to resolve it, so this type cannot
 * silently carry a zone that has since changed. [month] is **1-based** (unlike
 * `android.app.DatePickerDialog`, whose month is 0-based), so the conversion back
 * to `LocalDate.of(...)` stays a direct mapping at the call site.
 */
data class ReminderPick(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
)
