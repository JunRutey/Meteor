package com.meteor.memory.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The "meteor-sig" marker is a data format written into the encrypted body:
 * it must survive Markdown round-trips, carry a fixed offset, and never leak
 * into rendered text. These tests pin that contract (§5.5 of the handbook).
 */
class SystemTimeModuleTest {

    private val module = SystemTimeModule()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val instant: Instant = Instant.parse("2025-03-14T13:30:07Z")
    private val stamped = "<!-- meteor-sig: 2025-03-14T21:30:07+08:00 -->"

    @Test
    fun `stamp appends marker with the zone offset`() {
        assertEquals("diary body\n$stamped\n", module.stamp("diary body", instant, zone))
    }

    @Test
    fun `stamp trims trailing whitespace before appending`() {
        assertEquals("body\n$stamped\n", module.stamp("body  \n\n", instant, zone))
    }

    @Test
    fun `readStamp parses the marker back to the same instant`() {
        assertEquals(instant, module.readStamp("body\n$stamped\n"))
    }

    @Test
    fun `readStamp handles a marker with a different offset`() {
        val utc = "<!-- meteor-sig: 2025-03-14T13:30:07Z -->"
        assertEquals(instant, module.readStamp(utc))
    }

    @Test
    fun `readStamp returns null when no marker is present`() {
        assertNull(module.readStamp("plain diary body"))
    }

    @Test
    fun `readStamp returns null when the payload is not a timestamp`() {
        assertNull(module.readStamp("<!-- meteor-sig: not-a-time -->"))
    }

    @Test
    fun `readStamp reads the first marker when several exist`() {
        val second = "<!-- meteor-sig: 2025-03-15T21:30:07+08:00 -->"
        assertEquals(instant, module.readStamp("$stamped\n$second"))
    }

    @Test
    fun `strip removes the marker and trailing newline`() {
        assertEquals("diary body", module.strip("diary body\n$stamped\n"))
    }

    @Test
    fun `strip removes every marker`() {
        val second = "<!-- meteor-sig: 2025-03-15T21:30:07+08:00 -->"
        assertEquals("a\n\nb", module.strip("a\n$stamped\nb\n$second\n"))
    }

    @Test
    fun `strip leaves ordinary HTML comments untouched`() {
        val body = "a <!-- normal comment --> b\n$stamped\n"
        assertEquals("a <!-- normal comment --> b", module.strip(body))
    }

    @Test
    fun `stamp then strip restores the trimmed body`() {
        assertEquals("diary body", module.strip(module.stamp("diary body  \n", instant, zone)))
    }

    @Test
    fun `formatDate is locale aware`() {
        val date = LocalDate.of(2025, 3, 14)
        val zh = module.formatDate(date, Locale.CHINA)
        val en = module.formatDate(date, Locale.US)

        assertTrue(zh.isNotBlank())
        assertTrue(en.isNotBlank())
        assertNotEquals(zh, en)
    }
}
