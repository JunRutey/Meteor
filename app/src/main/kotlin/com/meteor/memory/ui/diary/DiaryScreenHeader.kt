package com.meteor.memory.ui.diary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R

/**
 * 日记页通用页头：左侧返回箭头、20sp 标题、右侧动作（如「预览」胶囊）。
 *
 * 标题下可挂一行 13sp 辅助信息（记录时间、保存状态），与设计稿一致：
 * 页头不画卡片，直接落在页面底色上。
 */
@Composable
internal fun DiaryScreenHeader(
    dateLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    supportingContent: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backLabel = stringResource(R.string.diary_back)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                BackChevron()
            }
            Text(
                text = dateLabel,
                modifier = Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = actions,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = supportingContent,
        )
    }
}

@Composable
private fun BackChevron() {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val stroke = 1.8.dp.toPx()
        val unit = size.width / 24f
        val path = Path().apply {
            moveTo(15f * unit, 6f * unit)
            lineTo(9f * unit, 12f * unit)
            lineTo(15f * unit, 18f * unit)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
