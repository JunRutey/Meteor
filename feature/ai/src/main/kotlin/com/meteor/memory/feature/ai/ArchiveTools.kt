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

    override fun specs(): List<AiToolSpec> = SPECS

    /** Always returns a JSON string; failures are JSON too, never exceptions. */
    override fun execute(name: String, arguments: String, allowedPersonId: String?): String = runCatching {
        val args = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        when (name) {
            TOOL_LIST_PEOPLE -> listPeople(allowedPersonId)
            TOOL_READ_PERSON -> readPerson(args.optString("person_id"), allowedPersonId)
            TOOL_LIST_RECORDS -> listRecords(args.optString("person_id"), allowedPersonId)
            TOOL_READ_RECORD -> readRecord(args.optString("record_id"), allowedPersonId)
            TOOL_READ_MOODS -> readMoods(args.optString("from"), args.optString("to"))
            TOOL_SEARCH -> search(
                query = args.optString("query"),
                personId = args.optString("person_id"),
                allowedPersonId = allowedPersonId,
            )

            else -> errorJson("unknown-tool")
        }
    }.getOrElse { errorJson("tool-failed") }

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

    private fun listRecords(personId: String, allowedPersonId: String?): String {
        if (personId.isBlank()) return errorJson("missing-person-id")
        if (!personAllowed(personId, allowedPersonId)) return errorJson("not-allowed")

        val link = personLink(personId)
        val array = JSONArray()
        documents.asSequence()
            .filter { it.type != RecordType.PERSONA }
            .filter { it.personId == personId || (it.type == RecordType.DIARY && it.body.contains(link)) }
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

    private fun readMoods(from: String, to: String): String {
        val start = runCatching { LocalDate.parse(from) }.getOrNull() ?: return errorJson("bad-date")
        val end = runCatching { LocalDate.parse(to) }.getOrNull() ?: return errorJson("bad-date")
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
        val answer = AskEngine.answer(AskRequest(question = query, personId = scoped, maxHits = MAX_SEARCH_HITS), documents)

        val array = JSONArray()
        answer.matches.forEach { match ->
            array.put(
                JSONObject().apply {
                    put("date", match.at.atZone(zone).toLocalDate().toString())
                    put("type", match.type.wire)
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

    private fun errorJson(reason: String): String =
        JSONObject().put("error", reason).toString()

    companion object {
        const val TOOL_LIST_PEOPLE = "list_people"
        const val TOOL_READ_PERSON = "read_person"
        const val TOOL_LIST_RECORDS = "list_records"
        const val TOOL_READ_RECORD = "read_record"
        const val TOOL_READ_MOODS = "read_moods"
        const val TOOL_SEARCH = "search_archive"

        private const val MAX_PEOPLE = 100
        private const val MAX_RECORDS = 200
        private const val MAX_BODY_CHARS = 4000
        private const val MAX_SNIPPET_CHARS = 400
        private const val MAX_SEARCH_HITS = 8

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
                description = "列出与某个人物相关的记录（该人物的记录 + 提到 ta 的日记），按时间倒序。",
                parametersJson = """{"type":"object","properties":{"person_id":{"type":"string"}},"required":["person_id"]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_RECORD,
                description = "按 record_id 读取一条记录的正文。",
                parametersJson = """{"type":"object","properties":{"record_id":{"type":"string"}},"required":["record_id"]}""",
            ),
            AiToolSpec(
                name = TOOL_READ_MOODS,
                description = "读取一段时间内每天的心情（日期为 YYYY-MM-DD）。",
                parametersJson = """{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"]}""",
            ),
            AiToolSpec(
                name = TOOL_SEARCH,
                description = "在自己的档案里检索关键词，返回最相关的原文片段。",
                parametersJson = """{"type":"object","properties":{"query":{"type":"string"},"person_id":{"type":"string"}},"required":["query"]}""",
            ),
        )
    }
}
