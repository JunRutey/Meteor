package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonProfileCodecTest {

    @Test
    fun `round trips name relation and notes`() {
        val profile = PersonProfile("小明", Relation.FRIEND, "大学同学\n喜欢篮球")
        val decoded = PersonProfileCodec.decode(PersonProfileCodec.encode(profile))

        assertEquals("小明", decoded!!.displayName)
        assertEquals(Relation.FRIEND, decoded.relation)
        assertEquals("大学同学\n喜欢篮球", decoded.notes)
    }

    @Test
    fun `profile without relation is allowed`() {
        val decoded = PersonProfileCodec.decode(
            PersonProfileCodec.encode(PersonProfile("小红", null)),
        )
        assertEquals("小红", decoded!!.displayName)
        assertNull(decoded.relation)
    }

    @Test
    fun `empty notes stay empty`() {
        val decoded = PersonProfileCodec.decode(
            PersonProfileCodec.encode(PersonProfile("小李", Relation.FAMILY)),
        )
        assertEquals("", decoded!!.notes)
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
    fun `exported profile stays human readable`() {
        val body = PersonProfileCodec.encode(PersonProfile("小明", Relation.FRIEND, "同学"))
        assertEquals("name: 小明\nrelation: friend\n---\n同学\n", body)
    }
}
