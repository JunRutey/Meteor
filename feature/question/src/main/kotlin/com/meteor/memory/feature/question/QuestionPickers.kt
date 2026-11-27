package com.meteor.memory.feature.question

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.time.ZonedDateTime

/**
 * The 「选择日期与时间…」 flow: a system date dialog, then a system time dialog.
 *
 * `android.app.DatePickerDialog` / `TimePickerDialog` instead of the Material3
 * `DatePicker` / `TimePicker`: the Material3 pair is still experimental and the
 * only screen in this repo that opted in is `feature/ai/AiChrome.kt`. The platform
 * dialogs need no opt-in, bring their own 确定 / 取消, and are themed by the
 * activity. `minSdk` is 26, so both are always available.
 *
 * Both dialogs open on **tomorrow, same clock time** — the feature's default
 * reminder — so a person who just taps through never lands on an instant in the
 * past, which would arm an alarm that fires immediately.
 *
 * Returning a launcher (instead of rendering the dialogs as state) keeps the
 * call site free of picker bookkeeping: the screens only hand the callback down.
 */
@Composable
internal fun rememberCustomReminderLauncher(onPicked: (ReminderPick) -> Unit): () -> Unit {
    val context = LocalContext.current
    val dateTitle = stringResource(R.string.question_date_picker_title)
    val timeTitle = stringResource(R.string.question_time_picker_title)
    // The launcher is remembered across recompositions, so it must read the
    // newest callback instead of capturing the one from the first composition.
    val latestOnPicked by rememberUpdatedState(onPicked)

    val launcher: () -> Unit = remember(context, dateTitle, timeTitle) {
        {
            val now = ZonedDateTime.now()
            val seed = QuestionSchedule.defaultReminder(now)
            val date = QuestionFormat.localDate(seed, now)
            val time = QuestionFormat.localTime(seed, now)

            val dateDialog = DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    // The time dialog only exists once a date is chosen, so the
                    // picker never reports a half-filled reminder.
                    val timeDialog = TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            latestOnPicked(
                                ReminderPick(
                                    year = year,
                                    // DatePickerDialog counts months from 0.
                                    month = month + 1,
                                    day = dayOfMonth,
                                    hour = hour,
                                    minute = minute,
                                ),
                            )
                        },
                        time.hour,
                        time.minute,
                        true,
                    )
                    timeDialog.setTitle(timeTitle)
                    timeDialog.show()
                },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth,
            )
            dateDialog.setTitle(dateTitle)
            dateDialog.show()
        }
    }
    return launcher
}
