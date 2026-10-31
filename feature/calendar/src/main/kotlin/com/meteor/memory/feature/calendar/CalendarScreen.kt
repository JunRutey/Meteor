package com.meteor.memory.feature.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
    appName: String,
    month: YearMonth,
    today: LocalDate,
    moods: Map<LocalDate, Mood>,
    selectedDate: LocalDate?,
    diaryDates: Set<LocalDate>,
    imageDates: Set<LocalDate>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onPickMood: (LocalDate, Mood) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiary: (LocalDate) -> Unit,
    onEditDiary: (LocalDate) -> Unit,
    onOpenImages: (LocalDate) -> Unit,
    onPickImage: (LocalDate) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAi: (() -> Unit)?,
    onOpenGallery: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenPeople: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.getDefault()
    val monthLabel = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month.atDay(1))

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = appName, style = MaterialTheme.typography.headlineSmall)
                val settingsLabel = stringResource(R.string.calendar_open_settings)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSettings)
                        .semantics { contentDescription = settingsLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    GearIcon(contentDescription = settingsLabel)
                }
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
                    today = today,
                    mood = moods[date],
                    hasDiary = date in diaryDates,
                    hasImages = date in imageDates,
                    onPickMood = { mood -> onPickMood(date, mood) },
                    onRightClick = {
                        if (date in diaryDates) onOpenDiary(date) else onEditDiary(date)
                    },
                    onLeftClick = {
                        if (date in imageDates) onOpenImages(date) else onPickImage(date)
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenGallery, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.calendar_open_gallery), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onOpenChats, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.calendar_open_chats), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onOpenPeople, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.calendar_open_people), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(96.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val searchLabel = stringResource(R.string.calendar_open_search)
            FloatingActionButton(
                onClick = onOpenSearch,
                shape = CircleShape,
                modifier = Modifier.semantics { contentDescription = searchLabel },
            ) {
                MagnifierIcon(contentDescription = searchLabel)
            }

            onOpenAi?.let { open ->
                val aiLabel = stringResource(R.string.calendar_open_ai)
                FloatingActionButton(
                    onClick = open,
                    shape = CircleShape,
                    modifier = Modifier.semantics { contentDescription = aiLabel },
                ) {
                    AiSparkIcon(contentDescription = aiLabel)
                }
            }
        }
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
    today: LocalDate,
    mood: Mood?,
    hasDiary: Boolean,
    hasImages: Boolean,
    onPickMood: (Mood) -> Unit,
    onRightClick: () -> Unit,
    onLeftClick: () -> Unit,
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

            val leftLabel = if (hasImages) {
                R.string.calendar_view_images
            } else {
                R.string.calendar_save_image
            }
            val rightLabel = when {
                hasDiary -> R.string.calendar_view_diary
                date == today -> R.string.calendar_record_now
                else -> R.string.calendar_backfill_diary
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onLeftClick, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(leftLabel))
                }
                Button(onClick = onRightClick, modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(rightLabel))
                }
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

@Composable
private fun GearIcon(contentDescription: String) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics { this.contentDescription = contentDescription }) {
        val c = center
        val r = size.minDimension / 2f
        repeat(8) { i ->
            rotate(degrees = i * 45f, pivot = c) {
                drawRect(
                    color = tint,
                    topLeft = Offset(c.x - r * 0.11f, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 0.22f, r * 0.34f),
                )
            }
        }
        drawCircle(color = tint, radius = r * 0.58f, style = Stroke(width = r * 0.20f))
    }
}

@Composable
private fun MagnifierIcon(contentDescription: String) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics { this.contentDescription = contentDescription }) {
        val stroke = size.minDimension * 0.11f
        val radius = size.minDimension * 0.30f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color = tint, radius = radius, center = c, style = Stroke(width = stroke))
        drawLine(
            color = tint,
            start = Offset(c.x + radius * 0.75f, c.y + radius * 0.75f),
            end = Offset(size.width * 0.92f, size.height * 0.92f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun AiSparkIcon(contentDescription: String) {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp).semantics { this.contentDescription = contentDescription }) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val big = size.minDimension / 2f
        val small = big * 0.26f
        val star = Path().apply {
            moveTo(cx, cy - big)
            lineTo(cx + small, cy - small)
            lineTo(cx + big, cy)
            lineTo(cx + small, cy + small)
            lineTo(cx, cy + big)
            lineTo(cx - small, cy + small)
            lineTo(cx - big, cy)
            lineTo(cx - small, cy - small)
            close()
        }
        drawPath(star, color = tint)
    }
}

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
