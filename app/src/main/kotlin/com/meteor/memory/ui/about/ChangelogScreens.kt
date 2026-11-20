package com.meteor.memory.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R

/**
 * Second-level changelog page: one clickable row per version, newest first as
 * ordered in `changelog.md`.
 */
@Composable
fun ChangelogScreen(
    onOpenVersion: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versions = remember {
        ChangelogParser.parse(readAsset(context, "changelog.md").orEmpty())
    }

    AboutPage(
        title = stringResource(R.string.about_changelog),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (versions.isEmpty()) {
            item { EmptyNote(stringResource(R.string.about_changelog_empty)) }
        } else {
            itemsIndexed(versions, key = { _, version -> version.number }) { index, version ->
                Column {
                    AboutLinkRow(
                        text = version.number,
                        onClick = { onOpenVersion(version.number) },
                    )
                    if (index < versions.lastIndex) AboutHairline()
                }
            }
        }
    }
}

/** Third-level page: the log lines of one version. */
@Composable
fun ChangelogVersionScreen(
    versionNumber: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember(versionNumber) {
        ChangelogParser.parse(readAsset(context, "changelog.md").orEmpty())
            .firstOrNull { it.number == versionNumber }
    }

    AboutPage(
        title = stringResource(R.string.about_changelog_version_title, versionNumber),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (version == null || version.lines.isEmpty()) {
            item { EmptyNote(stringResource(R.string.about_changelog_version_empty)) }
        } else {
            items(version.lines) { line -> DocsLine(line) }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
