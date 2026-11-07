package com.meteor.memory.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Stroke
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
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.search_back)) }
            Text(
                text = stringResource(R.string.search_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            leadingIcon = { SearchGlyph() },
            placeholder = { Text(text = stringResource(R.string.search_hint)) },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        when (state) {
            SearchUiState.Loading -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            SearchUiState.Idle -> SearchHint(
                text = stringResource(R.string.search_idle),
                modifier = Modifier.weight(1f),
            )

            SearchUiState.Empty -> SearchHint(
                text = stringResource(R.string.search_empty),
                modifier = Modifier.weight(1f),
            )

            is SearchUiState.Failed -> SearchMessage(
                text = stringResource(R.string.search_failed, state.message),
            )

            is SearchUiState.Results -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.rows, key = { it.recordId }) { row ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = row.type == RecordType.DIARY) {
                                onOpenDiary(row.date)
                            },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = row.type.label(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = row.dateLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(
                                text = row.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SearchGlyph()
        }
        Text(
            text = text,
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SearchMessage(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun SearchGlyph() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.28f,
            center = center,
            style = Stroke(width = stroke),
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.62f, size.height * 0.62f),
            end = Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
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
