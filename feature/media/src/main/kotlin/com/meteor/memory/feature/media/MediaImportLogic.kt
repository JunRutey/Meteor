package com.meteor.memory.feature.media

import com.meteor.memory.core.storage.MediaKind
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Android-free half of the media import pipeline (ADR-052 §4).
 *
 * Everything here is a pure function so the rules that were easiest to get
 * wrong — MIME → [MediaKind], which timestamp becomes `sourceDate`, how a
 * retriever rotation swaps width and height — are covered by JVM unit tests
 * (`MediaImportLogicTest`). The Android-bound half lives in [MediaImporter] and
 * [DeviceMediaQuery].
 *
 * Internal on purpose: ADR-052 §4 fixes the module's public surface at six
 * declarations, and this is implementation detail.
 */
internal object MediaImportLogic {

    /** Longest edge of a generated PNG thumbnail (ADR-052 §4). */
    const val MAX_THUMBNAIL_EDGE = 512

    private val EXIF_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

    /**
     * `MediaMetadataRetriever` reports `METADATA_KEY_DATE` like
     * `20100214T153000.000Z`, which is UTC without a colon in the offset.
     */
    private val RETRIEVER_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

    private val EXIF_OFFSET: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * The `image`, `video` and `audio` MIME families decide the kind; anything
     * else is not media Meteor can store, so the import is rejected with `null`.
     */
    fun kindForMimeType(mimeType: String?): MediaKind? {
        val normalized = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when {
            normalized.startsWith("image/") -> MediaKind.IMAGE
            normalized.startsWith("video/") -> MediaKind.VIDEO
            normalized.startsWith("audio/") -> MediaKind.AUDIO
            else -> null
        }
    }

    /**
     * Fallback for providers that return a null MIME type: a content resolver
     * answering `null` must not turn a `.mp4` into an unimportable file.
     */
    fun mimeTypeForFileName(fileName: String?): String? {
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "m4v" -> "video/x-m4v"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "amr" -> "audio/amr"
            else -> null
        }
    }

    /** EXIF timestamps are `yyyy:MM:dd HH:mm:ss` in the camera's local time. */
    fun parseExifDateTime(raw: String?): LocalDateTime? {
        val value = raw?.trim().orEmpty()
        if (value.length < EXIF_DATE_TIME_PATTERN_LENGTH) return null
        return runCatching {
            LocalDateTime.parse(value.substring(0, EXIF_DATE_TIME_PATTERN_LENGTH), EXIF_DATE_TIME)
        }.getOrNull()
    }

    /** EXIF `OffsetTimeOriginal` is `±HH:mm`; a bare `+08:00` and `Z` both appear. */
    fun parseExifOffset(raw: String?): ZoneOffset? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value == "Z" || value == "z") return ZoneOffset.UTC
        val sign = when (value.first()) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = value.substring(1).replace(":", "")
        if (body.length != 4 || body.any { !it.isDigit() }) return null
        val hours = body.substring(0, 2).toIntOrNull() ?: return null
        val minutes = body.substring(2, 4).toIntOrNull() ?: return null
        if (hours > 18 || minutes > 59) return null
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
    }

    /**
     * `DateTimeOriginal` first, then `DateTimeDigitized`, then `DateTime`
     * (ADR-052 §4). Without any of them the source date stays unknown — the
     * caller falls back to the import date, never to "now".
     */
    fun sourceDateFromExif(
        dateTimeOriginal: String?,
        offsetTimeOriginal: String? = null,
        dateTimeDigitized: String? = null,
        dateTime: String? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        val local = parseExifDateTime(dateTimeOriginal)
            ?: parseExifDateTime(dateTimeDigitized)
            ?: parseExifDateTime(dateTime)
            ?: return null
        val offset = parseExifOffset(offsetTimeOriginal) ?: zone.rules.getOffset(local)
        return local.toInstant(offset)
    }

    /**
     * MediaStore `DATE_TAKEN` wins over `DATE_ADDED`; `DATE_ADDED` is stored in
     * **seconds** while `DATE_TAKEN` is milliseconds. Zero means "unknown".
     */
    fun sourceDateFromMediaStore(dateTakenMillis: Long?, dateAddedSeconds: Long?): Instant? {
        dateTakenMillis?.takeIf { it > 0 }?.let { return Instant.ofEpochMilli(it) }
        return dateAddedSeconds?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
    }

    /** `METADATA_KEY_DATE` of a video/audio track, in UTC. */
    fun parseRetrieverDate(raw: String?): Instant? {
        val value = raw?.trim().orEmpty()
        if (value.length < RETRIEVER_DATE_PATTERN_LENGTH) return null
        return runCatching {
            val local = LocalDateTime.parse(
                value.substring(0, RETRIEVER_DATE_PATTERN_LENGTH),
                RETRIEVER_DATE_TIME,
            )
            local.toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

    fun parseDurationMs(raw: String?): Long? =
        raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    fun parseDimension(raw: String?): Int? =
        raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /**
     * A retriever rotation of 90°/270° means the stored pixel buffer is
     * sideways: the displayed size is the swapped one.
     */
    fun orientedVideoSize(width: Int?, height: Int?, rotationDegrees: Int?): Pair<Int, Int>? {
        val w = width ?: return null
        val h = height ?: return null
        if (w <= 0 || h <= 0) return null
        val normalized = ((rotationDegrees ?: 0) % 360 + 360) % 360
        return if (normalized == 90 || normalized == 270) h to w else w to h
    }

    /** Calendar month an entry is filed under; null when the date is unknown. */
    fun monthBucket(date: Instant?, zone: ZoneId): YearMonth? =
        date?.atZone(zone)?.toLocalDate()?.let { YearMonth.from(it) }

    /** `m:ss` / `h:mm:ss`, digits only, so no string resource is needed. */
    fun formatDuration(durationMs: Long?): String {
        val totalSeconds = (durationMs ?: 0L).coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * SHA-256 of everything [input] yields, as 64 lowercase hex characters
     * (ADR-064).
     *
     * Streams instead of buffering: de-duplication hashes a potentially
     * multi-gigabyte video *before* probing it, so the source is read once in
     * fixed-size blocks and never held in the heap. The caller owns (and closes)
     * the stream.
     */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(hex)
    }

    private const val EXIF_DATE_TIME_PATTERN_LENGTH = 19
    private const val RETRIEVER_DATE_PATTERN_LENGTH = 15
    private const val HASH_BUFFER_SIZE = 64 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"
}
