package com.meteor.memory.feature.people

import com.meteor.memory.core.storage.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTagLibraryTest {

    private val relationLabels = mapOf(
        Relation.FAMILY to "family",
        Relation.FRIEND to "friend",
        Relation.FORMER_FRIEND to "former",
    )

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

    @Test
    fun `visible tags merge remembered and selected labels`() {
        assertEquals(
            listOf("colleague", "family", "friend"),
            PeopleTagLibrary.visibleTags(
                available = listOf("colleague", "family"),
                selected = listOf("friend"),
            ),
        )
    }

    @Test
    fun `the unclassified chip is never offered as a deletable tag`() {
        assertEquals(
            listOf("family"),
            PeopleTagLibrary.visibleTags(
                available = listOf("unclassified", "family"),
                selected = listOf("unclassified"),
                excluded = listOf("unclassified"),
            ),
        )
    }

    @Test
    fun `legacy relation label is folded into the selected tags`() {
        assertEquals(
            listOf("friend", "colleague"),
            PeopleTagLibrary.selectedTags(
                tags = listOf("colleague"),
                legacyRelationLabel = "friend",
            ),
        )
        // Already in the tag list: fold must not duplicate it.
        assertEquals(
            listOf("colleague", "friend"),
            PeopleTagLibrary.selectedTags(
                tags = listOf("colleague", "friend"),
                legacyRelationLabel = "friend",
            ),
        )
        assertEquals(
            listOf("colleague"),
            PeopleTagLibrary.selectedTags(
                tags = listOf("colleague"),
                legacyRelationLabel = null,
            ),
        )
    }

    @Test
    fun `relation is derived only from selected built-in labels`() {
        assertEquals(
            Relation.FRIEND,
            PeopleTagLibrary.relationFor(listOf("colleague", "friend"), relationLabels),
        )
        // Deleting every built-in label must persist a null relation.
        assertNull(PeopleTagLibrary.relationFor(listOf("colleague"), relationLabels))
    }

    @Test
    fun `a new person starts unclassified`() {
        assertTrue(PeopleTagLibrary.NEW_PERSON_TAGS.isEmpty())
        assertNull(PeopleTagLibrary.NEW_PERSON_RELATION)
        assertNull(PeopleTagLibrary.relationFor(PeopleTagLibrary.NEW_PERSON_TAGS, relationLabels))
    }
}
