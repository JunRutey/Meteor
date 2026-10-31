package com.meteor.memory.core.storage

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Physical layout from the Meteor archive §4.
 *
 * ```
 * 我的人生/
 * ├── 数字遗产/{聊天记录,图片,附件,数字人格}/
 * ├── 记忆档案/{自己,人际关系}/
 * └── 时光轴/心情/{日记/}
 * ```
 *
 * Pure path arithmetic — no IO — so routing rules are unit-testable.
 *
 * NOTE (ADR-014): the frozen `StorageRouter.route(personId, type, date)` has no
 * display-name parameter, so person folders are keyed by `person_id`. The
 * human-readable `display_name` lives inside the ciphertext, which is the only
 * place it can live without leaking the address book to the filesystem.
 */
class StorageLayout(private val root: File) {

    fun rootDir(): File = root

    fun fileFor(personId: String?, type: RecordType, date: LocalDate): File {
        val day = DAY_FORMAT.format(date)
        val year = YEAR_FORMAT.format(date)
        return when (type) {
            RecordType.DIARY -> File(root, "$TIMELINE/$MOOD/$DIARY/$year/$day.md")
            RecordType.MOOD -> File(root, "$TIMELINE/$MOOD/$day.md")
            RecordType.PROFILE -> File(File(root, "$ARCHIVE/${personDir(personId)}"), "profile.md")
            RecordType.CHAT_LOG -> File(root, "$LEGACY/$CHAT/${requirePerson(personId)}/$day.md")
            RecordType.PERSONA -> File(root, "$LEGACY/$PERSONA/${requirePerson(personId)}/persona.md")
        }
    }

    fun imagesDir(personId: String?): File =
        File(root, "$LEGACY/$IMAGES/${requirePerson(personId)}")

    fun legacyRoot(): File = File(root, LEGACY)

    private fun personDir(personId: String?): String =
        if (personId == null) SELF else "$RELATIONS/${requirePerson(personId)}"

    private fun requirePerson(personId: String?): String {
        require(personId != null) { "person_id is required for this record type" }
        // person_id is used as a path segment: refuse anything that could escape.
        require(PERSON_ID_PATTERN.matches(personId)) { "illegal person_id: $personId" }
        return personId
    }

    companion object {
        const val ROOT_NAME = "我的人生"
        const val LEGACY = "数字遗产"
        const val CHAT = "聊天记录"
        const val IMAGES = "图片"
        const val ATTACHMENTS = "附件"
        const val PERSONA = "数字人格"
        /** AI conversation history; encrypted, never scanned as archive records. */
        const val AI_CHAT = "AI对话"
        const val ARCHIVE = "记忆档案"
        const val SELF = "自己"
        const val RELATIONS = "人际关系"
        const val TIMELINE = "时光轴"
        const val MOOD = "心情"
        const val DIARY = "日记"

        const val DAY_PATTERN = "yyyy-MM-dd"

        val PERSON_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        private val DAY_FORMAT = DateTimeFormatter.ofPattern(DAY_PATTERN)
        private val YEAR_FORMAT = DateTimeFormatter.ofPattern("yyyy")
    }
}
