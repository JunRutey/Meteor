package com.meteor.memory.core.backup

import com.meteor.memory.core.storage.MediaKind
import com.meteor.memory.core.storage.StorageLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MediaManifestTest {

    private val sourceDate = Instant.parse("2024-05-06T07:08:09Z")

    /** `数字遗产/媒体/<owner>/...` built from constants: no Chinese in code. */
    private fun mediaPath(fileName: String): String =
        "${StorageLayout.LEGACY}/${StorageLayout.MEDIA}/$fileName"

    private fun videoEntry(
        id: String = "0123456789abcdef0123456789abcdef",
        fileName: String = "trip.mp4",
    ) = MediaManifestEntry(
        id = id,
        kind = MediaKind.VIDEO,
        fileName = fileName,
        mimeType = "video/mp4",
        personId = "person_123",
        sourceDate = sourceDate,
        durationMs = 12_345L,
        width = 1920,
        height = 1080,
        path = mediaPath("person_123/$id.mp4"),
    )

    @Test
    fun `entry with every field round trips`() {
        val entry = videoEntry()

        val decoded = MediaManifest.decode(MediaManifest.encode(listOf(entry))).single()

        assertEquals(entry, decoded)
        assertEquals(sourceDate, decoded.sourceDate)
        assertEquals(1920, decoded.width)
        assertEquals(12_345L, decoded.durationMs)
    }

    @Test
    fun `null optional fields survive the round trip`() {
        val entry = MediaManifestEntry(
            id = "ffffffffffffffffffffffffffffffff",
            kind = MediaKind.IMAGE,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            personId = null,
            sourceDate = null,
            durationMs = null,
            width = null,
            height = null,
            path = mediaPath("_self/ffffffffffffffffffffffffffffffff.jpg"),
            thumbnailPath = mediaPath("_self/ffffffffffffffffffffffffffffffff.thumb.png"),
        )

        val decoded = MediaManifest.decode(MediaManifest.encode(listOf(entry))).single()

        assertEquals(entry, decoded)
        assertNull(decoded.personId)
        assertNull(decoded.sourceDate)
        assertNull(decoded.width)
        assertNull(decoded.durationMs)
        assertEquals(entry.thumbnailPath, decoded.thumbnailPath)
    }

    @Test
    fun `thumbnail path is omitted when absent`() {
        val json = MediaManifest.encode(listOf(videoEntry()))

        val item = JSONObject(json).getJSONArray("media").getJSONObject(0)

        assertTrue(!item.has("thumbnailPath"))
        assertEquals(MediaManifest.VERSION, JSONObject(json).getInt("version"))
    }

    @Test
    fun `instant keeps second precision through epoch independent text`() {
        val entry = videoEntry().copy(sourceDate = Instant.parse("1999-12-31T23:59:59Z"))

        val json = MediaManifest.encode(listOf(entry))

        assertTrue(json.contains("1999-12-31T23:59:59Z"))
        assertEquals(entry.sourceDate, MediaManifest.decode(json).single().sourceDate)
    }

    @Test
    fun `empty manifest round trips`() {
        val decoded = MediaManifest.decode(MediaManifest.encode(emptyList()))

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `every kind round trips`() {
        val entries = MediaKind.entries.mapIndexed { index, kind ->
            videoEntry(id = index.toString().repeat(32).take(32)).copy(
                kind = kind,
                fileName = "file.$index",
                path = mediaPath("person_123/${index.toString().repeat(32).take(32)}.$index"),
            )
        }

        val decoded = MediaManifest.decode(MediaManifest.encode(entries))

        assertEquals(MediaKind.entries.toList(), decoded.map { it.kind })
    }

    @Test
    fun `illegal id is rejected`() {
        val json = MediaManifest.encode(listOf(videoEntry())).replace(
            "0123456789abcdef0123456789abcdef",
            "0123456789ABCDEF0123456789ABCDEF",
        )

        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `unknown kind is rejected`() {
        val json = MediaManifest.encode(listOf(videoEntry())).replace("VIDEO", "HOLOGRAM")

        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `illegal person id is rejected`() {
        val json = MediaManifest.encode(listOf(videoEntry())).replace("person_123", "../escape")

        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `path outside the media directory is rejected`() {
        val entry = videoEntry()
        // A sibling directory: valid shape, wrong prefix — must not pass the gate.
        val json = MediaManifest.encode(listOf(entry)).replace(
            entry.path,
            "${StorageLayout.LEGACY}/${StorageLayout.ATTACHMENTS}/person_123/${entry.id}.mp4",
        )

        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `path traversal is rejected`() {
        val entry = videoEntry()
        val json = MediaManifest.encode(listOf(entry)).replace(
            entry.path,
            mediaPath("../../outside.mp4"),
        )

        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `blank file name is rejected`() {
        val json = MediaManifest.encode(listOf(videoEntry())).replace("trip.mp4", " ")

        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `lenient decode keeps valid entries and drops broken ones`() {
        val good = videoEntry()
        // Built by hand: the strict encoder would never produce a broken entry,
        // which is exactly the corrupt-archive case this path exists for.
        val json = JSONObject().apply {
            put("version", MediaManifest.VERSION)
            put(
                "media",
                org.json.JSONArray().apply {
                    put(JSONObject(MediaManifest.encode(listOf(good))).getJSONArray("media").getJSONObject(0))
                    put(
                        JSONObject().apply {
                            put("id", "not-a-valid-id")
                            put("kind", "IMAGE")
                            put("fileName", "broken.jpg")
                        },
                    )
                },
            )
        }.toString()

        assertEquals(listOf(good), MediaManifest.decodeLenient(json))
        assertThrows(IllegalArgumentException::class.java) { MediaManifest.decode(json) }
    }

    @Test
    fun `manifest without a media array is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaManifest.decode(JSONObject().put("version", 1).toString())
        }
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = JSONObject(MediaManifest.encode(listOf(videoEntry()))).apply {
            put("futureField", "ignored")
            getJSONArray("media").getJSONObject(0).put("futurePerEntry", 42)
        }.toString()

        assertEquals(videoEntry(), MediaManifest.decode(json).single())
    }

    @Test
    fun `meta input carries every restored field`() {
        val meta = videoEntry().toMetaInput()

        assertEquals(MediaKind.VIDEO, meta.kind)
        assertEquals("trip.mp4", meta.fileName)
        assertEquals("video/mp4", meta.mimeType)
        assertEquals("person_123", meta.personId)
        assertEquals(sourceDate, meta.sourceDate)
        assertEquals(12_345L, meta.durationMs)
        assertEquals(1920, meta.width)
        assertEquals(1080, meta.height)
    }
}
