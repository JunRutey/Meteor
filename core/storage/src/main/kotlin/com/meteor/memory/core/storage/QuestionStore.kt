package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * One saved answer to a daily question (ADR-068).
 *
 * Answers are **append-only**: editing an existing answer is not a supported
 * operation, because each answer is a point in time ("what did I think on the
 * 14th?"). A second thought is a new answer, which is exactly the "multiple
 * answers" behaviour the feature promises.
 */
data class StoredAnswer(
    val id: String,
    val text: String,
    val answeredAt: Instant,
)

/**
 * One daily question with its pending reminders and its answers (ADR-068).
 *
 * [reminders] holds only **pending** instants: a reminder is consumed the moment
 * its alarm is delivered, and the notification's snooze actions append a new
 * one. Keeping the list pending-only means the stored file is also the single
 * source of truth for "what alarms should exist", so every sync can rebuild the
 * alarm from it without extra bookkeeping.
 */
data class StoredQuestion(
    val id: String,
    val text: String,
    val createdAt: Instant,
    val reminders: List<Instant> = emptyList(),
    val answers: List<StoredAnswer> = emptyList(),
) {
    val isAnswered: Boolean get() = answers.isNotEmpty()

    /** When this question was answered for the last time, or null while it is open. */
    val lastAnsweredAt: Instant? get() = answers.maxOfOrNull { it.answeredAt }

    /** Reminder instants still ahead of [now], earliest first. */
    fun upcomingReminders(now: Instant): List<Instant> =
        reminders.filter { it.isAfter(now) }.sorted()

    fun nextReminder(now: Instant): Instant? = upcomingReminders(now).firstOrNull()
}

/**
 * Encrypted storage for the 每日一问 feature (ADR-068).
 *
 * Blocking, like the other `*Queries`/`*Store` types: callers move the work to
 * `Dispatchers.IO` themselves. Every method returns plaintext; the ciphertext
 * never leaves this class.
 */
interface QuestionStore {
    /** Every stored question, newest first. Unreadable files are skipped. */
    fun list(): List<StoredQuestion>

    fun find(id: String): StoredQuestion?

    /** Creates and persists a question; [reminders] may be empty. */
    fun create(
        text: String,
        reminders: List<Instant> = emptyList(),
        createdAt: Instant = Instant.now(),
    ): StoredQuestion

    /** Appends one answer; returns null when [id] is unknown. */
    fun addAnswer(
        id: String,
        text: String,
        answeredAt: Instant = Instant.now(),
    ): StoredQuestion?

    /**
     * Replaces the whole pending-reminder list.
     *
     * Add, consume and clear are all this one call so the caller can never leave
     * the file half-updated: it computes the desired set and writes it once.
     */
    fun updateReminders(id: String, reminders: List<Instant>): StoredQuestion?

    /** Removes the question file; false when there was nothing to delete. */
    fun delete(id: String): Boolean

    companion object {
        /** Long enough for a real question, short enough to stay a single prompt. */
        const val MAX_QUESTION_CHARS = 500

        /** An answer is diary-sized, not chat-sized. */
        const val MAX_ANSWER_CHARS = 20_000

        /**
         * Upper bound on pending reminders per question.
         *
         * The scheduler keeps a single alarm for the earliest pending reminder,
         * so this is a sanity limit on the file, not on alarm slots.
         */
        const val MAX_REMINDERS = 8
    }
}

/**
 * File-per-question store encrypted with the archive's Keystore key (ADR-068).
 *
 * Layout: `我的人生/每日一问/<id>.bin`, encrypted JSON. The `.bin` suffix keeps
 * the file out of [RecordScanner], which only treats `.md` as records — a
 * question is not a diary entry and must not become archive "evidence" for the
 * search index or the AI tools. The unified payload key is deliberately **not**
 * used: this is small text, so the Keystore key is the right one (the split
 * mirrors `AiChatStore` and `ADR-067`'s text/binary boundary).
 */
class EncryptedQuestionStore(
    rootDir: File,
    private val crypto: FileCrypto,
) : QuestionStore {

    private val dir = StorageLayout(rootDir).questionsDir()

    override fun list(): List<StoredQuestion> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == EXTENSION }
            // One unreadable file (interrupted write, newer format) must not hide
            // every other question. It stays on disk for a later repair.
            .mapNotNull { file -> runCatching { readQuestion(file) }.getOrNull() }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    override fun find(id: String): StoredQuestion? {
        if (!ID_PATTERN.matches(id)) return null
        val file = fileFor(id)
        if (!file.isFile) return null
        return runCatching { readQuestion(file) }.getOrNull()
    }

    override fun create(text: String, reminders: List<Instant>, createdAt: Instant): StoredQuestion {
        val question = StoredQuestion(
            id = newId(),
            text = requireText(text),
            createdAt = createdAt,
            reminders = requireReminders(reminders),
        )
        write(question)
        return question
    }

    override fun addAnswer(id: String, text: String, answeredAt: Instant): StoredQuestion? {
        val existing = find(id) ?: return null
        val answer = StoredAnswer(
            id = newId(),
            text = requireAnswer(text),
            answeredAt = answeredAt,
        )
        val updated = existing.copy(answers = existing.answers + answer)
        write(updated)
        return updated
    }

    override fun updateReminders(id: String, reminders: List<Instant>): StoredQuestion? {
        val existing = find(id) ?: return null
        val updated = existing.copy(reminders = requireReminders(reminders))
        write(updated)
        return updated
    }

    override fun delete(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        val file = fileFor(id)
        if (!file.exists()) return false
        if (!file.delete()) error("question-store-delete-failed")
        return true
    }

    private fun requireText(raw: String): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "question-text-empty" }
        require(text.length <= QuestionStore.MAX_QUESTION_CHARS) { "question-text-too-long" }
        return text
    }

    private fun requireAnswer(raw: String): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "question-answer-empty" }
        require(text.length <= QuestionStore.MAX_ANSWER_CHARS) { "question-answer-too-long" }
        return text
    }

    /**
     * Deduplicates and sorts the reminder set.
     *
     * Two alarms at the same instant would collapse into one notification, so the
     * duplicates are removed here instead of at delivery time.
     */
    private fun requireReminders(reminders: List<Instant>): List<Instant> {
        val unique = reminders.distinct().sorted()
        require(unique.size <= QuestionStore.MAX_REMINDERS) { "question-reminder-limit" }
        return unique
    }

    private fun write(question: StoredQuestion) {
        if (!dir.exists() && !dir.mkdirs()) error("question-store-unavailable")
        val destination = fileFor(question.id)
        val temporary = File.createTempFile("${question.id}-", ".tmp", dir)
        try {
            temporary.writeBytes(crypto.encrypt(encode(question).toByteArray(Charsets.UTF_8)))
            replaceAtomically(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun readQuestion(file: File): StoredQuestion {
        val plaintext = runCatching { String(crypto.decrypt(file.readBytes()), Charsets.UTF_8) }
            .getOrElse { throw IllegalStateException("question-store-decrypt-failed", it) }
        return runCatching { decode(JSONObject(plaintext)) }
            .getOrElse { throw IllegalStateException("question-store-decode-failed", it) }
    }

    /** Android's rename is atomic on the same filesystem; replace in one step. */
    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(question: StoredQuestion): String = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("id", question.id)
        put("text", question.text)
        put("createdAt", question.createdAt.toEpochMilli())
        put("reminders", JSONArray(question.reminders.map { it.toEpochMilli() }))
        put("answers", JSONArray(question.answers.map { answer ->
            JSONObject()
                .put("id", answer.id)
                .put("text", answer.text)
                .put("answeredAt", answer.answeredAt.toEpochMilli())
        }))
    }.toString()

    private fun decode(root: JSONObject): StoredQuestion {
        require(root.optInt("version") == FORMAT_VERSION) { "unknown-question-version" }
        val id = root.getString("id")
        require(ID_PATTERN.matches(id)) { "question-id-invalid" }

        val reminderArray = root.optJSONArray("reminders")
        val reminders = if (reminderArray == null) {
            emptyList()
        } else {
            (0 until reminderArray.length()).map { index ->
                Instant.ofEpochMilli(reminderArray.getLong(index))
            }
        }

        val answerArray = root.optJSONArray("answers")
        val answers = if (answerArray == null) {
            emptyList()
        } else {
            (0 until answerArray.length()).map { index ->
                val item = answerArray.getJSONObject(index)
                StoredAnswer(
                    id = item.getString("id"),
                    text = item.getString("text"),
                    answeredAt = Instant.ofEpochMilli(item.getLong("answeredAt")),
                )
            }
        }

        return StoredQuestion(
            id = id,
            text = root.getString("text"),
            createdAt = Instant.ofEpochMilli(root.getLong("createdAt")),
            reminders = reminders,
            answers = answers,
        )
    }

    private fun fileFor(id: String): File = File(dir, "$id.$EXTENSION")

    /** 32 lowercase hex characters, like every other archive id (archive §4.1). */
    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    private companion object {
        const val FORMAT_VERSION = 1
        const val EXTENSION = "bin"
        val ID_PATTERN = Regex("^[a-f0-9]{32}$")
    }
}
