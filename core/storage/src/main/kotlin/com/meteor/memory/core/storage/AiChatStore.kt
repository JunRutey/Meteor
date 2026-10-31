package com.meteor.memory.core.storage

import com.meteor.memory.core.crypto.FileCrypto
import java.io.File
import java.time.Instant
import java.util.Base64

/** One persisted AI chat turn. Content is plaintext only in memory. */
data class StoredChatTurn(val fromUser: Boolean, val text: String, val at: Instant)

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

    companion object {
        const val MAX_TURNS = 60
    }
}

/**
 * File-per-scope store encrypted with the same Keystore AES-256-GCM key as the
 * archive ([FileCrypto]); nothing is ever written in plaintext.
 *
 * Wire format (line based, tolerant on read):
 * `v1 \t u|a \t epochMillis \t Base64(UTF-8 text)`.
 */
class EncryptedAiChatStore(
    rootDir: File,
    private val crypto: FileCrypto,
) : AiChatStore {

    private val dir = File(rootDir, "${StorageLayout.LEGACY}/${StorageLayout.AI_CHAT}")

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
    }

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
}
