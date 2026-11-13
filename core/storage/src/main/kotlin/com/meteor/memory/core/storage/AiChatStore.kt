package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Base64

/** One persisted AI chat turn. Content is plaintext only in memory. */
data class StoredChatTurn(val fromUser: Boolean, val text: String, val at: Instant)

data class StoredChatFile(
    val id: String,
    val fileName: String,
    val purpose: String,
    /** Upload time; epoch means the record predates file timestamps. */
    val uploadedAt: Instant = Instant.EPOCH,
)

data class StoredChatCitation(
    val url: String,
    val title: String,
    val startIndex: Int,
    val endIndex: Int,
)

data class StoredChatMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val at: Instant,
    val files: List<StoredChatFile> = emptyList(),
    val serverToolCallsJson: String? = null,
    val citations: List<StoredChatCitation> = emptyList(),
    val isTruncated: Boolean = false,
)

data class StoredAiConversation(
    val id: String,
    val scope: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messages: List<StoredChatMessage>,
)

/**
 * Encrypted storage for the in-app AI conversation.
 *
 * Kept out of the record scanner on purpose (`.bin`, not `.md`): the model's own
 * answers must never become "archive evidence" and pollute later retrieval.
 */
interface AiChatStore {
    fun load(scope: String, limit: Int = 60): List<StoredChatTurn>

    fun save(scope: String, turns: List<StoredChatTurn>)

    fun clear(scope: String)

    fun listConversations(scope: String? = null): List<StoredAiConversation>

    fun saveConversation(conversation: StoredAiConversation)

    fun deleteConversation(id: String)

    companion object {
        const val MAX_TURNS = 60
    }
}

/**
 * File-per-scope store encrypted with the same Keystore AES-256-GCM key as the
 * archive ([FileCrypto]); nothing is ever written in plaintext.
 *
 * Legacy wire format (line based, tolerant on read):
 * `v1 \t u|a \t epochMillis \t Base64(UTF-8 text)`.
 * New conversations use encrypted JSON v2 files in the `sessions` subfolder.
 */
