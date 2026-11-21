package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaRefTest {

    private val id = "0123456789abcdef0123456789abcdef"

    /** Path-shaped token as [EncryptedImageStore.save] returns it. */
    private val token =
        "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/${EncryptedImageStore.SELF_DIR}/9f8e7d6c.enc"

    @Test
    fun `media scheme parses to a media ref`() {
        assertEquals(MediaRef.Media(id), MediaRef.parse("meteor://media/$id"))
    }

    @Test
    fun `image scheme parses to a legacy image ref`() {
        assertEquals(MediaRef.LegacyImage(token), MediaRef.parse("meteor://image/$token"))
    }

    @Test
    fun `old bare token parses to a legacy image ref`() {
        assertEquals(MediaRef.LegacyImage(token), MediaRef.parse(token))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(MediaRef.Media(id), MediaRef.parse("  meteor://media/$id  "))
        assertEquals(MediaRef.LegacyImage(token), MediaRef.parse("\t$token\n"))
    }

    /** A whole Markdown image is a body fragment, not a URL: `parse` takes the target only. */
    @Test
    fun `a whole markdown image is not a media url`() {
        assertNull(MediaRef.parse(MediaRef.markdown(id, "alt")))
    }

    @Test
    fun `malformed media ids are rejected`() {
        for (bad in listOf(
            "meteor://media/",
            "meteor://media/abc",
            "meteor://media/${id.uppercase()}",
            "meteor://media/${id}x",
            "meteor://media/../$id",
            "meteor://media/$id/extra",
        )) {
            assertNull(bad, MediaRef.parse(bad))
        }
    }

    @Test
    fun `non meteor urls are rejected`() {
        for (bad in listOf(
            "",
            "   ",
            "https://example.com/a.png",
            "meteor://person/abc",
            "meteor://diary/abc",
            "meteor://image/",
            "meteor://media/$id?x=1",
        )) {
            assertNull(bad, MediaRef.parse(bad))
        }
    }

    /** Only `ImageStore.save`'s path-shaped output is a bare legacy token. */
    @Test
    fun `free text and non token strings are not bare tokens`() {
        for (bad in listOf(
            "photo.jpg",
            "hello",
            "not a token",
            "${StorageLayout.IMAGES}/abc.png",
            "${StorageLayout.ARCHIVE}/${StorageLayout.SELF}/profile.md",
            "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/../secret.enc",
            "/${StorageLayout.LEGACY}/${StorageLayout.IMAGES}/_self/a.enc",
            "${StorageLayout.LEGACY}/${StorageLayout.IMAGES}//a.enc",
        )) {
            assertNull(bad, MediaRef.parse(bad))
        }
    }

    @Test
    fun `markdown renders the media scheme`() {
        assertEquals("![](meteor://media/$id)", MediaRef.markdown(id))
        assertEquals("![alt](meteor://media/$id)", MediaRef.markdown(id, "alt"))
    }

    @Test
    fun `the stored url form parses back to the same ref`() {
        assertEquals(MediaRef.Media(id), MediaRef.parse("${MediaStore.MEDIA_SCHEME}$id"))
    }

    @Test
    fun `media store markdown matches the ref helper`() {
        assertEquals(MediaRef.markdown(id), MediaStore.markdownFor(id))
        assertEquals(
            "![](meteor://media/$id)",
            MediaStore.markdownFor(id),
        )
    }
}
