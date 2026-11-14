package com.meteor.memory.core.log

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the end of one on-disk log file without loading an unbounded file into
 * memory. Exports happen exactly when the log is large, so reading a 32 MB file
 * whole would be the wrong moment to risk an OOM.
 */
object LogFileTail {

    const val MAX_BYTES = 1_000_000
    private const val TRUNCATED = "earlier lines omitted"

    fun read(file: File, maxBytes: Int = MAX_BYTES): String {
        val length = file.length()
        if (length <= 0L) return ""

        val limit = maxBytes.coerceAtLeast(1)
        val start = (length - limit).coerceAtLeast(0L)
        val bytes = ByteArray((length - start).toInt())
        RandomAccessFile(file, "r").use { reader ->
            reader.seek(start)
            reader.readFully(bytes)
        }

        val text = String(bytes, Charsets.UTF_8)
        if (start == 0L) return text

        // The first byte range may begin inside a character or line. Drop that
        // partial line so a markdown heading or timestamp is never cut in half.
        return TRUNCATED + "\n" + text.substringAfter('\n', text)
    }
}
