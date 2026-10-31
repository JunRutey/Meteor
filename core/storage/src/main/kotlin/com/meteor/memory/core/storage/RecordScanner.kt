package com.meteor.memory.core.storage

import java.io.File

/**
 * Enumerates every stored record as a decrypted [Document].
 *
 * Additive companion to the frozen `StorageRouter`: routing alone cannot walk
 * the tree, and `:core-index` needs the same decryption path so it never has to
 * know about keys (archive R6).
 */
fun interface RecordScanner {
    fun scan(): List<Document>
}

class PrivateRecordScanner(
    private val rootDir: File,
    private val router: StorageRouter,
) : RecordScanner {

    override fun scan(): List<Document> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            .mapNotNull { file -> runCatching { router.read(file) }.getOrNull() }
            .toList()
    }
}
