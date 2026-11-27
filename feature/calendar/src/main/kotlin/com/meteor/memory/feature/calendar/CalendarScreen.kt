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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.core.storage.Mood
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Dim unselected moods once the day already holds [Mood.MAX_PER_DAY]. */
private const val MOOD_DISABLED_ALPHA = 0.35f

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
 * 一天最多选 [Mood.MAX_PER_DAY] 个心情：点一次选中，再点一次取消（ADR-035）。
 * 视觉以设计稿 `docs/design/home.html` 为准：52dp 圆角日格、淡蓝统计条、
 * 20dp 圆角卡片、44dp 胶囊按钮与三宫格快捷入口。
 */
@Composable
fun CalendarScreen(
    appName: String,
    month: YearMonth,
    today: LocalDate,
    moods: Map<LocalDate, List<Mood>>,
    selectedDate: LocalDate?,
    diaryDates: Set<LocalDate>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onToggleMood: (LocalDate, Mood) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiary: (LocalDate) -> Unit,
    onEditDiary: (LocalDate) -> Unit,
    onOpenImages: (LocalDate) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAi: (() -> Unit)?,
    /**
     * 每日一问（ADR-068）：选中的那天不再提供「存张记忆」——从设备导入图片、
     * 视频、音频统一收进相册，这个位置让给提问入口。
     */
    onOpenQuestions: () -> Unit,
    /**
     * The album hub: photos, video, audio and the imported-record list all live
     * in one place, so the old separate gallery and notes entries are merged
     * into this single callback (ADR-060).
     */
    onOpenAlbum: () -> Unit,
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
                    moods = moods[date].orEmpty(),
                    hasDiary = date in diaryDates,
                    onToggleMood = { mood -> onToggleMood(date, mood) },
                    onRightClick = {
                        if (date in diaryDates) onOpenDiary(date) else onEditDiary(date)
                    },
                    onLeftClick = { onOpenImages(date) },
                )
            }

            QuickActions(
                onOpenQuestions = onOpenQuestions,
                onOpenAlbum = onOpenAlbum,
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
    moods: Map<LocalDate, List<Mood>>,
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
                                moods = moods[date].orEmpty(),
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
    moods: List<Mood>,
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
        if (moods.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                moods.forEach { mood ->
                    Text(
                        text = mood.emoji(),
                        fontSize = when (moods.size) {
                            1 -> 13.sp
                            2 -> 11.sp
                            else -> 9.sp
                        },
                        lineHeight = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthSummary(
    month: YearMonth,
    today: LocalDate,
    moods: Map<LocalDate, List<Mood>>,
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
    moods: List<Mood>,
    hasDiary: Boolean,
    onToggleMood: (Mood) -> Unit,
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
    val atLimit = moods.size >= Mood.MAX_PER_DAY
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
            if (moods.isNotEmpty() && !date.isAfter(today)) {
                val separator = stringResource(R.string.calendar_mood_separator)
                val labels = moods.map { it.label() }
                Text(
                    text = if (date == today) {
                        stringResource(R.string.calendar_mood_today, labels.joinToString(separator))
                    } else {
                        labels.joinToString(separator)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.End,
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
                    val selected = candidate in moods
                    // A fourth mood is not selectable; deselect first. The
                    // caption below explains the cap so the dimmed state reads
                    // as "full", not "broken".
                    val enabled = selected || !atLimit
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .alpha(if (enabled) 1f else MOOD_DISABLED_ALPHA)
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
                            .toggleable(
                                value = selected,
                                enabled = enabled,
                                role = Role.Checkbox,
                                onValueChange = { onToggleMood(candidate) },
                            )
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = candidate.emoji(), fontSize = 22.sp)
                    }
                }
            }

            Text(
                text = if (moods.isEmpty()) {
                    stringResource(R.string.calendar_mood_hint, Mood.MAX_PER_DAY)
                } else {
                    stringResource(
                        R.string.calendar_mood_hint_selected,
                        moods.size,
                        Mood.MAX_PER_DAY,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // 左按钮固定为「查看当天图片」：当天有没有图都不再问，导入入口
            // 已经从首页挪到相册（ADR-068）。
            val leftLabel = R.string.calendar_view_day_images
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

/**
 * Bottom quick entries (ADR-060, ADR-068). The album hub owns photos, video,
 * audio and the imported-record list, so the old "gallery" and "notes" tiles
 * collapsed into one entry; 每日一问 sits in front of them.
 *
 * Three tiles share one row, so the gaps and the internal padding are tighter
 * than the two-tile layout used to be — otherwise the hints wrap.
 */
@Composable
private fun QuickActions(
    onOpenQuestions: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenPeople: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickActionTile(
            label = stringResource(R.string.calendar_open_questions),
            hint = stringResource(R.string.calendar_quick_questions_hint),
            onClick = onOpenQuestions,
            modifier = Modifier.weight(1f),
        ) { QuestionsGlyph() }
        QuickActionTile(
            label = stringResource(R.string.calendar_open_gallery),
            hint = stringResource(R.string.calendar_quick_album_hint),
            onClick = onOpenAlbum,
            modifier = Modifier.weight(1f),
        ) { AlbumGlyph() }
        QuickActionTile(
            label = stringResource(R.string.calendar_open_people),
            hint = stringResource(R.string.calendar_quick_people_hint),
            onClick = onOpenPeople,
            modifier = Modifier.weight(1f),
        ) { PeopleGlyph() }
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp)) { icon() }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/**
 * Combined album glyph (ADR-060): two stacked cards, the front one carrying a
 * photo mark. The back card is the imported-record half of the hub, so one icon
 * covers "photos / video / audio + records" without a second entry.
 */
@Composable
private fun AlbumGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        // Back card: the records half, peeking out behind the front one.
        drawRoundRect(
            color = tint,
            topLeft = Offset(7.5f * unit, 3f * unit),
            size = Size(13f * unit, 12.5f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
            style = Stroke(width = stroke),
        )
        // Front card: photos and video.
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.5f * unit, 8f * unit),
            size = Size(13f * unit, 12.5f * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * unit),
            style = Stroke(width = stroke),
        )
        drawCircle(
            color = tint,
            radius = 1.3f * unit,
            center = Offset(7.8f * unit, 12.6f * unit),
            style = Stroke(width = stroke),
        )
        val hills = Path().apply {
            moveTo(5f * unit, 18.6f * unit)
            lineTo(8.4f * unit, 15.4f * unit)
            lineTo(10.6f * unit, 17.6f * unit)
            lineTo(12.4f * unit, 15.8f * unit)
            lineTo(15f * unit, 18.6f * unit)
        }
        drawPath(
            path = hills,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 每日一问 glyph (ADR-068): a question mark inside a speech bubble.
 *
 * The mark is a single path — hook plus stem — so it stays legible at 22dp
 * without depending on a font glyph.
 */
@Composable
private fun QuestionsGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        // Bubble outline with a tail at the bottom-left.
        val bubble = Path().apply {
            moveTo(7f * unit, 3.5f * unit)
            lineTo(17f * unit, 3.5f * unit)
            cubicTo(
                19f * unit, 3.5f * unit,
                20.5f * unit, 5f * unit,
                20.5f * unit, 7f * unit,
            )
            lineTo(20.5f * unit, 13f * unit)
            cubicTo(
                20.5f * unit, 15f * unit,
                19f * unit, 16.5f * unit,
                17f * unit, 16.5f * unit,
            )
            lineTo(11f * unit, 16.5f * unit)
            lineTo(7.5f * unit, 20f * unit)
            lineTo(7.5f * unit, 16.5f * unit)
            lineTo(7f * unit, 16.5f * unit)
            cubicTo(
                5f * unit, 16.5f * unit,
                3.5f * unit, 15f * unit,
                3.5f * unit, 13f * unit,
            )
            lineTo(3.5f * unit, 7f * unit)
            cubicTo(
                3.5f * unit, 5f * unit,
                5f * unit, 3.5f * unit,
                7f * unit, 3.5f * unit,
            )
            close()
        }
        drawPath(
            path = bubble,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // Question mark: the hook, then the stem dot below it.
        val hook = Path().apply {
            moveTo(9.2f * unit, 8f * unit)
            cubicTo(
                9.2f * unit, 6.4f * unit,
                10.4f * unit, 5.6f * unit,
                12f * unit, 5.6f * unit,
            )
            cubicTo(
                13.6f * unit, 5.6f * unit,
                14.8f * unit, 6.5f * unit,
                14.8f * unit, 8f * unit,
            )
            cubicTo(
                14.8f * unit, 10f * unit,
                12f * unit, 10.2f * unit,
                12f * unit, 12.4f * unit,
            )
        }
        drawPath(path = hook, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(
            color = tint,
            radius = 0.95f * unit,
            center = Offset(12f * unit, 14.4f * unit),
        )
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
