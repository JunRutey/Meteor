package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.ArchiveDeleteResult
import com.meteor.memory.core.storage.ArchiveRecordDeleter
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.PersonDeleteResult
import com.meteor.memory.core.storage.PersonEntry
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.core.storage.StoredAttachment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate

class ArchiveToolsTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "meteor-tools-test")

    private val attachment = StoredAttachment(
        id = "0123456789abcdef0123456789abcdef",
        fileName = "notes.txt",
        mimeType = "text/plain",
        personId = "p1",
        sizeBytes = 11,
        modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private class FakeAttachments(
        private val items: List<StoredAttachment>,
        private val text: String?,
    ) : AttachmentStore {
        override fun save(
            input: InputStream,
            fileName: String,
            mimeType: String,
            personId: String?,
            indexedText: String?,
        ): StoredAttachment = error("not used")

        override fun list(personId: String?): List<StoredAttachment> =
            items.filter { personId == null || it.personId == personId }

        override fun readIndexedText(id: String): String? = text

        override fun copyDecrypted(id: String, output: OutputStream): Long = error("not used")

        override fun delete(id: String): Boolean = error("not used")
    }

    private class RecordingDiaries : DiaryQueries {
        var saved: Pair<LocalDate, String>? = null

        override fun dates(): List<LocalDate> = emptyList()

        override fun read(date: LocalDate): Document? = null

        override fun save(date: LocalDate, body: String) {
            saved = date to body
        }
    }

    private class RecordingDeleter : ArchiveRecordDeleter {
        var deletedDiary: LocalDate? = null
        var deletedMood: LocalDate? = null

        override fun deleteDiary(date: LocalDate): ArchiveDeleteResult {
            deletedDiary = date
            return ArchiveDeleteResult.DELETED
        }

        override fun deleteMood(date: LocalDate): ArchiveDeleteResult {
            deletedMood = date
            return ArchiveDeleteResult.DELETED
        }
    }

    private object NoPeople : PersonQueries {
        override fun list(): List<PersonEntry> = emptyList()

        override fun read(personId: String): PersonProfile? = null

        override fun create(
            displayName: String,
            relation: Relation?,
            note: String,
            notes: String,
            tags: List<String>,
        ): String = "p-new"

        override fun update(
            personId: String,
            displayName: String,
            relation: Relation?,
            note: String,
            notes: String,
            tags: List<String>,
        ) = Unit

        override fun delete(personId: String): PersonDeleteResult = PersonDeleteResult.NOT_FOUND
    }

    private class ExistingPerson : PersonQueries {
        var updatedPersonId: String? = null
        var updatedTags: List<String>? = null
        var deletedPersonId: String? = null

        override fun list(): List<PersonEntry> = emptyList()

        override fun read(personId: String): PersonProfile? =
            if (personId == "p1") {
                PersonProfile("Existing", Relation.FRIEND, tags = listOf("同事"))
            } else {
                null
            }

        override fun create(
            displayName: String,
            relation: Relation?,
            note: String,
            notes: String,
            tags: List<String>,
        ): String = error("not used")

        override fun update(
            personId: String,
            displayName: String,
            relation: Relation?,
            note: String,
            notes: String,
            tags: List<String>,
        ) {
            updatedPersonId = personId
            updatedTags = tags
        }

        override fun delete(personId: String): PersonDeleteResult {
            deletedPersonId = personId
            return PersonDeleteResult.DELETED
        }
    }

    private object NoMoods : MoodQueries {
        override fun range(from: LocalDate, to: LocalDate): Map<LocalDate, Mood> = emptyMap()

        override fun read(date: LocalDate): Mood? = null

        override fun save(date: LocalDate, mood: Mood) = Unit
    }

    private class RecordingMoods : MoodQueries {
        var saved: Pair<LocalDate, Mood>? = null

        override fun range(from: LocalDate, to: LocalDate): Map<LocalDate, Mood> = emptyMap()

        override fun read(date: LocalDate): Mood? = null

        override fun save(date: LocalDate, mood: Mood) {
            saved = date to mood
        }
    }

    private fun tools(
        attachments: AttachmentStore? = null,
        permissions: AiToolPermissions = AiToolPermissions.READ_ONLY,
        diaries: DiaryQueries = RecordingDiaries(),
        moods: MoodQueries = NoMoods,
        people: PersonQueries = NoPeople,
        deleter: ArchiveRecordDeleter? = null,
    ): ArchiveTools = ArchiveTools(
        rootDir = root,
        scanner = RecordScanner { emptyList() },
        personQueries = people,
        moodQueries = moods,
        diaryQueries = diaries,
        attachments = attachments,
        permissions = permissions,
        archiveRecordDeleter = deleter,
    )

    private fun errorOf(json: String): String = JSONObject(json).optString("error")

    @Test
    fun `read tools are listed, write tools are not`() {
        val names = tools().specs().map { it.name }
        assertTrue(names.toString(), names.contains(ArchiveTools.TOOL_READ_DIARY))
        assertTrue(names.toString(), names.contains(ArchiveTools.TOOL_ARCHIVE_OVERVIEW))
        assertFalse(names.toString(), names.contains(ArchiveTools.TOOL_SAVE_DIARY))
        assertFalse(names.toString(), names.contains(ArchiveTools.TOOL_CREATE_PERSON))
    }

    @Test
    fun `each write grant only opens its own tools`() {
        val diaryOnly = tools(permissions = AiToolPermissions(writeDiary = true)).specs().map { it.name }
        assertTrue(diaryOnly.toString(), diaryOnly.contains(ArchiveTools.TOOL_SAVE_DIARY))
        assertFalse(diaryOnly.toString(), diaryOnly.contains(ArchiveTools.TOOL_CREATE_PERSON))
        assertFalse(diaryOnly.toString(), diaryOnly.contains(ArchiveTools.TOOL_DELETE_RECORD))

        val peopleOnly = tools(permissions = AiToolPermissions(writePeople = true)).specs().map { it.name }
        assertTrue(peopleOnly.toString(), peopleOnly.contains(ArchiveTools.TOOL_CREATE_PERSON))
        assertTrue(peopleOnly.toString(), peopleOnly.contains(ArchiveTools.TOOL_DELETE_PERSON))
        assertFalse(peopleOnly.toString(), peopleOnly.contains(ArchiveTools.TOOL_SAVE_DIARY))
    }

    @Test
    fun `mood tool accepts the seventh mood without approval`() {
        val moods = RecordingMoods()
        val archiveTools = tools(
            permissions = AiToolPermissions(writeDiary = true),
            moods = moods,
        )
        val spec = archiveTools.specs().single { it.name == ArchiveTools.TOOL_SET_MOOD }
        val moodValues = JSONObject(spec.parametersJson)
            .getJSONObject("properties")
            .getJSONObject("mood")
            .getJSONArray("enum")
            .let { values -> (0 until values.length()).map(values::getString) }
        val date = LocalDate.parse("2026-03-08")

        val result = archiveTools.execute(
            ArchiveTools.TOOL_SET_MOOD,
            """{"date":"$date","mood":"emm"}""",
            allowedPersonId = null,
        )

        assertEquals(listOf("happy", "calm", "excited", "tired", "sad", "angry", "emm"), moodValues)
        assertFalse(archiveTools.requiresApproval(ArchiveTools.TOOL_SET_MOOD))
        assertTrue(JSONObject(result).optBoolean("saved"))
        assertEquals(date to Mood.EMM, moods.saved)
    }

    @Test
    fun `mutating tools are marked as requiring approval`() {
        val archiveTools = tools(permissions = AiToolPermissions(writeDiary = true, writePeople = true))

        assertTrue(archiveTools.requiresApproval(ArchiveTools.TOOL_SAVE_DIARY))
        assertTrue(archiveTools.requiresApproval(ArchiveTools.TOOL_UPDATE_PERSON))
        assertTrue(archiveTools.requiresApproval(ArchiveTools.TOOL_DELETE_PERSON))
        assertTrue(archiveTools.requiresApproval(ArchiveTools.TOOL_DELETE_RECORD))
        assertFalse(archiveTools.requiresApproval(ArchiveTools.TOOL_APPEND_DIARY))
        assertFalse(archiveTools.requiresApproval(ArchiveTools.TOOL_SET_MOOD))
        assertFalse(archiveTools.requiresApproval(ArchiveTools.TOOL_CREATE_PERSON))
        assertFalse(archiveTools.requiresApproval(ArchiveTools.TOOL_READ_DIARY))
    }

    @Test
    fun `diary and mood deletion are limited to explicit dates and types`() {
        val deleter = RecordingDeleter()
        val archiveTools = tools(
            permissions = AiToolPermissions(writeDiary = true),
            deleter = deleter,
        )
        val date = LocalDate.parse("2026-03-08")
        val diaryResult = archiveTools.execute(
            ArchiveTools.TOOL_DELETE_RECORD,
            """{"record_type":"diary","date":"$date"}""",
            allowedPersonId = null,
        )
        val moodResult = archiveTools.execute(
            ArchiveTools.TOOL_DELETE_RECORD,
            """{"record_type":"mood","date":"$date"}""",
            allowedPersonId = null,
        )
        val invalidType = archiveTools.execute(
            ArchiveTools.TOOL_DELETE_RECORD,
            """{"record_type":"profile","date":"$date"}""",
            allowedPersonId = null,
        )

        assertTrue(JSONObject(diaryResult).optBoolean("deleted"))
        assertTrue(JSONObject(moodResult).optBoolean("deleted"))
        assertEquals("bad-record-type", errorOf(invalidType))
        assertEquals(date, deleter.deletedDiary)
        assertEquals(date, deleter.deletedMood)
        assertTrue(archiveTools.specs().any { it.name == ArchiveTools.TOOL_DELETE_RECORD })
    }

    @Test
    fun `an ungranted write tool is refused before it touches storage`() {
        val diaries = RecordingDiaries()
        val result = tools(diaries = diaries).execute(
            ArchiveTools.TOOL_SAVE_DIARY,
            """{"date":"2026-01-01","body":"hello"}""",
            allowedPersonId = null,
        )

        assertEquals("permission-denied", errorOf(result))
        assertNull(diaries.saved)
    }

    @Test
    fun `a granted write tool runs`() {
        val diaries = RecordingDiaries()
        val result = tools(
            permissions = AiToolPermissions(writeDiary = true),
            diaries = diaries,
        ).execute(
            ArchiveTools.TOOL_SAVE_DIARY,
            """{"date":"2026-01-01","body":"hello"}""",
            allowedPersonId = null,
        )

        assertTrue(result, JSONObject(result).optBoolean("saved"))
        assertEquals(LocalDate.parse("2026-01-01") to "hello", diaries.saved)
    }

    @Test
    fun `a non-string argument is treated as absent`() {
        val result = tools().execute(
            ArchiveTools.TOOL_READ_PERSON,
            """{"person_id":123}""",
            allowedPersonId = null,
        )

        assertEquals("missing-person-id", errorOf(result))
    }

    @Test
    fun `malformed json and oversized arguments are refused`() {
        assertEquals(
            "bad-arguments",
            errorOf(tools().execute(ArchiveTools.TOOL_READ_PERSON, "{", allowedPersonId = null)),
        )

        val result = tools(permissions = AiToolPermissions(writeDiary = true)).execute(
            ArchiveTools.TOOL_SAVE_DIARY,
            """{"date":"2026-01-01","body":"${"x".repeat(20_001)}"}""",
            allowedPersonId = null,
        )
        assertEquals("argument-too-long", errorOf(result))
    }

    @Test
    fun `persona scope refuses global writes and mood reads`() {
        val diaries = RecordingDiaries()
        val deleter = RecordingDeleter()
        val scoped = tools(
            permissions = AiToolPermissions(writeDiary = true),
            diaries = diaries,
            deleter = deleter,
        )

        assertEquals(
            "not-allowed",
            errorOf(
                scoped.execute(
                    ArchiveTools.TOOL_SAVE_DIARY,
                    """{"date":"2026-01-01","body":"hello"}""",
                    allowedPersonId = "p1",
                ),
            ),
        )
        assertEquals(
            "not-allowed",
            errorOf(scoped.execute(ArchiveTools.TOOL_READ_MOODS, "{}", allowedPersonId = "p1")),
        )
        assertEquals(
            "not-allowed",
            errorOf(
                scoped.execute(
                    ArchiveTools.TOOL_DELETE_RECORD,
                    """{"record_type":"diary","date":"2026-01-01"}""",
                    allowedPersonId = "p1",
                ),
            ),
        )
        assertEquals(
            "not-allowed",
            errorOf(
                tools(permissions = AiToolPermissions(writePeople = true)).execute(
                    ArchiveTools.TOOL_DELETE_PERSON,
                    """{"person_id":"p1"}""",
                    allowedPersonId = "p1",
                ),
            ),
        )
        assertNull(diaries.saved)
        assertNull(deleter.deletedDiary)
    }

    @Test
    fun `persona scope can update only its own person`() {
        val people = ExistingPerson()
        val scoped = tools(permissions = AiToolPermissions(writePeople = true), people = people)
        val samePerson = scoped.execute(
            ArchiveTools.TOOL_UPDATE_PERSON,
            """{"person_id":"p1","name":"Updated"}""",
            allowedPersonId = "p1",
        )
        assertTrue(JSONObject(samePerson).optBoolean("updated"))
        assertEquals("p1", people.updatedPersonId)

        val otherPerson = scoped.execute(
            ArchiveTools.TOOL_UPDATE_PERSON,
            """{"person_id":"p2","name":"Updated"}""",
            allowedPersonId = "p1",
        )
        assertEquals("not-allowed", errorOf(otherPerson))
        assertEquals("p1", people.updatedPersonId)
    }

    @Test
    fun `delete person only runs with the people grant`() {
        val people = ExistingPerson()
        val ungranted = tools(people = people).execute(
            ArchiveTools.TOOL_DELETE_PERSON,
            """{"person_id":"p1"}""",
            allowedPersonId = null,
        )
        assertEquals("permission-denied", errorOf(ungranted))
        assertNull(people.deletedPersonId)

        val granted = tools(
            permissions = AiToolPermissions(writePeople = true),
            people = people,
        ).execute(
            ArchiveTools.TOOL_DELETE_PERSON,
            """{"person_id":"p1"}""",
            allowedPersonId = null,
        )
        assertTrue(granted, JSONObject(granted).optBoolean("deleted"))
        assertEquals("p1", people.deletedPersonId)
    }

    @Test
    fun `update person keeps tags when omitted and replaces them when provided`() {
        val people = ExistingPerson()
        val scoped = tools(permissions = AiToolPermissions(writePeople = true), people = people)

        scoped.execute(
            ArchiveTools.TOOL_UPDATE_PERSON,
            """{"person_id":"p1","name":"Updated"}""",
            allowedPersonId = null,
        )
        assertEquals(listOf("同事"), people.updatedTags)

        scoped.execute(
            ArchiveTools.TOOL_UPDATE_PERSON,
            """{"person_id":"p1","tags":["大学同学","大学同学"]}""",
            allowedPersonId = null,
        )
        assertEquals(listOf("大学同学"), people.updatedTags)
    }

    @Test
    fun `read person exposes custom tags`() {
        val result = tools(people = ExistingPerson()).execute(
            ArchiveTools.TOOL_READ_PERSON,
            """{"person_id":"p1"}""",
            allowedPersonId = null,
        )

        val array = JSONObject(result).getJSONArray("tags")
        val tags = (0 until array.length()).map(array::getString)
        assertEquals(listOf("同事"), tags)
    }

    @Test
    fun `unknown tools are refused`() {
        assertEquals(
            "unknown-tool",
            errorOf(tools().execute("delete_everything", "{}", allowedPersonId = null)),
        )
    }

    @Test
    fun `attachment tools need a wired store`() {
        val names = tools().specs().map { it.name }
        assertFalse(names.toString(), names.contains(ArchiveTools.TOOL_LIST_ATTACHMENTS))
        assertEquals(
            "tool-unavailable",
            errorOf(tools().execute(ArchiveTools.TOOL_LIST_ATTACHMENTS, "{}", allowedPersonId = null)),
        )
    }

    @Test
    fun `attachment metadata lists only the scoped owner`() {
        val store = FakeAttachments(listOf(attachment), text = "hello")
        val scoped = tools(attachments = store).execute(
            ArchiveTools.TOOL_LIST_ATTACHMENTS,
            "{}",
            allowedPersonId = "p2",
        )
        assertEquals("[]", scoped)

        val full = tools(attachments = store).execute(
            ArchiveTools.TOOL_LIST_ATTACHMENTS,
            "{}",
            allowedPersonId = null,
        )
        assertEquals(1, JSONArray(full).length())
    }

    @Test
    fun `attachment text is refused outside the allowed person`() {
        val store = FakeAttachments(listOf(attachment), text = "hello")
        val result = tools(attachments = store).execute(
            ArchiveTools.TOOL_READ_ATTACHMENT_TEXT,
            """{"attachment_id":"${attachment.id}"}""",
            allowedPersonId = "p2",
        )

        assertEquals("not-allowed", errorOf(result))
    }

    @Test
    fun `attachment text is returned when the attachment is visible`() {
        val store = FakeAttachments(listOf(attachment), text = "hello")
        val result = tools(attachments = store).execute(
            ArchiveTools.TOOL_READ_ATTACHMENT_TEXT,
            """{"attachment_id":"${attachment.id}"}""",
            allowedPersonId = null,
        )

        assertEquals("hello", JSONObject(result).getString("text"))
    }

    @Test
    fun `a malformed attachment id is refused without touching the store`() {
        val result = tools(attachments = FakeAttachments(listOf(attachment), text = "hello")).execute(
            ArchiveTools.TOOL_READ_ATTACHMENT_TEXT,
            """{"attachment_id":"../../etc/passwd"}""",
            allowedPersonId = null,
        )

        assertEquals("bad-attachment-id", errorOf(result))
    }
}
