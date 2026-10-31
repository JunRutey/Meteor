package com.meteor.memory.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
 * 关于页：这么运作 / 加密说明 / GitHub / 打赏 / 逐版本更新日志。
 *
 * 长正文放 assets/*.md（数据，不是代码字面量），短标签走 strings.xml。
 * 打赏码缺失时整块隐藏，缺图不会挡住编译。
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
    val changelogMd = remember { readAsset(context, "changelog.md").orEmpty() }
    val donate = remember { readDonate(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        DocsText(aboutMd)

        OutlinedButton(onClick = onOpenRepository, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.about_repository))
        }

        if (donate != null) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.about_donate),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Image(
                bitmap = donate.asImageBitmap(),
                contentDescription = stringResource(R.string.about_donate),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.about_changelog),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        DocsText(changelogMd)
    }
}

private fun readAsset(context: Context, path: String): String? = runCatching {
    context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}.getOrNull()

private fun readDonate(context: Context): Bitmap? = runCatching {
    context.assets.open("donate.png").use { BitmapFactory.decodeStream(it) }
}.getOrNull()
