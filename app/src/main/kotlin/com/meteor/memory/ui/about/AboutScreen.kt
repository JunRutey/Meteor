package com.meteor.memory.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R

/**
 * 关于页：运行方式 / 加密说明 / GitHub。
 *
 * 长正文放在 assets 的 Markdown 文件中（数据，不是代码字面量），短标签走 strings.xml。
 * 打赏码与更新日志分别在独立页面展示。
 */
@Composable
fun AboutScreen(
    versionName: String,
    onOpenRepository: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val aboutMd = remember { readAsset(context, "about.md").orEmpty() }
    val aboutLines = remember(aboutMd) { aboutMd.lines() }

    AboutPage(
        title = stringResource(R.string.about_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(aboutLines) { line -> DocsLine(line) }
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenRepository,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_repository),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun DonateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val donate = remember { readDonate(context) }

    AboutPage(
        title = stringResource(R.string.about_donate),
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            if (donate != null) {
                Image(
                    bitmap = donate.asImageBitmap(),
                    contentDescription = stringResource(R.string.about_donate),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = stringResource(R.string.about_donate_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ChangelogScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val changelogMd = remember { readAsset(context, "changelog.md").orEmpty() }
    val changelogLines = remember(changelogMd) { changelogMd.lines() }

    AboutPage(
        title = stringResource(R.string.about_changelog),
        onBack = onBack,
        modifier = modifier,
    ) {
        items(changelogLines) { line -> DocsLine(line) }
    }
}

@Composable
private fun AboutPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AboutPageHeader(title = title, onBack = onBack)
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun AboutPageHeader(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.about_back)
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).semantics { contentDescription = backLabel },
        ) {
            val tint = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(22.dp)) {
                val unit = size.width / 24f
                val path = Path().apply {
                    moveTo(15f * unit, 6f * unit)
                    lineTo(9f * unit, 12f * unit)
                    lineTo(15f * unit, 18f * unit)
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun readAsset(context: Context, path: String): String? = runCatching {
    context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}.getOrNull()

private fun readDonate(context: Context): Bitmap? = runCatching {
    context.assets.open("donate.png").use { BitmapFactory.decodeStream(it) }
}.getOrNull()
