package com.meteor.memory.ui.question

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.R
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.QuestionStore
import com.meteor.memory.feature.question.QuestionComposerState
import com.meteor.memory.feature.question.QuestionSchedule
import com.meteor.memory.feature.question.ReminderPick
import com.meteor.memory.feature.question.ReminderRow
import com.meteor.memory.system.QuestionReminderController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * 提问页（ADR-069）的草稿与保存。
 *
 * 从 [QuestionListViewModel] 里拆出来：列表不再持有编辑状态，提问页退出时它的
 * 作用域随导航条目一起销毁，草稿不会串到下一次进入。提醒默认带一条「明天同一
 * 时刻」（见 [QuestionSchedule.initialReminders]），所以只写正文也能直接保存。
 */
@HiltViewModel
class QuestionComposeViewModel @Inject constructor(
    private val questionStore: QuestionStore,
    private val questionReminders: QuestionReminderController,
    private val appLog: AppLog,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(newComposer())
    val state: StateFlow<QuestionComposerState> = _state.asStateFlow()

    fun onDraftChange(text: String) {
        _state.update { it.copy(draft = text, error = null) }
    }

    /**
     * 加一条自定义提醒。
     *
     * 滚轮已经挡掉了「早于现在」的时刻，这里仍按 [QuestionSchedule.addAt] 的
     * null 兜底（上限或极端时序）；失败时给一条可重试的提示。
     */
    fun addReminder(pick: ReminderPick) {
        val now = ZonedDateTime.now()
        val next = QuestionSchedule.addAt(
            reminders = currentReminderInstants(),
            date = LocalDate.of(pick.year, pick.month, pick.day),
            time = LocalTime.of(pick.hour, pick.minute),
            now = now,
        )
        if (next == null) {
            _state.update { it.copy(error = context.getString(R.string.question_error_save)) }
            return
        }
        _state.update { it.copy(reminders = questionReminderRows(next, now), error = null) }
    }

    fun removeReminder(at: Instant) {
        val now = ZonedDateTime.now()
        val current = currentReminderInstants()
        val next = QuestionSchedule.remove(current, at)
        if (next == current) return
        _state.update { it.copy(reminders = questionReminderRows(next, now), error = null) }
    }

    /**
     * 保存提问：写盘 → 为这条问题装闹钟 → 通知宿主退回列表。
     *
     * 提醒先过 [QuestionSchedule.pending]：页面开了很久的话，草稿里可能留着已经
     * 过去的时刻，而它只会立刻触发一条通知，从来不是用户配置的意图。
     */
    fun submit(onCreated: () -> Unit) {
        val current = _state.value
        val text = current.draft.trim()
        if (text.isEmpty() || current.submitting) return
        val now = ZonedDateTime.now()
        val reminders = QuestionSchedule.pending(currentReminderInstants(), now)
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    questionStore.create(
                        text = text,
                        reminders = reminders,
                        createdAt = now.toInstant(),
                    )
                }
            }
            val question = created.getOrNull()
            if (question == null) {
                // QuestionStore.create 对空文本、超长文本是 require 抛异常，
                // 与写盘失败给同一条提示：用户能做的都是「改一下再试」。
                appLog.warn(TAG, "question create failed", created.exceptionOrNull())
                _state.update {
                    it.copy(
                        submitting = false,
                        error = context.getString(R.string.question_error_save),
                    )
                }
                return@launch
            }

            // controller 内部直接做阻塞 IO（它假定调用方已在 IO 线程），所以不能
            // 在主线程上直接 await。
            withContext(Dispatchers.IO) { questionReminders.sync(question.id) }
            appLog.info(TAG, "question created: ${question.id} reminders=${reminders.size}")
            _state.update { it.copy(submitting = false) }
            onCreated()
        }
    }

    /** 草稿里的提醒时刻。页面只持有 [ReminderRow]，所以每次操作都从它反推。 */
    private fun currentReminderInstants(): List<Instant> =
        _state.value.reminders.map { it.at }

    private fun newComposer(): QuestionComposerState {
        val now = ZonedDateTime.now()
        return QuestionComposerState(
            reminders = questionReminderRows(QuestionSchedule.initialReminders(now), now),
        )
    }

    private companion object {
        const val TAG = "QuestionCompose"
    }
}
