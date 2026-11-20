package com.meteor.memory.feature.people

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTagLibraryTest {

    @Test
    fun `remembered tags are merged with the current person's tags`() {
        assertEquals(
            listOf("classmate", "colleague", "family"),
            PeopleTagLibrary.available(
                remembered = listOf("colleague", "classmate"),
                currentPersonTags = listOf("family", "classmate"),
            ),
        )
    }

    @Test
    fun `create dialog without a current person sees remembered tags only`() {
        assertEquals(
            listOf("classmate"),
            PeopleTagLibrary.available(remembered = listOf("classmate")),
        )
    }

    @Test
    fun `blank, duplicate and whitespace-padded labels are normalized`() {
        assertEquals(
            listOf("a", "b"),
            PeopleTagLibrary.available(
                remembered = listOf(" b ", "", "  ", "b"),
                currentPersonTags = listOf("a"),
            ),
        )
    }
}
