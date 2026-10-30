package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FrontmatterTest {

    private val timestamp = Instant.parse("2025-03-14T13:30:07Z")

    private fun header(personId: String? = "V1StGXR8_Z5jdHi6B-myT") = DocumentHeader(
        personId = personId,
        type = RecordType.DIARY,
        timestamp = timestamp,
        source = RecordSource.USER,
    )

    @Test
    fun `round trips the five fields`() {
        val parsed = Frontmatter.split(Frontmatter.encode(header()) + "PAYLOAD")
        assertNotNull(parsed)
        assertEquals("V1StGXR8_Z5jdHi6B-myT", parsed!!.header.personId)
        assertEquals(RecordType.DIARY, parsed.header.type)
        assertEquals(RecordSource.USER, parsed.header.source)
        assertEquals(timestamp, parsed.header.timestamp)
        assertEquals("PAYLOAD", parsed.payload)
        assertEquals(Frontmatter.ENC_SCHEME, parsed.encScheme)
    }

    @Test
    fun `blank person id becomes null`() {
        val parsed = Frontmatter.split(Frontmatter.encode(header(personId = null)) + "X")
        assertNull(parsed!!.header.personId)
    }

    @Test
    fun `plaintext scheme is accepted and reported`() {
        val text = Frontmatter.encode(header(), Frontmatter.PLAINTEXT_SCHEME) + "readable body"
        val parsed = Frontmatter.split(text)
        assertEquals(Frontmatter.PLAINTEXT_SCHEME, parsed!!.encScheme)
    }

    @Test
    fun `unknown scheme is rejected`() {
        val text = Frontmatter.encode(header()).replace(Frontmatter.ENC_SCHEME, "rot13")
        assertNull(Frontmatter.split(text + "X"))
    }

    @Test
    fun `missing required key is rejected`() {
        val text = Frontmatter.encode(header()).replace("source: user\n", "")
        assertNull(Frontmatter.split(text + "X"))
    }

    @Test
    fun `garbage without fence is rejected`() {
        assertNull(Frontmatter.split("just a plain text file"))
        assertNull(Frontmatter.split(""))
    }

    @Test
    fun `header never contains body content`() {
        val encoded = Frontmatter.encode(header())
        assertTrue(encoded.contains("person_id"))
        assertTrue(!encoded.contains("display_name"))
    }
}
