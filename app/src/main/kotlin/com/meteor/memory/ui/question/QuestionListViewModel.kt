package com.meteor.memory.ui.question

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.R
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.QuestionStore
import com.meteor.memory.core.storage.StoredQuestion
import com.meteor.memory.feature.question.QuestionComposerState
import com.meteor.memory.feature.question.QuestionFormat
import com.meteor.memory.feature.question.QuestionRow
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
 * 每日一问列表页（ADR-068）。
 *
 * 这一层只做三件事：把 [QuestionStore] 的**已存储**模型翻译成**已格式化**的
 * [QuestionRow] / [ReminderRow]（标签相对「现在」，所以每次重建都要重算）、
 * 维护提问草稿，以及在写盘之后叫 [QuestionReminderController] 重排闹钟。
 * 问题的唯一真相是文件，这里不保存第二份列表。
 */
@HiltViewModel
class QuestionListViewModel @Inject constructor(
    private val questionStore: QuestionStore,
    private val questionReminders: QuestionReminderController,
    private val appLog: AppLog,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _rows = MutableStateFlow(emptyList<QuestionRow>())
    val rows: StateFlow<List<QuestionRow>> = _rows.asStateFlow()

    /**
     * 提问草稿。提醒默认带一条「明天同一时刻」：写下想法就保存的人也该收到提醒，
     * 不必先学会配置提醒（见 [QuestionSchedule.initialReminders]）。
     */
    private val _composer = MutableStateFlow(newComposer())
    val composer: StateFlow<QuestionComposerState> = _composer.asStateFlow()

    init {
        refresh()
    }

    fun onDraftChange(text: String) {
        _composer.update { it.copy(draft = text, error = null) }
    }

    /** 加一条「明天同一时刻」；已经满 [QuestionSchedule.MAX_REMINDERS] 时保持不变。 */
    fun addDefaultReminder() {
        val now = ZonedDateTime.now()
        val current = currentReminderInstants()
        val next = QuestionSchedule.addDefault(current, now)
        if (next == current) return
        _composer.update { it.copy(reminders = questionReminderRows(next, now), error = null) }
    }

    /**
     * 加一条自定义提醒。
     *
     * [QuestionSchedule.addAt] 返回 null 表示这个时刻不合法（不在未来，或已满上限）。
     * 说明文案本该是 feature 模块的「提醒时间必须晚于现在」，但 `:app` 不能引用
     * `:feature-question` 的 `R`，所以这里退回 `:app` 已有的通用失败文案；
     * 要在界面上说清原因，得先往 `:app` 的 strings.xml 加键。
     */
    fun addCustomReminder(pick: ReminderPick) {
        val now = ZonedDateTime.now()
        val next = QuestionSchedule.addAt(
            reminders = currentReminderInstants(),
            date = LocalDate.of(pick.year, pick.month, pick.day),
            time = LocalTime.of(pick.hour, pick.minute),
            now = now,
        )
        if (next == null) {
            _composer.update { it.copy(error = context.getString(R.string.question_error_save)) }
            return
        }
        _composer.update { it.copy(reminders = questionReminderRows(next, now), error = null) }
    }

    fun removeReminder(at: Instant) {
        val now = ZonedDateTime.now()
        val current = currentReminderInstants()
        val next = QuestionSchedule.remove(current, at)
        if (next == current) return
        _composer.update { it.copy(reminders = questionReminderRows(next, now), error = null) }
    }

    /**
     * 保存提问：写盘 → 为这条问题装闹钟 → 清空草稿（提醒回到「明天同一时刻」）→ 重读列表。
     *
     * 提醒先过 [QuestionSchedule.pending]：页面开了很久的话，草稿里可能留着已经
     * 过去的时刻，而它只会立刻触发一条通知，从来不是用户配置的意图。
     */
    fun submit() {
        val current = _composer.value
        val text = current.draft.trim()
        if (text.isEmpty() || current.submitting) return
        val now = ZonedDateTime.now()
        val reminders = QuestionSchedule.pending(currentReminderInstants(), now)
        _composer.update { it.copy(submitting = true, error = null) }

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
                _composer.update {
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
            _composer.value = newComposer()
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { questionStore.delete(id) }
                    .onFailure { appLog.warn(TAG, "question delete failed: $id", it) }
                    .getOrDefault(false)
            }
            if (!removed) appLog.warn(TAG, "question delete removed nothing: $id")
            // 已知限制：QuestionReminderController 没有「按问题取消」的入口，这里只能
            // syncAll 重排，而被删问题的旧闹钟不在 questionStore.list() 里了。它会在
            // 触发时刻被 QuestionReminderReceiver 发现问题不存在而静默忽略 —— 用户
            // 无感，但系统闹钟表里会留一条空闹钟。改 controller 接口不在本次范围内。
            withContext(Dispatchers.IO) { questionReminders.syncAll() }
            refresh()
        }
    }

    /** 重读文件并重建行；格式化放在 IO 返回之后，用主线程的「现在」算标签。 */
    fun refresh() {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { questionStore.list() }
                    .onFailure { appLog.warn(TAG, "question list unreadable", it) }
                    .getOrDefault(emptyList())
            }
            val now = ZonedDateTime.now()
            _rows.value = stored.map { it.toRow(now) }
        }
    }

    /** 草稿里的提醒时刻。列表只持有 [ReminderRow]，所以每次操作都从它反推。 */
    private fun currentReminderInstants(): List<Instant> =
        _composer.value.reminders.map { it.at }

    private fun newComposer(): QuestionComposerState {
        val now = ZonedDateTime.now()
        return QuestionComposerState(
            reminders = questionReminderRows(QuestionSchedule.initialReminders(now), now),
        )
    }

    private fun StoredQuestion.toRow(now: ZonedDateTime): QuestionRow {
        val last = answers.maxByOrNull { it.answeredAt }
        return QuestionRow(
            id = id,
            text = text,
            createdLabel = QuestionFormat.stamp(createdAt, now),
            answeredCount = answers.size,
            answerPreview = last?.let { firstLine(it.text) },
            lastAnsweredLabel = last?.let { QuestionFormat.stamp(it.answeredAt, now) },
            nextReminderLabel = nextReminder(now.toInstant())
                ?.let { QuestionFormat.reminderLabel(it, now) },
        )
    }

    private companion object {
        const val TAG = "QuestionList"

        /** 列表是「一眼」而不是阅读器：回答预览只取第一行非空文本。 */
        fun firstLine(text: String): String? =
            text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }
}

/**
 * 提醒时刻 → 提醒行。标签是相对「现在」的，所以每次重建都必须重算（见
 * [QuestionFormat.reminderLabel]）；列表页与详情页共用这一份，避免某一处忘了
 * 重算，把「明天 09:00」一直显示到后天。
 */
internal fun questionReminderRows(reminders: List<Instant>, now: ZonedDateTime): List<ReminderRow> =
    reminders.sorted().map { at ->
        ReminderRow(
            at = at,
            label = QuestionFormat.reminderLabel(at, now),
            expired = !at.isAfter(now.toInstant()),
        )
    }
