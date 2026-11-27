package com.meteor.memory.ui.question

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.R
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.QuestionStore
import com.meteor.memory.core.storage.StoredQuestion
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.feature.question.AnswerRow
import com.meteor.memory.feature.question.QuestionDetailUi
import com.meteor.memory.feature.question.QuestionFormat
import com.meteor.memory.feature.question.QuestionSchedule
import com.meteor.memory.feature.question.ReminderPick
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
 * 每日一问详情页（ADR-068）。
 *
 * 两条规则由这里唯一掌握，屏幕不参与判断：
 * 1. **详情永远先阅读**，点底部「回答 / 再回答一次」才进编辑；不再按「有没有回答过」
 *    自动切换形态。这样从列表点进来、从通知点进来，看到的是同一个页面。
 * 2. **回答可以有很多次**，每次都是新的 [AnswerRow]；保存回答后不再催问，
 *    待提醒被清空（用户已经回答过了）。
 */
@HiltViewModel
class QuestionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val questionStore: QuestionStore,
    private val questionReminders: QuestionReminderController,
    private val appLog: AppLog,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val questionId: String = savedStateHandle.get<String>(ARG_ID).orEmpty()

    private val _state = MutableStateFlow(
        QuestionDetailUi(
            id = questionId,
            text = "",
            createdLabel = "",
            reminders = emptyList(),
            answers = emptyList(),
        ),
    )
    val state: StateFlow<QuestionDetailUi> = _state.asStateFlow()

    /** 最近一次读到的记录；提醒增删都以它为准，避免从展示模型反推。 */
    private var question: StoredQuestion? = null

    init {
        // 与 PersonDetailViewModel 一致：进入即读一次，页面的 RefreshOnResume 再读一次。
        // 第二次读保留编辑状态，见 [load] 的 [QuestionDetailUi.loading] 判断。
        load()
    }

    /**
     * 重读档案。
     *
     * 读取只刷新数据，不决定「编辑还是阅读」：详情页永远先阅读，编辑由用户按
     * 底部的回答按钮进入。这样切出去再回来不会把没写完的编辑器关掉，也不会
     * 因为某个问题刚被答过就把人从阅读页弹进编辑器。
     */
    fun load() {
        if (questionId.isBlank()) {
            _state.update {
                it.copy(
                    loading = false,
                    exists = false,
                    error = context.getString(R.string.question_error_load),
                )
            }
            return
        }
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { questionStore.find(questionId) }
                    .onFailure { appLog.warn(TAG, "question load failed: $questionId", it) }
                    .getOrNull()
            }
            if (stored == null) {
                question = null
                _state.update {
                    it.copy(
                        loading = false,
                        exists = false,
                        error = context.getString(R.string.question_error_load),
                    )
                }
                return@launch
            }
            question = stored
            _state.update { previous -> render(stored, previous) }
        }
    }

    fun onDraftChange(text: String) {
        // saved = false：下一次键入之后就不再是「刚存完」，返回确认要能重新触发。
        _state.update { it.copy(draft = text, saved = false, error = null) }
    }

    /** 回答编辑器的 编辑 / 预览 切换，和记日记一样。 */
    fun togglePreview() {
        _state.update { it.copy(previewing = !it.previewing) }
    }

    /** 「回答」/「再回答一次」：空白编辑器，焦点回到正文。 */
    fun startAnswer() {
        _state.update { it.copy(editing = true, previewing = false, draft = "", saved = false) }
    }

    /**
     * 退出回答编辑（取消）：回到阅读形态，丢弃未保存的草稿。
     *
     * 屏幕在有草稿时先弹确认，所以这里不需要再问一遍；保存过的问题文件不受
     * 影响，只是本次回答作废。
     */
    fun cancelAnswer() {
        _state.update {
            it.copy(editing = false, previewing = false, draft = "", saved = false)
        }
    }

    /**
     * 在回答里插入一个 Markdown 链接。
     *
     * 回答编辑器的工具栏由 `feature-editor` 提供，链接对话框确认后回调这里。
     * 和日记一样把插入动作交给宿主，编辑器本身不持有正文。
     */
    fun insertLink(target: String, label: String?) {
        if (target.isBlank()) return
        _state.update {
            it.copy(
                draft = MarkdownEditing.appendLink(it.draft, target, label),
                saved = false,
                error = null,
            )
        }
    }

    /**
     * 保存回答。
     *
     * 成功后按用户要求清空待提醒：问题已经回答过，再催没有意义。**已知取舍**：
     * 若用户在回答期间点了通知的「延迟 1 小时」，那条新提醒也会被一起清掉；
     * 这是边缘时序，且「回答完就不再提醒」是更重要的意图。
     */
    fun saveAnswer() {
        val current = _state.value
        val text = current.draft.trim()
        if (text.isEmpty() || current.saving || current.deleting) return
        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { questionStore.addAnswer(questionId, text) }
                    .onFailure { appLog.warn(TAG, "answer save failed: $questionId", it) }
                    .getOrNull()
            }
            if (saved == null) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = context.getString(R.string.question_error_answer),
                    )
                }
                return@launch
            }

            withContext(Dispatchers.IO) {
                runCatching { questionStore.updateReminders(questionId, emptyList()) }
                    .onFailure { appLog.warn(TAG, "reminder clear failed: $questionId", it) }
            }
            withContext(Dispatchers.IO) { questionReminders.syncAll() }
            appLog.info(TAG, "answer saved: $questionId")

            // 先落状态再 load()：load() 会保留 previewing / draft，所以这里必须
            // 自己把它们清掉，并让 saved = true 一直显示到下一次键入。
            _state.update {
                it.copy(
                    editing = false,
                    previewing = false,
                    draft = "",
                    saving = false,
                    saved = true,
                )
            }
            load()
        }
    }

    /**
     * 删除这个问题。
     *
     * [onDeleted] 只在文件真的消失后才回调，宿主据此 `popBackStack()`；写盘失败时
     * 页面原地不动并显示错误，绝不把用户送走再弹一个「删除失败」。
     */
    fun delete(onDeleted: () -> Unit) {
        val current = _state.value
        if (current.deleting) return
        if (!current.exists) {
            // 文件已经不在了（这里或别处删过）：把意图当成已完成。
            onDeleted()
            return
        }
        _state.update { it.copy(deleting = true, error = null) }

        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { questionStore.delete(questionId) }
            }
            val failure = deleted.exceptionOrNull()
            if (failure != null) {
                appLog.error(TAG, "question delete failed: $questionId", failure)
                _state.update {
                    it.copy(
                        deleting = false,
                        error = context.getString(R.string.question_error_delete),
                    )
                }
                return@launch
            }
            if (deleted.getOrDefault(false).not()) {
                appLog.warn(TAG, "question delete removed nothing: $questionId")
            }
            // 同列表页：controller 没有「按问题取消」的入口，syncAll 不会清掉已删问题
            // 的旧闹钟，它到点会被 QuestionReminderReceiver 静默忽略（已知限制）。
            withContext(Dispatchers.IO) { questionReminders.syncAll() }
            question = null
            _state.update { it.copy(deleting = false, exists = false, saved = false) }
            onDeleted()
        }
    }

    /** 加一条自定义提醒；时刻不合法时见 [QuestionListViewModel] 的取舍说明。 */
    fun addCustomReminder(pick: ReminderPick) {
        val now = ZonedDateTime.now()
        val current = question?.reminders ?: return
        val next = QuestionSchedule.addAt(
            reminders = current,
            date = LocalDate.of(pick.year, pick.month, pick.day),
            time = LocalTime.of(pick.hour, pick.minute),
            now = now,
        )
        if (next == null) {
            _state.update { it.copy(error = context.getString(R.string.question_error_save)) }
            return
        }
        persistReminders(next)
    }

    fun removeReminder(at: Instant) {
        val current = question?.reminders ?: return
        val next = QuestionSchedule.remove(current, at)
        if (next == current) return
        persistReminders(next)
    }

    /**
     * 提醒立即持久化：它是文件里的状态，不是页面的临时选择，所以不能等「保存」。
     * 写盘成功后重排闹钟并重算标签（对照 [QuestionFormat.reminderLabel] 的「现在」）。
     */
    private fun persistReminders(next: List<Instant>) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching { questionStore.updateReminders(questionId, next) }
                    .onFailure { appLog.warn(TAG, "reminder save failed: $questionId", it) }
                    .getOrNull()
            }
            if (updated == null) {
                _state.update { it.copy(error = context.getString(R.string.question_error_save)) }
                return@launch
            }
            question = updated
            withContext(Dispatchers.IO) { questionReminders.sync(questionId) }
            _state.update { previous -> render(updated, previous) }
        }
    }

    /**
     * 记录 + 当前临时字段 → 展示状态。
     *
     * [previous] 提供这个页面自己拥有的东西（草稿、编辑 / 预览、错误、正在删除）；
     * 其余一切来自档案。读取**不**切换编辑形态：`editing` 只由用户按「回答」与
     * 「取消」改变，草稿也绝不在这里被覆盖。
     */
    private fun render(question: StoredQuestion, previous: QuestionDetailUi): QuestionDetailUi {
        val now = ZonedDateTime.now()
        return previous.copy(
            id = question.id,
            text = question.text,
            createdLabel = QuestionFormat.stamp(question.createdAt, now),
            reminders = questionReminderRows(question.reminders, now),
            answers = question.answers.map { answer ->
                AnswerRow(
                    id = answer.id,
                    text = answer.text,
                    answeredAtLabel = QuestionFormat.stamp(answer.answeredAt, now),
                )
            },
            // 读到档案就说明上一次的失败过去了（例如重试加载或重试删提醒成功）。
            error = null,
            exists = true,
            loading = false,
        )
    }

    companion object {
        /** 路由参数名，必须与 `MeteorNavHost` 的 `navArgument` 完全一致。 */
        const val ARG_ID = "questionId"
        private const val TAG = "QuestionDetail"
    }
}
