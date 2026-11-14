package com.meteor.memory.core.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** Why an export failed; the UI owns the localized wording. */
enum class LogExportError {
    OPEN_FAILED,
    WRITE_FAILED,
    READ_FAILED,
}

/**
 * Bundles the collected logs into one text document and writes it to the
 * destination the user picked in the system file picker.
 *
 * The picker (SAF) is the only writer path: the app touches no shared storage
 * directly and needs no storage permission on any API level. The caller is
 * responsible for launching `ActivityResultContracts.CreateDocument` and
 * passing the returned `content://` URI.
 */
class LogExporter(
    private val context: Context,
    private val appLog: AppLog,
    private val clock: () -> Instant = Instant::now,
) {

    data class Result(
        val success: Boolean,
        val displayName: String?,
        val error: LogExportError?,
    )

    /** File name offered to the system picker; the user may rename it there. */
    fun suggestedFileName(): String =
        "meteor-log-${FILE_STAMP.format(clock().atZone(ZoneId.systemDefault()))}.txt"

    fun export(destination: Uri): Result {
        val payload = runCatching { buildPayload() }
            .getOrElse { return Result(false, null, LogExportError.READ_FAILED) }

        val stream = openDestination(destination)
            ?: return Result(false, null, LogExportError.OPEN_FAILED)

        return runCatching {
            stream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            Result(true, displayName(destination), null)
        }.getOrElse { Result(false, null, LogExportError.WRITE_FAILED) }
    }

    /**
     * Some document providers reject the truncating `wt` mode even though they
     * can still open a normal write stream. Never silently append to an older
     * export, though: `w` is the fallback, not the first choice.
     */
    private fun openDestination(uri: Uri): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()

    private fun buildPayload(): String {
        val logDir = File(context.filesDir, LOG_DIR)
        val files = (logDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(LogFiles.EXTENSION) }
            .sortedBy { it.name }
            .map { file ->
                LogFileContent(
                    name = file.name,
                    text = runCatching { LogFileTail.read(file) }
                        .getOrElse { "<unreadable: ${it::class.java.simpleName}>" },
                )
            }
        return LogExportFormat.document(header(files), files)
    }

    private fun header(files: List<LogFileContent>): String = buildString {
        appendLine("Meteor diagnostic log")
        appendLine("exported: ${clock()}")
        appendLine("app: ${appVersion()}")
        appendLine("build: ${if (isDebuggable()) "debug" else "release"}")
        appendLine("package: ${context.packageName}")
        appendLine(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT}), " +
                "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
        )
        appendLine("abi: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
        appendLine("locale: ${Locale.getDefault().toLanguageTag()}")
        appendLine("timezone: ${TimeZone.getDefault().id}")
        appendLine("verbose logging: ${appLog.isVerbose()}")
        appendLine("log files: ${files.size}")
        appendLine("---")
    }

    private fun displayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()

        return queried?.takeIf { it.isNotBlank() } ?: suggestedFileName()
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

    private fun isDebuggable(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        const val LOG_DIR = "logs"
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
