package com.meteor.memory.feature.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.core.storage.Mood
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Emoji shown on a day cell and in the picker. Presentation only. */
fun Mood.emoji(): String = when (this) {
    Mood.HAPPY -> "😆"
    Mood.CALM -> "😌"
    Mood.EXCITED -> "🤩"
    Mood.TIRED -> "🥱"
    Mood.SAD -> "😵‍💫"
    Mood.ANGRY -> "😡"
    Mood.EMM -> "🥺"
}

/**
 * 首页「心情日历」：月份网格 + 本月统计 + 选中日卡片 + 快捷入口。
 *
 * 日历仍然手写（ADR-017）：格子只有几十个，手写比引入日历库更可控。
 * 视觉以设计稿 `docs/design/home.html` 为准：52dp 圆角日格、淡蓝统计条、
 * 20dp 圆角卡片、44dp 胶囊按钮与三宫格快捷入口。
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
    onToday: () -> Unit = { onSelectDate(today) },
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (showHeader) {
                CalendarHeader(
                    appName = appName,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(start = 20.dp, end = 8.dp),
                )
            }

            MonthBar(
                month = month,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onToday = onToday,
            )

            WeekdayHeader()

            MonthGrid(
                month = month,
                today = today,
                moods = moods,
                selectedDate = selectedDate,
                onSelectDate = onSelectDate,
            )

            MonthSummary(
                month = month,
                today = today,
                moods = moods,
                diaryDates = diaryDates,
            )

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

            QuickActions(
                onOpenGallery = onOpenGallery,
                onOpenChats = onOpenChats,
                onOpenPeople = onOpenPeople,
            )

            // Leave room for the floating AI entry so it never covers the
            // quick-action row when the page is scrolled to the bottom.
            Spacer(Modifier.height(if (onOpenAi != null) 84.dp else 24.dp))
        }

        onOpenAi?.let { open ->
            val aiLabel = stringResource(R.string.calendar_open_ai)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 8.dp)
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .clickable(onClick = open)
                    .semantics {
                        contentDescription = aiLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                AiSparkIcon(label = aiLabel)
            }
        }
    }
}

@Composable
fun CalendarHeader(
    appName: String,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = appName,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row {
            val searchLabel = stringResource(R.string.calendar_open_search)
            IconAction(
                label = searchLabel,
                onClick = onOpenSearch,
            ) { MagnifierIcon(label = searchLabel) }

            val settingsLabel = stringResource(R.string.calendar_open_settings)
            IconAction(
                label = settingsLabel,
                onClick = onOpenSettings,
            ) { SlidersIcon(label = settingsLabel) }
        }
    }
}

@Composable
private fun IconAction(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MonthBar(
    month: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(
                R.string.calendar_month_label,
                month.year,
                month.monthValue,
            ),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .padding(end = 4.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp))
                    .clickable(onClick = onToday)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.calendar_today),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }

            val previousLabel = stringResource(R.string.calendar_previous_month)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onPreviousMonth)
                    .semantics { contentDescription = previousLabel },
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(previous = true)
            }

            val nextLabel = stringResource(R.string.calendar_next_month)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onNextMonth)
                    .semantics { contentDescription = nextLabel },
                contentAlignment = Alignment.Center,
            ) {
                ChevronIcon(previous = false)
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val locale = Locale.getDefault()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 12.dp),
    ) {
        DayOfWeek.entries.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.NARROW, locale),
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
    val cells: List<LocalDate?> = remember(month) {
        List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f).height(52.dp)) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                mood = moods[date],
                                isFuture = date.isAfter(today),
                                isSelected = date == selectedDate,
                                onClick = { onSelectDate(date) },
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Box(Modifier.weight(1f).height(52.dp)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    mood: Mood?,
    isFuture: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme
    val background = if (isSelected) surface.primary else Color.Transparent
    val numberColor = when {
        isSelected -> surface.onPrimary
        isFuture -> surface.onSurfaceVariant
        else -> surface.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = date.dayOfMonth.toString()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = numberColor,
        )
        if (mood != null) {
            Text(text = mood.emoji(), fontSize = 13.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun MonthSummary(
    month: YearMonth,
    today: LocalDate,
    moods: Map<LocalDate, Mood>,
    diaryDates: Set<LocalDate>,
) {
    val recordedDays = remember(month, moods, diaryDates) {
        (moods.keys + diaryDates).count { YearMonth.from(it) == month }
    }
    val streak = remember(today, moods, diaryDates) {
        var cursor = today
        var count = 0
        while (diaryDates.contains(cursor) || moods.containsKey(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        count
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .height(36.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = stringResource(R.string.calendar_month_summary, recordedDays, streak),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
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
    val locale = Locale.getDefault()
    val dateLabel = stringResource(
        R.string.calendar_date_label,
        date.monthValue,
        date.dayOfMonth,
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = dateLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (hasDiary && !date.isAfter(today)) DoubleCheckMark()
            }
            if (mood != null && !date.isAfter(today)) {
                Text(
                    text = if (date == today) {
                        stringResource(R.string.calendar_mood_today, mood.label())
                    } else {
                        mood.label()
                    },
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        if (date.isAfter(today)) {
            val encouragements = stringArrayResource(R.array.calendar_future_encouragements)
            Text(
                text = remember(date) { encouragements.random() },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mood.entries.forEach { candidate ->
                    val label = candidate.label()
                    val selected = candidate == mood
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                shape = CircleShape,
                            )
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        CircleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onPickMood(candidate) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = candidate.emoji(), fontSize = 22.sp)
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
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onLeftClick,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        text = stringResource(leftLabel),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Button(
                    onClick = onRightClick,
                    modifier = Modifier.weight(1.3f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        text = stringResource(rightLabel),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    onOpenGallery: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenPeople: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickActionTile(
            label = stringResource(R.string.calendar_open_gallery),
            onClick = onOpenGallery,
            modifier = Modifier.weight(1f),
        ) { GalleryGlyph() }
        QuickActionTile(
            label = stringResource(R.string.calendar_open_chats),
            onClick = onOpenChats,
            modifier = Modifier.weight(1f),
        ) { NotesGlyph() }
        QuickActionTile(
            label = stringResource(R.string.calendar_open_people),
            onClick = onOpenPeople,
            modifier = Modifier.weight(1f),
        ) { PeopleGlyph() }
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .height(60.dp)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(22.dp)) { icon() }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.secondary,
        )
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
        Mood.EMM -> R.string.mood_emm
    },
)

@Composable
private fun SlidersIcon(label: String) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp).semantics { contentDescription = label }) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        fun line(x1: Float, x2: Float, y: Float) = drawLine(
            color = tint,
            start = Offset(x1 * unit, y * unit),
            end = Offset(x2 * unit, y * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        line(4f, 14f, 7f)
        line(18f, 20f, 7f)
        line(4f, 6f, 17f)
        line(10f, 20f, 17f)
        drawCircle(
            color = tint,
            radius = 2f * unit,
            center = Offset(16f * unit, 7f * unit),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = tint,
            radius = 2f * unit,
            center = Offset(8f * unit, 17f * unit),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun MagnifierIcon(label: String) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp).semantics { contentDescription = label }) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawCircle(
            color = tint,
            radius = 6.5f * unit,
            center = Offset(11f * unit, 11f * unit),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(16f * unit, 16f * unit),
            end = Offset(20.5f * unit, 20.5f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ChevronIcon(previous: Boolean) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val path = Path().apply {
            if (previous) {
                moveTo(15f * unit, 6f * unit)
                lineTo(9f * unit, 12f * unit)
                lineTo(15f * unit, 18f * unit)
            } else {
                moveTo(9f * unit, 6f * unit)
                lineTo(15f * unit, 12f * unit)
                lineTo(9f * unit, 18f * unit)
            }
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun GalleryGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(3f * unit, 4f * unit),
            size = Size(18f * unit, 16f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * unit),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = tint,
            radius = 1.6f * unit,
            center = Offset(9f * unit, 10f * unit),
            style = Stroke(width = stroke),
        )
        val path = Path().apply {
            moveTo(4f * unit, 18f * unit)
            lineTo(9f * unit, 13f * unit)
            lineTo(13f * unit, 17f * unit)
            lineTo(16f * unit, 14f * unit)
            lineTo(20f * unit, 18f * unit)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun NotesGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawRoundRect(
            color = tint,
            topLeft = Offset(5f * unit, 3f * unit),
            size = Size(14f * unit, 18f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * unit),
            style = Stroke(width = stroke),
        )
        listOf(8f, 12f, 16f).forEachIndexed { index, y ->
            drawLine(
                color = tint,
                start = Offset(9f * unit, y * unit),
                end = Offset((if (index == 2) 13f else 15f) * unit, y * unit),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PeopleGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        drawCircle(
            color = tint,
            radius = 3f * unit,
            center = Offset(9f * unit, 9f * unit),
            style = Stroke(width = stroke),
        )
        val shoulder = Path().apply {
            moveTo(3.5f * unit, 19f * unit)
            cubicTo(
                4.1f * unit, 16f * unit,
                6.5f * unit, 14.5f * unit,
                9f * unit, 14.5f * unit,
            )
            cubicTo(
                11.5f * unit, 14.5f * unit,
                13.9f * unit, 16f * unit,
                14.5f * unit, 19f * unit,
            )
        }
        drawPath(path = shoulder, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(
            color = tint,
            radius = 2.4f * unit,
            center = Offset(17f * unit, 10f * unit),
            style = Stroke(width = stroke),
        )
        val second = Path().apply {
            moveTo(16f * unit, 14.8f * unit)
            cubicTo(
                18.4f * unit, 14.5f * unit,
                20.2f * unit, 15.8f * unit,
                20.8f * unit, 18.5f * unit,
            )
        }
        drawPath(path = second, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun AiSparkIcon(label: String) {
    val tint = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(22.dp).semantics { contentDescription = label }) {
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
    val tint = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .size(width = 20.dp, height = 12.dp)
            .semantics { contentDescription = label },
    ) {
        val stroke = Stroke(
            width = 1.8.dp.toPx(),
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
        drawPath(first, color = tint, style = stroke)
        drawPath(second, color = tint, style = stroke)
    }
}
