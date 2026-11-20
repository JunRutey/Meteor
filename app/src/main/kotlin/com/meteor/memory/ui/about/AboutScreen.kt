package com.meteor.memory.ui.about

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.R

/**
 * About page: app identity plus links to the changelog, repository,
 * discussions and the feedback page. The long-form introduction that used to
 * be rendered from `about.md` is gone; user-facing copy lives in strings.xml.
 */
@Composable
fun AboutScreen(
    versionName: String,
    onOpenChangelog: () -> Unit,
    onOpenRepository: () -> Unit,
    onOpenDiscussions: () -> Unit,
    onOpenFeedback: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Column {
                AboutLinkRow(
                    text = stringResource(R.string.about_changelog),
                    onClick = onOpenChangelog,
                )
                AboutHairline()
                AboutLinkRow(
                    text = stringResource(R.string.about_repository),
                    onClick = onOpenRepository,
                )
                AboutHairline()
                AboutLinkRow(
                    text = stringResource(R.string.about_discussions),
                    onClick = onOpenDiscussions,
                )
                AboutHairline()
                AboutLinkRow(
                    text = stringResource(R.string.about_feedback),
                    onClick = onOpenFeedback,
                )
            }
        }
    }
}

/**
 * Feedback sub-page. The issue tracker belongs here rather than on the About
 * page; the second row opens the maintainer's inbox through a mailto intent.
 */
@Composable
fun FeedbackScreen(
    onOpenIssues: () -> Unit,
    onOpenEmail: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AboutPage(
        title = stringResource(R.string.about_feedback_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            Column {
                AboutLinkRow(
                    text = stringResource(R.string.about_feedback_issues),
                    onClick = onOpenIssues,
                )
                AboutHairline()
                AboutLinkRow(
                    text = stringResource(R.string.about_feedback_email),
                    subtitle = SUPPORT_EMAIL,
                    onClick = onOpenEmail,
                )
            }
        }
    }
}

/**
 * Donation page. Alipay opens the maintainer's payment page through an
 * external intent; WeChat stays a centred, width-capped appreciation code.
 * Donations never unlock features or data.
 */
@Composable
fun DonateScreen(
    onOpenAlipay: () -> Unit,
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
            Column {
                Text(
                    text = stringResource(R.string.about_donate_alipay_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.about_donate_alipay_desc),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp,
                )
                Button(
                    onClick = onOpenAlipay,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_donate_alipay_action),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            AboutHairline()
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.about_donate_wechat_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.about_donate_wechat_desc),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                if (donate != null) {
                    Image(
                        bitmap = donate.asImageBitmap(),
                        contentDescription = stringResource(R.string.about_donate_wechat_code),
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .fillMaxWidth()
                            .aspectRatio(donate.width.toFloat() / donate.height.toFloat())
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.about_donate_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.about_donate_note),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Shared page shell: back arrow + title above a rounded, scrollable card. */
@Composable
internal fun AboutPage(
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

/** Clickable row matching the settings row rhythm (16dp inset, 52dp minimum). */
@Composable
internal fun AboutLinkRow(
    text: String,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AboutChevron()
    }
}

@Composable
internal fun AboutHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
private fun AboutChevron() {
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(22.dp)) {
            val unit = size.width / 24f
            val path = Path().apply {
                moveTo(9f * unit, 6f * unit)
                lineTo(15f * unit, 12f * unit)
                lineTo(9f * unit, 18f * unit)
            }
            drawPath(
                path = path,
                color = tint,
                style = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
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

internal fun readAsset(context: Context, path: String): String? = runCatching {
    context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}.getOrNull()

private fun readDonate(context: Context): Bitmap? = runCatching {
    context.assets.open("donate.png").use { BitmapFactory.decodeStream(it) }
}.getOrNull()
