package com.meteor.memory.core.backup

import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.MediaMetaInput
import com.meteor.memory.core.storage.StorageLayout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * One entry of `设置/media-manifest.json` inside a plaintext export (ADR-057).
 *
 * [path] and [thumbnailPath] are **archive-root relative** (`数字遗产/媒体/...`),
 * exactly like `设置/backup-settings.json`: the zip entry name is the same path
 * with the `我的人生/` root prefix in front of it.
 */
data class MediaManifestEntry(
    val id: String,
    val kind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val personId: String?,
    val sourceDate: Instant?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val path: String,
    val thumbnailPath: String? = null,
) {

    /** Rebuilds the store-facing metadata so a restore keeps every derived field. */
    fun toMetaInput(): MediaMetaInput = MediaMetaInput(
        kind = kind,
        fileName = fileName,
        mimeType = mimeType,
        personId = personId,
        sourceDate = sourceDate,
        durationMs = durationMs,
        width = width,
        height = height,
    )
}

/**
 * Codec for the media manifest (ADR-052 §5 F).
 *
 * The manifest is plaintext JSON inside an already-plaintext zip: it records what
 * the media files under `数字遗产/媒体/` are, because the encrypted sidecars that
 * normally carry that information cannot leave the device. Pure JVM — no Android
 * types — so the whole format is unit-testable.
 *
 * Forward compatibility mirrors [SettingsSnapshotCodec]: the top-level `version`
 * is informational, unknown keys are ignored, and a document written by a newer
 * build still restores everything this build understands.
 */
object MediaManifest {

    /** Current manifest schema version written by this build. */
    const val VERSION = 1

    /** File name inside `设置/`, next to the settings snapshot. */
    const val FILE_NAME = "media-manifest.json"

    /** Archive-root relative path of the manifest. */
    val PATH: String = "${StorageLayout.SETTINGS}/$FILE_NAME"

    /** Every media payload and thumbnail lives under this prefix. */
    val MEDIA_PREFIX: String = "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/"

    /** `StoredMedia.id`: 32 lowercase hex characters (ADR-052 §3). */
    val MEDIA_ID_PATTERN = Regex("^[a-f0-9]{32}$")

