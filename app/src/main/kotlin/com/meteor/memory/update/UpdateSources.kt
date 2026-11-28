package com.meteor.memory.update

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Download sources for a release APK (ADR-072): the official GitHub URL plus
 * the built-in community mirrors.
 *
 * Hard rules:
 * - The mirror list is baked in, not fetched. There is no authoritative
 *   community list to download, so the built-ins are the three public-welfare
 *   relays of the open-source `hunshcn/gh-proxy` family that were reachable
 *   when this was written.
 * - A mirror URL is always derived locally by prefixing the already-validated
 *   `github.com` URL from the release JSON — a mirror name in the JSON itself
 *   would be attacker-controlled and is never used.
 * - Whichever source wins, the bytes are verified against GitHub's own
 *   SHA-256 digest; the mirror only changes speed, never trust.
 */
internal object UpdateSources {

    /**
     * Prefix-style mirrors, in the order they should be tried when no latency
     * information separates them. `https://ghproxy.com` and `ghp.ci` are gone
     * (blocked), so only currently reachable hosts are listed.
     */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
    )

    /**
     * The official GitHub host that also relays release metadata when the API
     * is unreachable directly (it proxies `api.github.com` too).
     */
    const val METADATA_MIRROR_PREFIX = "https://gh-proxy.com/"

    /** Every candidate URL for one APK: official first, then mirrors. */
    fun candidatesFor(apkUrl: String): List<String> =
        listOf(apkUrl) + MIRROR_PREFIXES.map { prefix -> mirrorUrl(prefix, apkUrl) }

    /** Prefix-style mirror address: `<mirror>/<original github url>`. */
    fun mirrorUrl(prefix: String, githubUrl: String): String = prefix + githubUrl

    /**
     * Orders candidate URLs by probe latency: fastest first, failures last.
     * The sort is stable, so ties keep the list order — the official URL (the
     * first candidate) wins every tie and the mirrors fall through in their
     * declared order.
     */
    fun rankedCandidates(candidates: List<String>, latencies: Map<String, Long?>): List<String> =
        candidates.sortedWith(compareBy { latencies[it] ?: Long.MAX_VALUE })

    /**
     * Probes every candidate at once with a one-byte ranged GET and reports
     * the elapsed milliseconds until the response head; null means the probe
     * timed out or failed and the source ranks last.
     *
     * A ranged GET instead of HEAD because some relays answer HEAD with the
     * same response a proxy error page would, while `Range: bytes=0-0` is
     * honoured by every host on the list and costs one byte.
     */
    suspend fun probeLatencies(
        candidates: List<String>,
        timeoutMillis: Int = PROBE_TIMEOUT_MILLIS,
    ): Map<String, Long?> = coroutineScope {
        candidates.map { url ->
            async { url to measureLatency(url, timeoutMillis) }
        }.awaitAll().toMap()
    }

    private fun measureLatency(url: String, timeoutMillis: Int): Long? {
        var connection: HttpURLConnection? = null
        val startedAt = System.nanoTime()
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", PROBE_USER_AGENT)
            }
            val reachable = connection.responseCode in PROBE_OK_CODES
            if (!reachable) {
                null
            } else {
                (System.nanoTime() - startedAt) / 1_000_000
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Streaming SHA-256 of a local file; null input digest shortcuts the caller. */
    fun sha256Hex(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val PROBE_TIMEOUT_MILLIS = 4_000
    private const val PROBE_USER_AGENT = "Meteor-Android"
    private const val HASH_BUFFER_SIZE = 64 * 1024
    private val PROBE_OK_CODES = 200..399
}
