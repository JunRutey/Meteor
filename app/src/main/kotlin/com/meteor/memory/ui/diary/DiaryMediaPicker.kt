package com.meteor.memory.ui.diary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.feature.media.MediaPickerScreen
import com.meteor.memory.feature.media.PickedMedia

/**
 * The diary editor's half of ADR-052 §5 D: turning "the toolbar wants these kinds"
 * into a picker the user can finish, without teaching `:feature-editor` about
 * navigation.
 *
 * The editor only emits `onPickMedia(kinds)`; this state holds that request, and
 * [DiaryMediaPicker] renders the picker while it is set. Keeping the request here
 * rather than in a nav route means the diary edit screen stays one destination and
 * a dismissed picker can never leave an orphaned back-stack entry.
 */
class DiaryMediaPickerState internal constructor() {

    /** Kinds to offer, or null when the picker is closed. */
    var kinds: Set<MediaKind>? by mutableStateOf(null)
        private set

    fun open(requested: Set<MediaKind>) {
        if (requested.isEmpty()) return
        kinds = requested
    }

    fun close() {
        kinds = null
    }
}

@Composable
fun rememberDiaryMediaPicker(): DiaryMediaPickerState = remember { DiaryMediaPickerState() }

/**
 * Renders the in-app picker over the editing screen while a request is pending.
 *
 * Call it from the same `Box` as [DiaryEditScreen] and hand the result to
 * `DiaryEditViewModel.importMedia`: the import — and therefore the `_self`
 * ownership decision — stays in the view model, exactly like the legacy image path.
 */
@Composable
fun DiaryMediaPicker(
    state: DiaryMediaPickerState,
    onPicked: (List<PickedMedia>) -> Unit,
) {
    val kinds = state.kinds ?: return
    MediaPickerScreen(
        kinds = kinds,
        onConfirm = { picked ->
            state.close()
            onPicked(picked)
        },
        onBack = state::close,
    )
}
