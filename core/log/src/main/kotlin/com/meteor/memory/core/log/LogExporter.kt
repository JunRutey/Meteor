package com.meteor.memory.core.log

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes the collected logs into the device's shared Downloads folder.
 *
 * API 29+ goes through MediaStore, which needs no permission at all. Older
 * releases would need `WRITE_EXTERNAL_STORAGE`, so there we fall back to the
 * app-specific external directory and report the real path instead of failing.
 */
class LogExporter(
    private val context: Context,
    private val clock: () -> Instant = Instant::now,
) {

    data class Result(
        val success: Boolean,
        val location: String?,
        val error: String?,
    )

    fun export(): Result = runCatching {
        val payload = buildPayload()
        val fileName = "meteor-log-${FILE_STAMP.format(clock().atZone(ZoneId.systemDefault()))}.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToDownloadsViaMediaStore(fileName, payload)
        } else {
            writeToLegacyDownloads(fileName, payload)
        }
    }.getOrElse { throwable ->
        Result(success = false, location = null, error = throwable.message ?: throwable::class.java.simpleName)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloadsViaMediaStore(fileName: String, payload: String): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Result(false, null, "无法在下载目录创建文件")

        resolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            ?: return Result(false, null, "无法写入下载目录")

        return Result(true, "Download/$FOLDER/$fileName", null)
    }

    @Suppress("DEPRECATION")
    private fun writeToLegacyDownloads(fileName: String, payload: String): Result {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER,
            )
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(payload, Charsets.UTF_8)
            Result(true, "Download/$FOLDER/$fileName", null)
        } catch (security: SecurityException) {
            // No permission on API < 29: keep the log reachable via USB instead of losing it.
            val fallbackDir = File(context.getExternalFilesDir(null), "logs-export")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val target = File(fallbackDir, fileName)
            target.writeText(payload, Charsets.UTF_8)
            Result(true, target.absolutePath, null)
        }
    }

    private fun buildPayload(): String {
        val header = buildString {
            appendLine("Meteor diagnostic log")
            appendLine("exported: ${clock()}")
            appendLine("app: ${appVersion()}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("---")
        }

        val logDir = File(context.filesDir, "logs")
        val files = (logDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(LogFiles.EXTENSION) }
            .sortedBy { it.name }

        if (files.isEmpty()) return header + "（还没有任何日志）\n"

        val body = buildString {
            files.forEach { file ->
                appendLine("## ${file.name}")
                appendLine(file.readText(Charsets.UTF_8).takeLast(MAX_CHARS_PER_FILE))
            }
        }

        // Keep the tail: the newest lines are the ones that explain a failure.
        return header + body.takeLast(MAX_TOTAL_CHARS)
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("unknown")

    private companion object {
        const val FOLDER = "Meteor"
        const val MAX_CHARS_PER_FILE = 200_000
        const val MAX_TOTAL_CHARS = 500_000
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
