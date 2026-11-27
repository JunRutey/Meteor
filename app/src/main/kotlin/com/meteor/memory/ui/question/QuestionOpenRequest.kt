package com.meteor.memory.ui.question

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-off between a notification tap and the navigation graph (ADR-068).
 *
 * A notification cannot call `navController.navigate`: it is delivered by the
 * system while the activity may be dead, cold-starting, or already showing some
 * other screen. So the notification only records *which question* the person
 * asked to open, and the shell consumes it once the graph exists.
 *
 * `null` means "nothing pending". [consume] clears it atomically, so a
 * configuration change cannot replay the same navigation twice.
 */
@Singleton
class QuestionOpenRequest @Inject constructor() {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun request(questionId: String) {
        if (questionId.isBlank()) return
        _pending.value = questionId
    }

    /** Returns the pending question id and clears it. */
    fun consume(): String? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }
}
