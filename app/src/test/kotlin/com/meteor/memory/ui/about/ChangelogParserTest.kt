package com.meteor.memory.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogParserTest {

    @Test
    fun `empty file has no versions`() {
        assertTrue(ChangelogParser.parse("").isEmpty())
        assertTrue(ChangelogParser.parse("   \n\n").isEmpty())
    }

    @Test
    fun `text without version headings is ignored`() {
        val versions = ChangelogParser.parse(
            """
            # Changelog

            Some introductory text.
            - a stray bullet
            """.trimIndent(),
        )

        assertTrue(versions.isEmpty())
    }

    @Test
    fun `versions keep file order with newest first`() {
        val versions = ChangelogParser.parse(
            """
            ## v2.0.0

            - newest

            ## v1.5.0

            - middle

            ## v1.0.0

            - oldest
            """.trimIndent(),
        )

        assertEquals(listOf("v2.0.0", "v1.5.0", "v1.0.0"), versions.map { it.number })
        assertEquals(listOf("- newest"), versions[0].lines)
        assertEquals(listOf("- middle"), versions[1].lines)
        assertEquals(listOf("- oldest"), versions[2].lines)
    }

    @Test
    fun `section headings stay inside their version`() {
        val versions = ChangelogParser.parse(
            """
            ## v1.2.0

            ### Added

            - one
            - two

            ### Fixed

            - three

            ## v1.1.0

            ### Changed

            - four
            """.trimIndent(),
        )

        assertEquals(2, versions.size)
        assertEquals(
            listOf(
                "### Added",
                "",
                "- one",
                "- two",
                "",
                "### Fixed",
                "",
                "- three",
            ),
            versions[0].lines,
        )
        assertEquals(
            listOf("### Changed", "", "- four"),
            versions[1].lines,
        )
    }

    @Test
    fun `a version without body keeps empty lines`() {
        val versions = ChangelogParser.parse("## v3.0.0\n\n## v2.0.0\n- body")

        assertEquals(2, versions.size)
        assertTrue(versions[0].lines.isEmpty())
        assertEquals(listOf("- body"), versions[1].lines)
    }

    @Test
    fun `heading without a number is treated as body text`() {
        val versions = ChangelogParser.parse("## v1.0.0\n\n##\n\n- body")

        assertEquals(1, versions.size)
        assertEquals(listOf("##", "", "- body"), versions[0].lines)
    }
}
