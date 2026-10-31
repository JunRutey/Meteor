package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonProfileCodecTest {

    @Test
    fun `round trips name relation and body`() {
        val profile = PersonProfile(
            displayName = "小明",
            relation = Relation.FRIEND,
            notes = "大学同学\n喜欢篮球",
        )
        val decoded = PersonProfileCodec.decode(PersonProfileCodec.encode(profile))

        assertEquals("小明", decoded!!.displayName)
        assertEquals(Relation.FRIEND, decoded.relation)
        assertEquals("大学同学\n喜欢篮球", decoded.notes)
        assertEquals("", decoded.note)
    }

    @Test
    fun `round trips the one line note separately from the body`() {
        val profile = PersonProfile(
            displayName = "小明",
            relation = Relation.FRIEND,
            note = "大学同学",
            notes = "# 正文\n\n一起打过球",
        )
        val decoded = PersonProfileCodec.decode(PersonProfileCodec.encode(profile))

        assertEquals("大学同学", decoded!!.note)
        assertEquals("# 正文\n\n一起打过球", decoded.notes)
    }

    @Test
    fun `a newline inside the note cannot break the header`() {
        val profile = PersonProfile(displayName = "小明", relation = null, note = "第一行\n第二行")
        val body = PersonProfileCodec.encode(profile)

        // Flattened onto one header line instead of spilling into the file.
        assertTrue(body.contains("note: 第一行 第二行\n"))
        assertEquals("第一行 第二行", PersonProfileCodec.decode(body)!!.note)
    }

    @Test
    fun `profile without relation is allowed`() {
        val decoded = PersonProfileCodec.decode(
            PersonProfileCodec.encode(PersonProfile(displayName = "小红", relation = null)),
        )
        assertEquals("小红", decoded!!.displayName)
        assertNull(decoded.relation)
    }

    @Test
    fun `empty body stays empty`() {
        val decoded = PersonProfileCodec.decode(
            PersonProfileCodec.encode(PersonProfile(displayName = "小李", relation = Relation.FAMILY)),
        )
        assertEquals("", decoded!!.notes)
        assertEquals("", decoded.note)
    }

    @Test
    fun `body without a header is rejected`() {
        assertNull(PersonProfileCodec.decode("随便写的一段话"))
        assertNull(PersonProfileCodec.decode(""))
    }

    @Test
    fun `unknown relation code degrades to null`() {
        val body = "name: 小王\nrelation: acquaintance\n---\n"
        val decoded = PersonProfileCodec.decode(body)
        assertEquals("小王", decoded!!.displayName)
        assertNull(decoded.relation)
    }

    @Test
    fun `missing name is rejected`() {
        assertNull(PersonProfileCodec.decode("relation: friend\n---\n"))
    }

    @Test
    fun `a file written before the note field reads back as body only`() {
        // Exactly what v0.4-rc wrote: no `note:` line at all.
        val decoded = PersonProfileCodec.decode("name: 小明\nrelation: friend\n---\n旧的内容\n")

        assertEquals("小明", decoded!!.displayName)
        assertEquals("", decoded.note)
        assertEquals("旧的内容", decoded.notes)
    }

    @Test
    fun `exported profile stays human readable`() {
        val body = PersonProfileCodec.encode(
            PersonProfile(displayName = "小明", relation = Relation.FRIEND, notes = "同学"),
        )
        assertEquals("name: 小明\nrelation: friend\n---\n同学\n", body)
    }
}
