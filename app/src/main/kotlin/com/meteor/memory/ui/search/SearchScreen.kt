package com.meteor.memory.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import com.meteor.memory.core.storage.RecordType

@Composable
fun SearchScreen(
    state: SearchUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenDiary: (java.time.LocalDate) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.search_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.search_back)) }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text(text = stringResource(R.string.search_hint)) },
        )

        when (state) {
            SearchUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

            SearchUiState.Idle -> Hint(stringResource(R.string.search_idle))

            SearchUiState.Empty -> Hint(stringResource(R.string.search_empty))

            is SearchUiState.Failed -> Text(
                text = stringResource(R.string.search_failed, state.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )

            is SearchUiState.Results -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.rows, key = { it.recordId }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = row.type == RecordType.DIARY) { onOpenDiary(row.date) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = row.type.label(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = row.dateLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(
                                text = row.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
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
