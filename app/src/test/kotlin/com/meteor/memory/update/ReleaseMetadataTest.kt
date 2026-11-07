package com.meteor.memory.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataTest {

    @Test
    fun `version comparison handles numeric segments and prereleases`() {
        val current = requireNotNull(ReleaseVersion.parse("0.8.9"))
        val nextMinor = requireNotNull(ReleaseVersion.parse("v0.9.0"))

        assertTrue(nextMinor > current)
        assertTrue(
            requireNotNull(ReleaseVersion.parse("1.0.0")) >
                requireNotNull(ReleaseVersion.parse("0.99.99")),
        )
        assertTrue(
            requireNotNull(ReleaseVersion.parse("1.0.0")) >
                requireNotNull(ReleaseVersion.parse("1.0.0-rc.2")),
        )
        assertTrue(
            requireNotNull(ReleaseVersion.parse("1.0.0-rc.10")) >
                requireNotNull(ReleaseVersion.parse("1.0.0-rc.2")),
        )
        assertEquals(0, requireNotNull(ReleaseVersion.parse("v1.2.3")).compareTo(
            requireNotNull(ReleaseVersion.parse("1.2.3")),
        ))
    }

    @Test
    fun `release parser accepts the single GitHub APK asset`() {
        val release = parseReleaseMetadata(releaseJson())

        assertEquals("0.9.0", release.version.toString())
        assertEquals(
            "https://github.com/JunRutey/Meteor/releases/download/v0.9.0/app-release.apk",
            release.apkUrl,
        )
    }

    @Test
    fun `release parser rejects non-GitHub APK URLs`() {
        val error = assertThrows(UpdateException::class.java) {
            parseReleaseMetadata(
                releaseJson("https://example.com/app.apk"),
            )
        }

        assertEquals(UpdateError.INVALID_RELEASE, error.reason)
    }

    @Test
    fun `release parser rejects prereleases`() {
        val error = assertThrows(UpdateException::class.java) {
            parseReleaseMetadata(releaseJson(prerelease = true))
        }

        assertEquals(UpdateError.INVALID_RELEASE, error.reason)
    }

    private fun releaseJson(
        apkUrl: String = "https://github.com/JunRutey/Meteor/releases/download/v0.9.0/app-release.apk",
        prerelease: Boolean = false,
    ): String = """
        {
          "tag_name": "v0.9.0",
          "draft": false,
          "prerelease": $prerelease,
          "assets": [
            {
              "name": "app-release.apk",
              "browser_download_url": "$apkUrl"
            }
          ]
        }
    """.trimIndent()
}
