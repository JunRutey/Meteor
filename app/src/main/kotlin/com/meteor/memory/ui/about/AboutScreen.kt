package com.meteor.memory.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R

/**
 * 关于页：这么运作 / 加密说明 / GitHub。
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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.about_version, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onBack) { Text(text = stringResource(R.string.about_back)) }
            }
        }

        items(aboutLines) { line -> DocsLine(line) }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenRepository, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.about_repository))
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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { AboutDetailHeader(title = stringResource(R.string.about_donate), onBack = onBack) }
        if (donate != null) {
            item {
                Image(
                    bitmap = donate.asImageBitmap(),
                    contentDescription = stringResource(R.string.about_donate),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            item { Text(text = stringResource(R.string.about_donate_unavailable)) }
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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { AboutDetailHeader(title = stringResource(R.string.about_changelog), onBack = onBack) }
        items(changelogLines) { line -> DocsLine(line) }
    }
}

@Composable
private fun AboutDetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = onBack) { Text(text = stringResource(R.string.about_back)) }
    }
}

private fun readAsset(context: Context, path: String): String? = runCatching {
    context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}.getOrNull()

private fun readDonate(context: Context): Bitmap? = runCatching {
    context.assets.open("donate.png").use { BitmapFactory.decodeStream(it) }
}.getOrNull()