    /** Encodes [entries] in the given order; later imports follow the same order. */
    fun encode(entries: List<MediaManifestEntry>): String = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        put(KEY_MEDIA, JSONArray().apply { entries.forEach { put(encodeEntry(it)) } })
    }.toString()

    /**
     * Strict decode: any invalid entry rejects the whole document.
     *
     * Use this where a corrupted manifest must be surfaced to the user instead of
     * silently dropping memories; [decodeLenient] is the import-time variant.
     */
    fun decode(json: String): List<MediaManifestEntry> = parse(json, strict = true)

    /**
     * Import-time decode: keeps the valid entries and drops the malformed ones, so
     * one bad line cannot cost the whole media library.
     */
    fun decodeLenient(json: String): List<MediaManifestEntry> = parse(json, strict = false)

    private fun parse(json: String, strict: Boolean): List<MediaManifestEntry> {
        val root = JSONObject(json)
        val array = root.optJSONArray(KEY_MEDIA)
            ?: throw IllegalArgumentException("media manifest has no $KEY_MEDIA array")

        val entries = ArrayList<MediaManifestEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val entry = item?.let { runCatching { decodeEntry(it) }.getOrNull() }
            if (entry == null) {
                if (strict) throw IllegalArgumentException("invalid media manifest entry at $index")
                continue
            }
            entries += entry
        }
        return entries
    }

    private fun encodeEntry(entry: MediaManifestEntry): JSONObject = JSONObject().apply {
        put(KEY_ID, entry.id)
        put(KEY_KIND, entry.kind.name)
        put(KEY_FILE_NAME, entry.fileName)
        put(KEY_MIME_TYPE, entry.mimeType)
        put(KEY_PERSON_ID, entry.personId ?: JSONObject.NULL)
        put(KEY_SOURCE_DATE, entry.sourceDate?.toString() ?: JSONObject.NULL)
        put(KEY_DURATION_MS, entry.durationMs ?: JSONObject.NULL)
        put(KEY_WIDTH, entry.width ?: JSONObject.NULL)
        put(KEY_HEIGHT, entry.height ?: JSONObject.NULL)
        put(KEY_PATH, entry.path)
        if (entry.thumbnailPath != null) put(KEY_THUMBNAIL_PATH, entry.thumbnailPath)
    }

    private fun decodeEntry(item: JSONObject): MediaManifestEntry {
        val id = item.getString(KEY_ID)
        require(MEDIA_ID_PATTERN.matches(id)) { "illegal media id" }

        val kindName = item.getString(KEY_KIND)
        val kind = MediaKind.entries.firstOrNull { it.name == kindName }
            ?: throw IllegalArgumentException("unknown media kind: $kindName")

        val fileName = item.getString(KEY_FILE_NAME)
        require(fileName.isNotBlank()) { "media file name is required" }

        val personId = item.optNullableString(KEY_PERSON_ID)
        require(personId == null || StorageLayout.PERSON_ID_PATTERN.matches(personId)) {
            "illegal person_id: $personId"
        }

        // `optString` renders a JSON null as the literal text "null", so go through
        // the nullable helper instead of trusting the raw opt.
        val mimeType = item.optNullableString(KEY_MIME_TYPE) ?: MIME_OCTET_STREAM
        val sourceDate = item.optNullableString(KEY_SOURCE_DATE)?.let { Instant.parse(it) }
        val durationMs = item.optNullableLong(KEY_DURATION_MS)
        val width = item.optNullableInt(KEY_WIDTH)
        val height = item.optNullableInt(KEY_HEIGHT)
        require(durationMs == null || durationMs >= 0) { "illegal duration" }
        require(width == null || width >= 0) { "illegal width" }
        require(height == null || height >= 0) { "illegal height" }

        val path = item.getString(KEY_PATH)
        require(isMediaPath(path)) { "illegal media path: $path" }
        val thumbnailPath = item.optNullableString(KEY_THUMBNAIL_PATH)
        require(thumbnailPath == null || isMediaPath(thumbnailPath)) {
            "illegal thumbnail path: $thumbnailPath"
        }

        return MediaManifestEntry(
            id = id,
            kind = kind,
            fileName = fileName,
            mimeType = mimeType,
            personId = personId,
            sourceDate = sourceDate,
            durationMs = durationMs,
            width = width,
            height = height,
            path = path,
            thumbnailPath = thumbnailPath,
        )
    }

    /**
     * A manifest path becomes a zip entry name and later a relative path, so it is
     * validated before it can be joined anywhere: no traversal, no absolute path,
     * no drive letter, and it must sit under the media directory.
     */
    private fun isMediaPath(path: String): Boolean {
        if (path.isBlank() || path.contains('\\') || path.contains(':')) return false
        if (path.startsWith("/") || path.contains("../") || path.contains("/..") || path == "..") return false
        if (path.split('/').any { it.isBlank() || it == "." }) return false
        return path.startsWith(MEDIA_PREFIX) && path.length > MEDIA_PREFIX.length
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = optString(key)
        return value.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private const val KEY_VERSION = "version"
    private const val KEY_MEDIA = "media"
    private const val KEY_ID = "id"
    private const val KEY_KIND = "kind"
    private const val KEY_FILE_NAME = "fileName"
    private const val KEY_MIME_TYPE = "mimeType"
    private const val KEY_PERSON_ID = "personId"
    private const val KEY_SOURCE_DATE = "sourceDate"
    private const val KEY_DURATION_MS = "durationMs"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_PATH = "path"
    private const val KEY_THUMBNAIL_PATH = "thumbnailPath"
    private const val MIME_OCTET_STREAM = "application/octet-stream"
}
