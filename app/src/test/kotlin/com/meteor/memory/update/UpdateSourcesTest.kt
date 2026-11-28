package com.meteor.memory.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSourcesTest {

    private val apkUrl =
        "https://github.com/JunRutey/Meteor/releases/download/v1.6.0/app-release.apk"

    @Test
    fun `mirror url prefixes the already-validated github url`() {
        assertEquals(
            "https://gh-proxy.com/$apkUrl",
            UpdateSources.mirrorUrl("https://gh-proxy.com/", apkUrl),
        )
        assertEquals(
            "https://ghfast.top/$apkUrl",
            UpdateSources.mirrorUrl("https://ghfast.top/", apkUrl),
        )
    }

    @Test
    fun `candidates put the official url first and derive every mirror from it`() {
        val candidates = UpdateSources.candidatesFor(apkUrl)

        // Direct plus the three built-in mirrors; nothing else may sneak in.
        assertEquals(4, candidates.size)
        assertEquals(apkUrl, candidates.first())
        assertEquals(4, candidates.toSet().size)
        assertTrue(candidates.drop(1).all { it.endsWith(apkUrl) })
    }

    @Test
    fun `ranking orders by probe latency`() {
        val candidates = UpdateSources.candidatesFor(apkUrl)
        val latencies = mapOf(
            candidates[0] to 180L,
            candidates[1] to 90L,
            candidates[2] to 40L,
            candidates[3] to 260L,
        )

        assertEquals(
            listOf(candidates[2], candidates[1], candidates[0], candidates[3]),
            UpdateSources.rankedCandidates(candidates, latencies),
        )
    }

    @Test
    fun `failed and missing probes rank last`() {
        val candidates = UpdateSources.candidatesFor(apkUrl)
        val latencies = mapOf(
            candidates[0] to null,
            candidates[1] to 500L,
            candidates[2] to null,
            // candidates[3] is absent from the map, exactly like a probe that
            // never reported back; it must rank with the failures, not crash.
        )

        // The ranked list is the reachable source first, then the failures in
        // their original order — the official url still leads the failures.
        assertEquals(
            listOf(candidates[1], candidates[0], candidates[2], candidates[3]),
            UpdateSources.rankedCandidates(candidates, latencies),
        )
    }

    @Test
    fun `ties keep the official url ahead of every mirror`() {
        val candidates = UpdateSources.candidatesFor(apkUrl)
        val latencies = candidates.associateWith { 120L }

        assertEquals(candidates, UpdateSources.rankedCandidates(candidates, latencies))
    }
}
