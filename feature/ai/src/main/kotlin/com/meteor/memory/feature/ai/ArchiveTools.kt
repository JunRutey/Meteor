package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The tool surface an agent may use. Read-only by construction. */
interface Toolbox {
    fun specs(): List<AiToolSpec>

    fun execute(name: String, arguments: String, allowedPersonId: String?): String
}

/**
 * The **entire** agent capability set: six read-only tools (ADR-024).
 *
 * There is deliberately no write, delete, export, share, network-fetch or shell
 * tool. The only write in the AI domain is the persona artifact, and it happens
 * outside this class after the user explicitly asks for a distillation.
 */
class ArchiveTools(
    private val rootDir: File,
    private val scanner: RecordScanner,
    private val personQueries: PersonQueries,
    private val moodQueries: MoodQueries,
    private val zone: ZoneId = ZoneId.systemDefault(),
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

    override fun specs(): List<AiToolSpec> = SPECS

    /** Always returns a JSON string; failures are JSON too, never exceptions. */
    override fun execute(name: String, arguments: String, allowedPersonId: String?): String = runCatching {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        when (name) {
            TOOL_LIST_PEOPLE -> listPeople(allowedPersonId)
            TOOL_READ_PERSON -> readPerson(args.optString("person_id"), allowedPersonId)
            TOOL_LIST_RECORDS -> listRecords(
                personId = args.optString("person_id"),
                from = args.optString("from"),
                to = args.optString("to"),
                allowedPersonId = allowedPersonId,
            )
            TOOL_READ_RECORD -> readRecord(args.optString("record_id"), allowedPersonId)
            TOOL_READ_DIARY -> readDiary(args.optString("date"), allowedPersonId)
            TOOL_DIARY_DATES -> listDiaryDates(args.optInt("limit", 0), allowedPersonId)
            TOOL_READ_MOODS -> readMoods(
                from = args.optString("from"),
                to = args.optString("to"),
                days = args.optInt("days", 0),
            )
            TOOL_SEARCH -> search(
                query = args.optString("query"),
                personId = args.optString("person_id"),
                allowedPersonId = allowedPersonId,
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

    // --- guards ------------------------------------------------------------

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
        const val TOOL_READ_PERSON = "read_person"
        const val TOOL_LIST_RECORDS = "list_records"
        const val TOOL_READ_RECORD = "read_record"
        const val TOOL_READ_DIARY = "read_diary"
        const val TOOL_DIARY_DATES = "list_diary_dates"
        const val TOOL_READ_MOODS = "read_moods"
        const val TOOL_SEARCH = "search_archive"

        private const val MAX_PEOPLE = 100
        private const val MAX_RECORDS = 200
        private const val MAX_BODY_CHARS = 4000
        private const val MAX_SNIPPET_CHARS = 400
        private const val MAX_SEARCH_HITS = 8
        private const val PREFETCH_SNIPPET_CHARS = 320

        private val SPECS = listOf(
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
                name = TOOL_SEARCH,
                description = "在自己的档案里检索关键词，返回最相关的原文片段。",
                parametersJson = """{"type":"object","properties":{"query":{"type":"string"},"person_id":{"type":"string"}},"required":["query"]}""",
            ),
        )
    }
}
