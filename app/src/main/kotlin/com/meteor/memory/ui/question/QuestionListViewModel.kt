package com.meteor.memory.ui.question

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.QuestionStore
import com.meteor.memory.core.storage.StoredQuestion
import com.meteor.memory.feature.question.QuestionFormat
import com.meteor.memory.feature.question.QuestionRow
import com.meteor.memory.feature.question.ReminderRow
import com.meteor.memory.system.QuestionReminderController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * 每日一问列表页（ADR-068 / ADR-069）。
 *
 * 列表只负责浏览：把 [QuestionStore] 的**已存储**模型翻译成**已格式化**的
 * [QuestionRow]（标签相对「现在」，所以每次重建都要重算），以及删除。
 * 提问草稿搬去了 [QuestionComposeViewModel]，列表不再持有第二份编辑状态。
 */
@HiltViewModel
class QuestionListViewModel @Inject constructor(
    private val questionStore: QuestionStore,
    private val questionReminders: QuestionReminderController,
    private val appLog: AppLog,
) : ViewModel() {

    private val _rows = MutableStateFlow(emptyList<QuestionRow>())
    val rows: StateFlow<List<QuestionRow>> = _rows.asStateFlow()

    init {
        refresh()
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
 * [QuestionFormat.reminderLabel]）；提问页与详情页共用这一份，避免某一处忘了
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
