package com.meteor.memory.feature.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.core.storage.Mood
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Emoji shown on a day cell and in the picker. Presentation only. */
fun Mood.emoji(): String = when (this) {
    Mood.HAPPY -> "😊"
    Mood.CALM -> "😌"
    Mood.EXCITED -> "🤩"
    Mood.TIRED -> "😪"
    Mood.SAD -> "😔"
    Mood.ANGRY -> "😠"
}

/**
 * Month grid with a mood per day.
 *
 * Hand-rolled instead of pulling in a calendar library: the grid is a few dozen
 * cells and every dependency here would need API verification we cannot do
 * without a local build (ADR-017).
 */
@Composable
fun CalendarScreen(
    month: YearMonth,
    today: LocalDate,
    moods: Map<LocalDate, Mood>,
    selectedDate: LocalDate?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onPickMood: (LocalDate, Mood) -> Unit,
    onOpenDiary: (LocalDate) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Days that already have a diary entry, for the ✓✓ marker on the day card. */
    diaryDates: Set<LocalDate> = emptySet(),
) {
    val locale = Locale.getDefault()
    val monthLabel = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month.atDay(1))

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.calendar_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.calendar_back)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onPreviousMonth) { Text("‹") }
            Text(text = monthLabel, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onNextMonth) { Text("›") }
        }

        WeekdayHeader(locale)

        MonthGrid(
            month = month,
            today = today,
            moods = moods,
            selectedDate = selectedDate,
            onSelectDate = onSelectDate,
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        selectedDate?.let { date ->
            DayCard(
                date = date,
                mood = moods[date],
                hasDiary = date in diaryDates,
                onPickMood = { mood -> onPickMood(date, mood) },
                onOpenDiary = { onOpenDiary(date) },
            )
        } ?: Text(
            text = stringResource(R.string.calendar_select_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WeekdayHeader(locale: Locale) {
    Row(modifier = Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    moods: Map<LocalDate, Mood>,
    selectedDate: LocalDate?,
    onSelectDate: (LocalDate) -> Unit,
) {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val cells: List<LocalDate?> =
        List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column {
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (date == null) {
                            Box(Modifier.aspectRatio(1f))
                        } else {
                            DayCell(
                                date = date,
                                mood = moods[date],
                                isToday = date == today,
                                isSelected = date == selectedDate,
                                onClick = { onSelectDate(date) },
                            )
                        }
                    }
                }
                // Keep the last row aligned when the month does not fill it.
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    mood: Mood?,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        isToday -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
        )
        mood?.let {
            Text(text = it.emoji(), fontSize = 12.sp)
        }
    }
}

@Composable
private fun DayCard(
    date: LocalDate,
    mood: Mood?,
    hasDiary: Boolean,
    onPickMood: (Mood) -> Unit,
    onOpenDiary: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault()).format(date),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (hasDiary) DoubleCheckMark()
            }
            Text(
                text = mood?.let { "${it.emoji()} ${it.label()}" }
                    ?: stringResource(R.string.calendar_no_mood),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Mood.entries.forEach { candidate ->
                    val label = candidate.label()
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (candidate == mood) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .clickable { onPickMood(candidate) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = candidate.emoji(), fontSize = 18.sp)
                    }
                }
            }

            Button(onClick = onOpenDiary, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.calendar_open_diary))
            }
        }
    }
}

@Composable
private fun Mood.label(): String = stringResource(
    when (this) {
        Mood.HAPPY -> R.string.mood_happy
        Mood.CALM -> R.string.mood_calm
        Mood.EXCITED -> R.string.mood_excited
        Mood.TIRED -> R.string.mood_tired
        Mood.SAD -> R.string.mood_sad
        Mood.ANGRY -> R.string.mood_angry
    },
)

/**
 * WhatsApp-style double tick: two overlapping check paths, drawn rather than
 * typed so the overlap is exact on every font.
 *
 * It only says "there is a diary that day" — no read/delivered semantics.
 */
@Composable
private fun DoubleCheckMark(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.calendar_has_diary)
    Canvas(
        modifier = modifier
            .size(width = 26.dp, height = 15.dp)
            .semantics { contentDescription = label },
    ) {
        val stroke = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val first = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.55f)
            lineTo(size.width * 0.20f, size.height * 0.86f)
            lineTo(size.width * 0.54f, size.height * 0.14f)
        }
        val second = Path().apply {
            moveTo(size.width * 0.38f, size.height * 0.55f)
            lineTo(size.width * 0.56f, size.height * 0.86f)
            lineTo(size.width * 0.98f, size.height * 0.14f)
        }
        drawPath(first, color = DiaryGreen, style = stroke)
        drawPath(second, color = DiaryGreen, style = stroke)
    }
}

private val DiaryGreen = Color(0xFF25D366)
