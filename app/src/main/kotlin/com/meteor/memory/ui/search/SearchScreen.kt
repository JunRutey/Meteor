package com.meteor.memory.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R
import com.meteor.memory.core.storage.RecordType

/**
 * 搜索页：页头 + 主题色描边搜索框 + 类型筛选胶囊 + 结果卡片。
 *
 * 筛选在本地对结果做类型过滤：搜索索引里没有独立的「图片」记录
 * （图片挂在日记正文里），所以这里提供 日记 / 心情 / 档案 三类。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenDiary: (java.time.LocalDate) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(48.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                BackChevron()
            }
            Text(
                text = stringResource(R.string.search_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                leadingIcon = { SearchGlyph(tint = MaterialTheme.colorScheme.primary) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_hint),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { option ->
                FilterChip(
                    label = stringResource(option.labelRes),
                    selected = filter == option,
                    onClick = { filter = option },
                )
            }
        }

        when (state) {
            SearchUiState.Loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            SearchUiState.Idle -> EmptyState(
                title = stringResource(R.string.search_idle),
                subtitle = stringResource(R.string.search_idle_hint),
            )

            SearchUiState.Empty -> EmptyState(
                title = stringResource(R.string.search_empty),
                subtitle = stringResource(R.string.search_empty_hint),
            )

            is SearchUiState.Failed -> Text(
                text = stringResource(R.string.search_failed, state.message),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(20.dp),
                    )
                    .padding(16.dp),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            is SearchUiState.Results -> {
                val rows = state.rows.filter(filter::matches)
                if (rows.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.search_empty),
                        subtitle = stringResource(R.string.search_empty_filter_hint),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 14.dp,
                            bottom = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(rows, key = { it.recordId }) { row ->
                            ResultCard(
                                row = row,
                                onClick = {
                                    if (row.type == RecordType.DIARY) onOpenDiary(row.date)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class SearchFilter(val labelRes: Int) {
    ALL(R.string.search_filter_all),
    DIARY(R.string.search_filter_diary),
    MOOD(R.string.search_filter_mood),
    PROFILE(R.string.search_filter_profile),
    ;

    fun matches(row: SearchRow): Boolean = when (this) {
        ALL -> true
        DIARY -> row.type == RecordType.DIARY
        MOOD -> row.type == RecordType.MOOD
        PROFILE -> row.type == RecordType.PROFILE || row.type == RecordType.PERSONA
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SearchGlyph(tint = MaterialTheme.colorScheme.primary, glyphSize = 32.dp)
        }
        Text(
            text = title,
            modifier = Modifier.padding(top = 16.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(row: SearchRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = row.type.label(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = row.dateLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = row.snippet,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BackChevron() {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(15f * unit, 6f * unit)
            lineTo(9f * unit, 12f * unit)
            lineTo(15f * unit, 18f * unit)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SearchGlyph(
    tint: androidx.compose.ui.graphics.Color,
    glyphSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    Canvas(Modifier.size(glyphSize)) {
        val stroke = 1.8.dp.toPx()
        val unit = this.size.width / 24f
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
private fun RecordType.label(): String = stringResource(
    when (this) {
        RecordType.DIARY -> R.string.type_diary
        RecordType.MOOD -> R.string.type_mood
        RecordType.PROFILE -> R.string.type_profile
        RecordType.CHAT_LOG -> R.string.type_chat_log
        RecordType.PERSONA -> R.string.type_persona
    },
)
