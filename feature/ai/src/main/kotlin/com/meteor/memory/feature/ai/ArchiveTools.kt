package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.ArchiveDeleteResult
import com.meteor.memory.core.storage.ArchiveRecordDeleter
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.Relation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The tool surface an agent may use. Read tools are always on. */
interface Toolbox {
    fun specs(): List<AiToolSpec>

    fun execute(name: String, arguments: String, allowedPersonId: String?): String

    fun requiresApproval(name: String): Boolean = false
}

/**
 * The agent's capability set (ADR-027 / ADR-028 / ADR-030).
 *
 * Read tools are always available. Write tools (diary, mood, people, deletion)
 * exist only for the capabilities granted in [permissions]; there is
 * deliberately no export, share, network-fetch or shell tool.
 *
 * Isolation rules, enforced here rather than by the prompt:
 * - [allowedPersonId] scopes every read and write to one person's data;
 * - an unclassified or ungranted name is rejected before dispatch;
 * - every argument is validated as a JSON string of bounded length;
 * - every result is bounded, so a single call cannot flood the context.
 */
class ArchiveTools(
    private val rootDir: File,
    private val scanner: RecordScanner,
    private val personQueries: PersonQueries,
    private val moodQueries: MoodQueries,
    private val diaryQueries: DiaryQueries,
    private val attachments: AttachmentStore? = null,
    private val permissions: AiToolPermissions = AiToolPermissions.READ_ONLY,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val archiveRecordDeleter: ArchiveRecordDeleter? = null,
) : Toolbox {

    private val documents: List<Document> by lazy { scanner.scan() }
    private val byId: Map<String, Document> by lazy { documents.associateBy { idOf(it) } }
    private val diaryDates: List<LocalDate> by lazy {
        documents.asSequence()
            .filter { it.type == RecordType.DIARY }
            .map { dateOf(it) }
            .distinct()
            .sortedDescending()
            .toList()
    }

    override fun specs(): List<AiToolSpec> = ALL_SPECS.filter { spec ->
        val capability = TOOL_CAPABILITIES[spec.name] ?: return@filter false
        available(spec.name) && permissions.allows(capability)
    }

    override fun requiresApproval(name: String): Boolean =
        name in APPROVAL_REQUIRED_TOOLS

    /** Always returns a JSON string; failures are JSON too, never exceptions. */
    override fun execute(name: String, arguments: String, allowedPersonId: String?): String = runCatching {
        val capability = TOOL_CAPABILITIES[name] ?: return@runCatching errorJson("unknown-tool")
        if (!available(name)) return@runCatching errorJson("tool-unavailable")
        if (!permissions.allows(capability)) return@runCatching errorJson("permission-denied")
        if (
            allowedPersonId != null &&
            name in PERSON_SCOPE_DENIED_TOOLS
        ) {
            return@runCatching errorJson("not-allowed")
        }
        if (arguments.length > MAX_TOOL_ARGUMENT_CHARS) return@runCatching errorJson("arguments-too-large")

        val args = try {
            JSONObject(arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return@runCatching errorJson("bad-arguments")
        }
        if (hasOversizedString(args)) return@runCatching errorJson("argument-too-long")
        when (name) {
            TOOL_LIST_PEOPLE -> listPeople(allowedPersonId)
            TOOL_ARCHIVE_OVERVIEW -> archiveOverviewJson(allowedPersonId)
            TOOL_READ_PERSON -> readPerson(args.str("person_id"), allowedPersonId)
            TOOL_LIST_RECORDS -> listRecords(
                personId = args.str("person_id"),
                from = args.str("from"),
                to = args.str("to"),
                allowedPersonId = allowedPersonId,
            )
            TOOL_READ_RECORD -> readRecord(args.str("record_id"), allowedPersonId)
            TOOL_READ_DIARY -> readDiary(args.str("date"), allowedPersonId)
            TOOL_DIARY_DATES -> listDiaryDates(args.optInt("limit", 0), allowedPersonId)
            TOOL_READ_MOODS -> readMoods(
                from = args.str("from"),
                to = args.str("to"),
                days = args.int("days", 0),
            )
            TOOL_SEARCH -> search(
                query = args.str("query"),
                personId = args.str("person_id"),
                allowedPersonId = allowedPersonId,
            )
            TOOL_LIST_ATTACHMENTS -> listAttachments(args.str("person_id"), allowedPersonId)
            TOOL_READ_ATTACHMENT_TEXT -> readAttachmentText(
                attachmentId = args.str("attachment_id"),
                allowedPersonId = allowedPersonId,
            )

            TOOL_SAVE_DIARY -> saveDiary(args.str("date"), args.str("body"))
            TOOL_APPEND_DIARY -> appendDiary(args.str("date"), args.str("text"))
            TOOL_SET_MOOD -> setMood(args.str("date"), args.str("mood"))
            TOOL_CREATE_PERSON -> createPerson(
                name = args.str("name"),
                relation = args.str("relation"),
                note = args.str("note"),
                notes = args.str("notes"),
            )

            TOOL_UPDATE_PERSON -> updatePerson(
                personId = args.str("person_id"),
                name = args.str("name"),
                relation = args.str("relation"),
                note = args.str("note"),
                notes = args.str("notes"),
                allowedPersonId = allowedPersonId,
            )
            TOOL_DELETE_RECORD -> deleteRecord(
                recordType = args.str("record_type"),
                date = args.str("date"),
            )

            else -> errorJson("unknown-tool")
        }
    }.getOrElse { errorJson("tool-failed") }

    /**
     * Retrieval the app performs **before** calling the model: the question is
     * tokenised locally, the archive is ranked, and only a few short windows are
     * returned. This keeps prompts small and still grounds the answer when a
     * provider never issues a tool call. [personId] scopes it to one person.
     */
    fun searchContext(query: String, personId: String?, maxHits: Int, maxChars: Int): String {
        if (query.isBlank()) return ""
        val answer = AskEngine.answer(
            request = AskRequest(question = query, personId = personId, maxHits = maxHits),
            corpus = documents,
            recordId = ::idOf,
        )
        if (answer.matches.isEmpty()) {
            return "No archive record matched this question's keywords " +
                "(checked ${answer.consultedRecords} records)."
        }

        val builder = StringBuilder()
        builder.append("Archive hits: ").append(answer.matches.size)
            .append(" (checked ").append(answer.consultedRecords).append(" records).\n")
        var used = builder.length
        var shown = 0
        for (match in answer.matches) {
            val date = match.at.atZone(zone).toLocalDate().toString()
            val snippet = match.snippet.replace('\n', ' ').trim().take(PREFETCH_SNIPPET_CHARS)
            val block = "- date=$date type=${match.type.wire} record_id=${match.recordId}\n  $snippet\n"
            if (used + block.length > maxChars && shown > 0) break
            builder.append(block)
            used += block.length
            shown++
        }
        return builder.toString().trimEnd()
    }

    /** Diary dates that actually exist, newest first (At most [limit]). */
    fun recentDiaryDates(limit: Int = 8): List<LocalDate> = diaryDates.take(limit)

    /** Compact, content-free facts so the model knows what the archive holds. */
    fun archiveOverview(): String {
        val diaries = diaryDates
        return buildString {
            append("diary_entries=").append(diaries.size)
            diaries.firstOrNull()?.let { append(", latest_diary=").append(it) }
            diaries.lastOrNull()?.let { append(", earliest_diary=").append(it) }
            append(", records_total=").append(documents.size)
        }
    }

    /** Same facts as [archiveOverview], as a tool result and scoped to one person. */
    private fun archiveOverviewJson(allowedPersonId: String?): String {
        val visible = documents.filter { documentAllowed(it, allowedPersonId) }
        val diaries = visible.asSequence()
            .filter { it.type == RecordType.DIARY }
            .map { dateOf(it) }
            .distinct()
            .sortedDescending()
            .toList()
        val people = personQueries.list()
            .count { allowedPersonId == null || it.personId == allowedPersonId }
        return JSONObject().apply {
            put("records_total", visible.size)
            put("diary_entries", diaries.size)
            put("latest_diary", diaries.firstOrNull()?.toString().orEmpty())
            put("earliest_diary", diaries.lastOrNull()?.toString().orEmpty())
            put("people_total", people)
        }.toString()
    }

    /**
     * Records inside a calendar window, chosen without any model guesswork:
     * diaries and chat logs only, each with its id and a short verbatim excerpt.
     * Respects the persona scope so a persona chat never sees other people.
     */
    fun dateContext(
        window: DateWindow,
        allowedPersonId: String?,
        maxRecords: Int,
        maxChars: Int,
    ): String {
        val inWindow = documents.asSequence()
            .filter { it.type == RecordType.DIARY || it.type == RecordType.CHAT_LOG }
            .filter { window.contains(dateOf(it)) }
            .filter { documentAllowed(it, allowedPersonId) }
            .sortedByDescending { it.timestamp }
            .toList()

        if (inWindow.isEmpty()) {
            val recent = recentDiaryDates(5).joinToString(", ").ifEmpty { "none" }
            return "No diary or chat records in ${window.from}..${window.to} (window=${window.label}). " +
                "Recent diary dates: $recent."
        }

        val builder = StringBuilder()
        builder.append("Records in ").append(window.from).append("..").append(window.to)
            .append(" (window=").append(window.label).append("): ")
            .append(inWindow.size).append('\n')
        var used = builder.length
        var shown = 0
        for (document in inWindow) {
            val snippet = document.body.replace('\n', ' ').trim().take(PREFETCH_SNIPPET_CHARS)
            val block = "- date=${dateOf(document)} type=${document.type.wire} " +
                "record_id=${idOf(document)}\n  $snippet\n"
            if (used + block.length > maxChars && shown > 0) break
            builder.append(block)
            used += block.length
            shown++
            if (shown >= maxRecords) break
        }
        if (inWindow.size > shown) builder.append("(").append(shown).append(" of ")
            .append(inWindow.size).append(" shown)\n")
        return builder.toString().trimEnd()
    }

    // --- tools -------------------------------------------------------------

    private fun listPeople(allowedPersonId: String?): String {
        val array = JSONArray()
        personQueries.list()
            .filter { allowedPersonId == null || it.personId == allowedPersonId }
            .take(MAX_PEOPLE)
            .forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("person_id", entry.personId)
                        put("name", entry.displayName.orEmpty())
                        put("relation", entry.relation?.code.orEmpty())
                    },
                )
            }
        return array.toString()
    }

    private fun readPerson(personId: String, allowedPersonId: String?): String {
        if (personId.isBlank()) return errorJson("missing-person-id")
        if (!personAllowed(personId, allowedPersonId)) return errorJson("not-allowed")
        val profile = personQueries.read(personId) ?: return errorJson("not-found")
        return JSONObject().apply {
            put("person_id", personId)
            put("name", profile.displayName)
            put("relation", profile.relation?.code.orEmpty())
            put("note", profile.note)
            put("notes", profile.notes.take(MAX_BODY_CHARS))
        }.toString()
    }

    private fun listRecords(personId: String, from: String, to: String, allowedPersonId: String?): String {
        if (personId.isNotBlank() && !personAllowed(personId, allowedPersonId)) {
            return errorJson("not-allowed")
        }
        val start = if (from.isBlank()) null else parseDate(from) ?: return errorJson("bad-date")
        val end = if (to.isBlank()) null else parseDate(to) ?: return errorJson("bad-date")
        if (start != null && end != null && end < start) return errorJson("bad-range")

        val link = personId.takeIf { it.isNotBlank() }?.let { personLink(it) }
        val array = JSONArray()
        documents.asSequence()
            .filter { it.type != RecordType.PERSONA }
            .filter { documentAllowed(it, allowedPersonId) }
            .filter {
                when {
                    personId.isBlank() -> true
                    it.personId == personId -> true
                    else -> it.type == RecordType.DIARY && link != null && it.body.contains(link)
                }
            }
            .filter { start == null || !dateOf(it).isBefore(start) }
            .filter { end == null || !dateOf(it).isAfter(end) }
            .sortedByDescending { it.timestamp }
            .take(MAX_RECORDS)
            .forEach { document ->
                array.put(
                    JSONObject().apply {
                        put("record_id", idOf(document))
                        put("type", document.type.wire)
                        put("date", dateOf(document).toString())
                    },
                )
            }
        return array.toString()
    }

    private fun readDiary(date: String, allowedPersonId: String?): String {
        val day = parseDate(date) ?: return errorJson("bad-date")
        val document = documents.asSequence()
            .filter { it.type == RecordType.DIARY && dateOf(it) == day }
            .filter { documentAllowed(it, allowedPersonId) }
            .firstOrNull()
        if (document == null) {
            return JSONObject().apply {
                put("found", false)
                put("date", day.toString())
                put("recent_diary_dates", JSONArray(recentDiaryDates(5).map { it.toString() }))
            }.toString()
        }
        return JSONObject().apply {
            put("found", true)
            put("record_id", idOf(document))
            put("date", day.toString())
            put("body", document.body.take(MAX_BODY_CHARS))
        }.toString()
    }

    private fun listDiaryDates(limit: Int, allowedPersonId: String?): String {
        val dates = documents.asSequence()
            .filter { it.type == RecordType.DIARY }
            .filter { documentAllowed(it, allowedPersonId) }
            .map { dateOf(it) }
            .distinct()
            .sortedDescending()
            .take(limit.coerceIn(1, MAX_RECORDS))
            .map { it.toString() }
            .toList()
        return JSONArray(dates).toString()
    }

    private fun readRecord(recordId: String, allowedPersonId: String?): String {
        val document = byId[recordId] ?: return errorJson("not-found")
        if (!documentAllowed(document, allowedPersonId)) return errorJson("not-allowed")
        return JSONObject().apply {
            put("record_id", recordId)
            put("type", document.type.wire)
            put("date", dateOf(document).toString())
            put("body", document.body.take(MAX_BODY_CHARS))
        }.toString()
    }

    private fun readMoods(from: String, to: String, days: Int): String {
        val (start, end) = when {
            days > 0 -> {
                val today = LocalDate.now(zone)
                today.minusDays(days.coerceIn(1, 366) - 1L) to today
            }

            else -> {
                val start = parseDate(from) ?: return errorJson("bad-date")
                val end = parseDate(to) ?: return errorJson("bad-date")
                start to end
            }
        }
        if (end < start) return errorJson("bad-range")

        val array = JSONArray()
        moodQueries.range(start, end).entries.sortedBy { it.key }.forEach { (date, mood) ->
            array.put(
                JSONObject().apply {
                    put("date", date.toString())
                    put("mood", mood.code)
                },
            )
        }
        return array.toString()
    }

    private fun search(query: String, personId: String, allowedPersonId: String?): String {
        if (query.isBlank()) return errorJson("missing-query")
        if (personId.isNotBlank() && !personAllowed(personId, allowedPersonId)) {
            return errorJson("not-allowed")
        }
        val scoped = personId.ifBlank { allowedPersonId }
        val answer = AskEngine.answer(
            request = AskRequest(question = query, personId = scoped, maxHits = MAX_SEARCH_HITS),
            corpus = documents,
            recordId = ::idOf,
        )

        val array = JSONArray()
        answer.matches.forEach { match ->
            array.put(
                JSONObject().apply {
                    put("date", match.at.atZone(zone).toLocalDate().toString())
                    put("type", match.type.wire)
                    put("record_id", match.recordId)
                    put("snippet", match.snippet.take(MAX_SNIPPET_CHARS))
                },
            )
        }
        return array.toString()
    }

    // --- attachments (read-only, metadata and extracted text only) ----------

    private fun listAttachments(personId: String, allowedPersonId: String?): String {
        val store = attachments ?: return errorJson("tool-unavailable")
        if (personId.isNotBlank() && !personAllowed(personId, allowedPersonId)) {
            return errorJson("not-allowed")
        }
        val owner = personId.ifBlank { allowedPersonId }
        val array = JSONArray()
        store.list(owner).take(MAX_ATTACHMENTS).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("attachment_id", item.id)
                    put("file_name", item.fileName.take(MAX_ATTACHMENT_NAME_CHARS))
                    put("mime_type", item.mimeType)
                    put("size_bytes", item.sizeBytes)
                    put("date", item.modifiedAt.atZone(zone).toLocalDate().toString())
                    put("person_id", item.personId.orEmpty())
                },
            )
        }
        return array.toString()
    }

    /**
     * Only the text extracted at import time is exposed: the model never gets a
     * decrypted binary, so it cannot smuggle a file out through the prompt.
     */
    private fun readAttachmentText(attachmentId: String, allowedPersonId: String?): String {
        val store = attachments ?: return errorJson("tool-unavailable")
        if (attachmentId.isBlank()) return errorJson("missing-attachment-id")
        if (!ATTACHMENT_ID_PATTERN.matches(attachmentId)) return errorJson("bad-attachment-id")

        val item = store.list(allowedPersonId).firstOrNull { it.id == attachmentId }
            ?: return errorJson("not-allowed")
        val text = store.readIndexedText(attachmentId) ?: return errorJson("no-indexed-text")
        return JSONObject().apply {
            put("attachment_id", attachmentId)
            put("file_name", item.fileName.take(MAX_ATTACHMENT_NAME_CHARS))
            put("text", text.take(MAX_ATTACHMENT_TEXT_CHARS))
        }.toString()
    }

    // --- write tools (only for the granted capabilities) -------------------

    private fun saveDiary(date: String, body: String): String {
        val day = parseDate(date) ?: return errorJson("bad-date")
        val text = body.trim().take(MAX_WRITE_CHARS)
        if (text.isBlank()) return errorJson("empty-body")
        diaryQueries.save(day, text)
        return savedJson(day)
    }

    private fun appendDiary(date: String, text: String): String {
        val day = parseDate(date) ?: return errorJson("bad-date")
        val addition = text.trim().take(MAX_WRITE_CHARS)
        if (addition.isBlank()) return errorJson("empty-body")
        val existing = diaryQueries.read(day)?.body.orEmpty().trimEnd()
        diaryQueries.save(day, if (existing.isBlank()) addition else "$existing\n\n$addition")
        return savedJson(day)
    }

    private fun savedJson(day: LocalDate): String =
        JSONObject().put("saved", true).put("date", day.toString()).toString()

    private fun setMood(date: String, mood: String): String {
        val day = parseDate(date) ?: return errorJson("bad-date")
        val value = Mood.fromCode(mood.trim().lowercase()) ?: return errorJson("bad-mood")
        moodQueries.save(day, value)
        return JSONObject().put("saved", true)
            .put("date", day.toString())
            .put("mood", value.code)
            .toString()
    }

    private fun deleteRecord(recordType: String, date: String): String {
        val day = parseDate(date) ?: return errorJson("bad-date")
        val deleter = archiveRecordDeleter ?: return errorJson("tool-unavailable")
        val result = when (recordType) {
            "diary" -> deleter.deleteDiary(day)
            "mood" -> deleter.deleteMood(day)
            else -> return errorJson("bad-record-type")
        }
        return when (result) {
            ArchiveDeleteResult.DELETED -> JSONObject().put("deleted", true)
                .put("record_type", recordType)
                .put("date", day.toString())
                .toString()
            ArchiveDeleteResult.NOT_FOUND -> errorJson("not-found")
            ArchiveDeleteResult.FAILED -> errorJson("delete-failed")
        }
    }

    private fun createPerson(name: String, relation: String, note: String, notes: String): String {
        val displayName = name.trim()
        if (displayName.isBlank()) return errorJson("empty-name")
        val id = personQueries.create(
            displayName = displayName,
            relation = relation.trim().takeIf { it.isNotBlank() }?.let { Relation.fromCode(it) },
            note = note.trim().take(MAX_NOTE_CHARS),
            notes = notes.trim().take(MAX_WRITE_CHARS),
        )
        return JSONObject().put("created", true).put("person_id", id).toString()
    }

    private fun updatePerson(
        personId: String,
        name: String,
        relation: String,
        note: String,
        notes: String,
        allowedPersonId: String?,
    ): String {
        if (personId.isBlank()) return errorJson("missing-person-id")
        if (!personAllowed(personId, allowedPersonId)) return errorJson("not-allowed")
        val existing = personQueries.read(personId) ?: return errorJson("not-found")
        val chosenRelation = relation.trim().takeIf { it.isNotBlank() }?.let { Relation.fromCode(it) }
            ?: existing.relation
        personQueries.update(
            personId = personId,
            displayName = name.trim().ifBlank { existing.displayName },
            relation = chosenRelation,
            note = note.trim().ifBlank { existing.note }.take(MAX_NOTE_CHARS),
            notes = notes.trim().ifBlank { existing.notes }.take(MAX_WRITE_CHARS),
        )
        return JSONObject().put("updated", true).put("person_id", personId).toString()
    }

    // --- guards ------------------------------------------------------------

    /** Attachment tools exist only when a store was wired in. */
    private fun available(name: String): Boolean = when {
        name in ATTACHMENT_TOOLS -> attachments != null
        name == TOOL_DELETE_RECORD -> archiveRecordDeleter != null
        else -> true
    }

    /**
     * Strict string argument. A non-string (number, object, array, JSON null) is
     * treated as absent instead of being coerced to its `toString()`, so a
     * malformed call cannot smuggle a path or an id past the validators.
     */
    private fun JSONObject.str(name: String): String =
        (opt(name) as? String)?.trim().orEmpty()

    private fun JSONObject.int(name: String, default: Int): Int {
        val value = opt(name) ?: return default
        if (value !is Number) return default
        val intValue = value.toInt()
        return if (value.toDouble() == intValue.toDouble()) intValue else default
    }

    private fun hasOversizedString(args: JSONObject): Boolean {
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = args.opt(key)
            if (value is String) {
                val limit = if (key in WRITE_TEXT_ARGUMENTS) MAX_WRITE_CHARS else MAX_ARG_CHARS
                if (value.length > limit) return true
            }
        }
        return false
    }

    private fun personAllowed(personId: String, allowedPersonId: String?): Boolean =
        allowedPersonId == null || personId == allowedPersonId

    /** A persona chat may only touch that person's records and diaries linking to them. */
    private fun documentAllowed(document: Document, allowedPersonId: String?): Boolean {
        if (allowedPersonId == null) return true
        if (document.type == RecordType.PERSONA) return false
        if (document.personId == allowedPersonId) return true
        return document.type == RecordType.DIARY && document.body.contains(personLink(allowedPersonId))
    }

    private fun idOf(document: Document): String = runCatching {
        document.file.relativeTo(rootDir).invariantSeparatorsPath
    }.getOrElse { document.file.name }

    private fun dateOf(document: Document): LocalDate =
        document.timestamp.atZone(zone).toLocalDate()

    private fun parseDate(value: String): LocalDate? =
        runCatching { LocalDate.parse(value.trim()) }.getOrNull()

    private fun errorJson(reason: String): String =
        JSONObject().put("error", reason).toString()

    companion object {
        const val TOOL_LIST_PEOPLE = "list_people"
        const val TOOL_ARCHIVE_OVERVIEW = "archive_overview"
        const val TOOL_READ_PERSON = "read_person"
        const val TOOL_LIST_RECORDS = "list_records"
        const val TOOL_READ_RECORD = "read_record"
        const val TOOL_READ_DIARY = "read_diary"
        const val TOOL_DIARY_DATES = "list_diary_dates"
        const val TOOL_READ_MOODS = "read_moods"
        const val TOOL_SEARCH = "search_archive"
        const val TOOL_LIST_ATTACHMENTS = "list_attachments"
        const val TOOL_READ_ATTACHMENT_TEXT = "read_attachment_text"
        const val TOOL_SAVE_DIARY = "save_diary"
        const val TOOL_APPEND_DIARY = "append_diary"
        const val TOOL_SET_MOOD = "set_mood"
        const val TOOL_CREATE_PERSON = "create_person"
        const val TOOL_UPDATE_PERSON = "update_person"
        const val TOOL_DELETE_RECORD = "delete_record"

        private const val MAX_PEOPLE = 100
        private const val MAX_RECORDS = 200
        private const val MAX_BODY_CHARS = 4000
        private const val MAX_SNIPPET_CHARS = 400
        private const val MAX_SEARCH_HITS = 8
        private const val PREFETCH_SNIPPET_CHARS = 320
        private const val MAX_WRITE_CHARS = 20_000
        private const val MAX_TOOL_ARGUMENT_CHARS = MAX_WRITE_CHARS + 2_000
        private const val MAX_NOTE_CHARS = 200
        private const val MAX_ARG_CHARS = 200
        private val WRITE_TEXT_ARGUMENTS = setOf("body", "text", "notes")
        private const val MAX_ATTACHMENTS = 100
        private const val MAX_ATTACHMENT_NAME_CHARS = 200
        private const val MAX_ATTACHMENT_TEXT_CHARS = 4000

        /** Same opaque shape the attachment store writes (32 lowercase hex). */
        private val ATTACHMENT_ID_PATTERN = Regex("^[a-f0-9]{32}$")

        /** Tools that need an [AttachmentStore]; hidden when none was wired. */
        private val ATTACHMENT_TOOLS = setOf(TOOL_LIST_ATTACHMENTS, TOOL_READ_ATTACHMENT_TEXT)
        private val APPROVAL_REQUIRED_TOOLS = setOf(TOOL_SAVE_DIARY, TOOL_UPDATE_PERSON, TOOL_DELETE_RECORD)
        private val PERSON_SCOPE_DENIED_TOOLS = setOf(
            TOOL_READ_MOODS,
            TOOL_SAVE_DIARY,
            TOOL_APPEND_DIARY,
            TOOL_SET_MOOD,
            TOOL_CREATE_PERSON,
            TOOL_DELETE_RECORD,
        )

        /**
         * The single classification table. A tool that is missing here can never
         * be listed or executed, so adding a tool means making its capability
         * explicit in this map.
         */
        private val TOOL_CAPABILITIES: Map<String, AiToolCapability> = mapOf(
            TOOL_LIST_PEOPLE to AiToolCapability.READ_ARCHIVE,
            TOOL_ARCHIVE_OVERVIEW to AiToolCapability.READ_ARCHIVE,
            TOOL_READ_PERSON to AiToolCapability.READ_ARCHIVE,
            TOOL_LIST_RECORDS to AiToolCapability.READ_ARCHIVE,
            TOOL_READ_RECORD to AiToolCapability.READ_ARCHIVE,
            TOOL_READ_DIARY to AiToolCapability.READ_ARCHIVE,
            TOOL_DIARY_DATES to AiToolCapability.READ_ARCHIVE,
            TOOL_READ_MOODS to AiToolCapability.READ_ARCHIVE,
            TOOL_SEARCH to AiToolCapability.READ_ARCHIVE,
            TOOL_LIST_ATTACHMENTS to AiToolCapability.READ_ARCHIVE,
            TOOL_READ_ATTACHMENT_TEXT to AiToolCapability.READ_ARCHIVE,
            TOOL_SAVE_DIARY to AiToolCapability.WRITE_DIARY,
            TOOL_APPEND_DIARY to AiToolCapability.WRITE_DIARY,
            TOOL_SET_MOOD to AiToolCapability.WRITE_DIARY,
            TOOL_CREATE_PERSON to AiToolCapability.WRITE_PEOPLE,
            TOOL_UPDATE_PERSON to AiToolCapability.WRITE_PEOPLE,
            TOOL_DELETE_RECORD to AiToolCapability.WRITE_DIARY,
        )

        private val READ_SPECS = listOf(
            AiToolSpec(
                name = TOOL_LIST_PEOPLE,
                description = "列出档案里的人物（person_id、称呼、关系）。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_PERSON,
                description = "读取一个人物的档案：称呼、关系、备注。",
                parametersJson = """{"type":"object","properties":{"person_id":{"type":"string"}},"required":["person_id"]}""",
            ),
            AiToolSpec(
                name = TOOL_LIST_RECORDS,
                description = "按时间倒序列出记录。可按人物或日期范围过滤（日期为 YYYY-MM-DD）；都不填则列出最近的记录。",
                parametersJson = """{"type":"object","properties":{"person_id":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"}},"required":[]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_RECORD,
                description = "按 record_id 读取一条记录的正文。",
                parametersJson = """{"type":"object","properties":{"record_id":{"type":"string"}},"required":["record_id"]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_DIARY,
                description = "读取某一天的日记原文（日期为 YYYY-MM-DD）。没有这一天时会返回 found=false 和最近的日记日期。",
                parametersJson = """{"type":"object","properties":{"date":{"type":"string"}},"required":["date"]}""",
            ),
            AiToolSpec(
                name = TOOL_DIARY_DATES,
                description = "列出有日记的日期，按时间倒序。用于回答「我什么时候写过日记」「最近写了什么」。",
                parametersJson = """{"type":"object","properties":{"limit":{"type":"integer"}},"required":[]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_MOODS,
                description = "读取一段时间的每日心情：可用 from/to（YYYY-MM-DD），或用 days 表示最近 N 天。",
                parametersJson = """{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"},"days":{"type":"integer"}},"required":[]}""",
            ),
            AiToolSpec(
                name = TOOL_ARCHIVE_OVERVIEW,
                description = "统计档案规模：记录总数、日记数量与最早/最晚日期、人物数量。不返回任何正文。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            ),
            AiToolSpec(
                name = TOOL_SEARCH,
                description = "在自己的档案里检索关键词，返回最相关的原文片段。",
                parametersJson = """{"type":"object","properties":{"query":{"type":"string"},"person_id":{"type":"string"}},"required":["query"]}""",
            ),
        )

        private val ATTACHMENT_SPECS = listOf(
            AiToolSpec(
                name = TOOL_LIST_ATTACHMENTS,
                description = "列出导入的附件（文件名、类型、大小、日期、归属人物）。只返回元数据，不返回文件内容。",
                parametersJson = """{"type":"object","properties":{"person_id":{"type":"string"}},"required":[]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_ATTACHMENT_TEXT,
                description = "读取某个附件在导入时抽取出的文本（最多 4000 字）；没有可抽取文本时返回错误。",
                parametersJson = """{"type":"object","properties":{"attachment_id":{"type":"string"}},"required":["attachment_id"]}""",
            ),
        )

        private val WRITE_SPECS = listOf(
            AiToolSpec(
                name = TOOL_SAVE_DIARY,
                description = "写入或覆盖某一天的日记（日期 YYYY-MM-DD）。会把原文替换为你给出的正文。",
                parametersJson = """{"type":"object","properties":{"date":{"type":"string"},"body":{"type":"string"}},"required":["date","body"]}""",
            ),
            AiToolSpec(
                name = TOOL_APPEND_DIARY,
                description = "在某一天的日记末尾追加一段文字；当天没有日记时会新建。",
                parametersJson = """{"type":"object","properties":{"date":{"type":"string"},"text":{"type":"string"}},"required":["date","text"]}""",
            ),
            AiToolSpec(
                name = TOOL_SET_MOOD,
                description = "设置某一天的心情，不需要额外确认。可选值：happy/calm/excited/tired/sad/angry/emm。",
                parametersJson = """{"type":"object","properties":{"date":{"type":"string"},"mood":{"type":"string","enum":["happy","calm","excited","tired","sad","angry","emm"]}},"required":["date","mood"]}""",
            ),
            AiToolSpec(
                name = TOOL_CREATE_PERSON,
                description = "新建一个人物档案：称呼必填，关系（family/friend/former_friend）可选；note 是一句话备注，notes 是正文。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string"},"relation":{"type":"string"},"note":{"type":"string"},"notes":{"type":"string"}},"required":["name"]}""",
            ),
            AiToolSpec(
                name = TOOL_UPDATE_PERSON,
                description = "更新一个人物档案：只改你给出的字段（note 备注、notes 正文），其余保持不变。",
                parametersJson = """{"type":"object","properties":{"person_id":{"type":"string"},"name":{"type":"string"},"relation":{"type":"string"},"note":{"type":"string"},"notes":{"type":"string"}},"required":["person_id"]}""",
            ),
            AiToolSpec(
                name = TOOL_DELETE_RECORD,
                description = "按明确日期删除一条日记或心情记录。只能在全档案对话中调用；删除前应用会要求用户确认。",
                parametersJson = """{"type":"object","properties":{"record_type":{"type":"string","enum":["diary","mood"]},"date":{"type":"string","description":"YYYY-MM-DD"}},"required":["record_type","date"]}""",
            ),
        )

        private val ALL_SPECS = READ_SPECS + ATTACHMENT_SPECS + WRITE_SPECS
    }
}