class EncryptedAiChatStore(
    rootDir: File,
    private val crypto: FileCrypto,
) : AiChatStore {

    private val dir = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}")
    private val sessionsDir = File(dir, "sessions")

    override fun load(scope: String, limit: Int): List<StoredChatTurn> = runCatching {
        val file = fileFor(scope)
        if (!file.isFile) return emptyList()
        val plaintext = String(crypto.decrypt(file.readBytes()), Charsets.UTF_8)
        plaintext.lineSequence()
            .mapNotNull { decode(it) }
            .toList()
            .takeLast(limit.coerceIn(1, AiChatStore.MAX_TURNS))
    }.getOrDefault(emptyList())

    override fun save(scope: String, turns: List<StoredChatTurn>) {
        val file = fileFor(scope)
        val kept = turns.takeLast(AiChatStore.MAX_TURNS)
        if (kept.isEmpty()) {
            file.delete()
            return
        }
        dir.mkdirs()
        val payload = kept.joinToString("\n") { encode(it) }
        file.writeBytes(crypto.encrypt(payload.toByteArray(Charsets.UTF_8)))
    }

    override fun clear(scope: String) {
        fileFor(scope).delete()
        listConversations(scope).forEach { deleteConversation(it.id) }
    }

    override fun listConversations(scope: String?): List<StoredAiConversation> {
        migrateLegacy(scope)
        if (!sessionsDir.isDirectory) return emptyList()
        return sessionsDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "bin" }
            // One unreadable session (old key, interrupted write, newer format)
            // must not hide every other conversation. Keep the file in place so
            // a later key recovery or manual repair can still read it.
            .mapNotNull { file ->
                try {
                    readConversation(file)
                } catch (_: Exception) {
                    null
                }
            }
            .filter { scope == null || it.scope == scope }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    override fun saveConversation(conversation: StoredAiConversation) {
        require(ID_PATTERN.matches(conversation.id)) { "invalid-chat-id" }
        val kept = if (conversation.messages.size > MAX_MESSAGES) {
            conversation.copy(messages = conversation.messages.takeLast(MAX_MESSAGES))
        } else {
            conversation
        }
        val json = encodeConversation(kept)
        if (!sessionsDir.exists() && !sessionsDir.mkdirs()) error("chat-store-unavailable")
        val destination = conversationFile(conversation.id)
        val temporary = File.createTempFile("${conversation.id}-", ".tmp", sessionsDir)
        try {
            temporary.writeBytes(crypto.encrypt(json.toByteArray(Charsets.UTF_8)))
            replaceAtomically(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun deleteConversation(id: String) {
        require(ID_PATTERN.matches(id)) { "invalid-chat-id" }
        val file = conversationFile(id)
        if (file.exists() && !file.delete()) error("chat-store-delete-failed")
    }

    private fun migrateLegacy(scope: String?) {
        if (!dir.isDirectory) return
        val expected = scope?.let { fileFor(it).name }
        dir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "bin" }
            .filter { expected == null || it.name == expected }
            .forEach { oldFile ->
                try {
                    migrateLegacyFile(oldFile)
                } catch (_: Exception) {
                    // Keep an unreadable legacy file untouched: a transient key
                    // problem must not delete data, and one bad file must not
                    // block the rest of the conversation list.
                }
            }
    }

    private fun migrateLegacyFile(oldFile: File) {
        val oldText = String(crypto.decrypt(oldFile.readBytes()), Charsets.UTF_8)
        val turns = oldText.lineSequence().mapNotNull(::decode).toList()
        if (turns.isEmpty()) return
        val id = "legacy_${oldFile.nameWithoutExtension.take(54)}"
        val destination = conversationFile(id)
        if (destination.isFile) {
            if (readConversation(destination).scope != oldFile.nameWithoutExtension) {
                error("chat-store-migration-conflict")
            }
            if (!oldFile.delete()) error("chat-store-migration-cleanup-failed")
            return
        }
        val now = turns.last().at
        val messages = turns.mapIndexed { index, turn ->
            StoredChatMessage(
                id = "${id}_$index",
                fromUser = turn.fromUser,
                text = turn.text,
                at = turn.at,
            )
        }
        saveConversation(
            StoredAiConversation(
                id = id,
                scope = oldFile.nameWithoutExtension,
                title = messages.firstOrNull { it.fromUser }?.text?.take(TITLE_MAX_CHARS).orEmpty(),
                createdAt = turns.first().at,
                updatedAt = now,
                messages = messages,
            ),
        )
        if (!oldFile.delete()) error("chat-store-migration-cleanup-failed")
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

    private fun readConversation(file: File): StoredAiConversation {
        val plaintext = try {
            String(crypto.decrypt(file.readBytes()), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("chat-store-decrypt-failed")
        }
        return try {
            decodeConversation(JSONObject(plaintext))
        } catch (_: Exception) {
            throw IllegalStateException("chat-store-decode-failed")
        }
    }

    private fun encodeConversation(conversation: StoredAiConversation): String = JSONObject().apply {
        put("version", CHAT_FORMAT_VERSION)
        put("id", conversation.id)
        put("scope", conversation.scope)
        put("title", conversation.title)
        put("createdAt", conversation.createdAt.toEpochMilli())
        put("updatedAt", conversation.updatedAt.toEpochMilli())
        put("messages", JSONArray(conversation.messages.map { message ->
            JSONObject().apply {
                put("id", message.id)
                put("fromUser", message.fromUser)
                put("text", message.text)
                put("at", message.at.toEpochMilli())
                put("files", JSONArray(message.files.map { file ->
                    JSONObject()
                        .put("id", file.id)
                        .put("fileName", file.fileName)
                        .put("purpose", file.purpose)
                        .put("uploadedAt", file.uploadedAt.toEpochMilli())
                }))
                message.serverToolCallsJson?.let { put("serverToolCalls", it) }
                put("citations", JSONArray(message.citations.map { citation ->
                    JSONObject()
                        .put("url", citation.url)
                        .put("title", citation.title)
                        .put("startIndex", citation.startIndex)
                        .put("endIndex", citation.endIndex)
                }))
                put("isTruncated", message.isTruncated)
            }
        }))
    }.toString()

    private fun decodeConversation(root: JSONObject): StoredAiConversation {
        require(root.optInt("version") == CHAT_FORMAT_VERSION) { "unknown-chat-version" }
        val messages = root.getJSONArray("messages")
        val decoded = (0 until messages.length()).map { index ->
            val item = messages.getJSONObject(index)
            val files = item.optJSONArray("files")?.let { array ->
                (0 until array.length()).map { fileIndex ->
                    val file = array.getJSONObject(fileIndex)
                    StoredChatFile(
                        id = file.getString("id"),
                        fileName = file.getString("fileName"),
                        purpose = file.getString("purpose"),
                        uploadedAt = Instant.ofEpochMilli(file.optLong("uploadedAt", 0L)),
                    )
                }
            }.orEmpty()
            val citations = item.optJSONArray("citations")?.let { array ->
                (0 until array.length()).map { citationIndex ->
                    val citation = array.getJSONObject(citationIndex)
                    StoredChatCitation(
                        url = citation.getString("url"),
                        title = citation.getString("title"),
                        startIndex = citation.getInt("startIndex"),
                        endIndex = citation.getInt("endIndex"),
                    )
                }
            }.orEmpty()
            StoredChatMessage(
                id = item.getString("id"),
                fromUser = item.getBoolean("fromUser"),
                text = item.getString("text"),
                at = Instant.ofEpochMilli(item.getLong("at")),
                files = files,
                serverToolCallsJson = item.optString("serverToolCalls").takeIf(String::isNotBlank),
                citations = citations,
                isTruncated = item.optBoolean("isTruncated"),
            )
        }
        return StoredAiConversation(
            id = root.getString("id"),
            scope = root.getString("scope"),
            title = root.getString("title"),
            createdAt = Instant.ofEpochMilli(root.getLong("createdAt")),
            updatedAt = Instant.ofEpochMilli(root.getLong("updatedAt")),
            messages = decoded,
        )
    }

    private fun conversationFile(id: String): File = File(sessionsDir, "$id.bin")

    private fun fileFor(scope: String): File {
        val safe = scope.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "default" }
        return File(dir, "$safe.bin")
    }

    private fun encode(turn: StoredChatTurn): String {
        val text = Base64.getEncoder().encodeToString(turn.text.toByteArray(Charsets.UTF_8))
        return "v1\t${if (turn.fromUser) "u" else "a"}\t${turn.at.toEpochMilli()}\t$text"
    }

    private fun decode(line: String): StoredChatTurn? {
        val parts = line.split('\t', limit = 4)
        if (parts.size != 4 || parts[0] != "v1") return null
        val at = parts[2].toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: return null
        val text = runCatching {
            String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return StoredChatTurn(fromUser = parts[1] == "u", text = text, at = at)
    }

    private companion object {
        const val CHAT_FORMAT_VERSION = 2
        const val TITLE_MAX_CHARS = 32
        const val MAX_MESSAGES = AiChatStore.MAX_TURNS * 2
        val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
