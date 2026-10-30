package com.meteor.memory.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Time watermark ("meteor-sig") helpers.
 *
 * Contract: the signature lives inside the decrypted body as an HTML comment so
 * it survives Markdown round-trips and never shows up in rendered text:
 *
 * ```
 * <!-- meteor-sig: 2025-03-14T21:30:07+08:00 -->
 * ```
 *
 * [strip] must be applied by the render pipeline before display.
 */
interface TimeModule {
    /** Current instant as an ISO-8601 string in [zone]. */
    fun nowIso(zone: ZoneId = ZoneId.systemDefault()): String

    /** Appends a signature marker for [timestamp] to [body]. */
    fun stamp(body: String, timestamp: Instant, zone: ZoneId = ZoneId.systemDefault()): String

    /** Returns the parsed signature instant, or null when absent/unparseable. */
    fun readStamp(body: String): Instant?

    /** Removes every signature marker from [body]. */
    fun strip(body: String): String

    /** Locale-aware date formatting. Never hardcode a pattern per language. */
    fun formatDate(date: LocalDate, locale: Locale): String
}

class SystemTimeModule : TimeModule {

    override fun nowIso(zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now().atZone(zone))

    override fun stamp(body: String, timestamp: Instant, zone: ZoneId): String {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp.atZone(zone))
        return buildString {
            append(body.trimEnd())
            append('\n')
            append(SIG_OPEN)
            append(iso)
            append(SIG_CLOSE)
            append('\n')
        }
    }

    override fun readStamp(body: String): Instant? {
        val raw = SIG_REGEX.find(body)?.groupValues?.getOrNull(1) ?: return null
        return runCatching { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(raw)) }.getOrNull()
    }

    override fun strip(body: String): String =
        body.replace(SIG_REGEX, "").trimEnd()

    override fun formatDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(date)

    private companion object {
        const val SIG_OPEN = "<!-- meteor-sig: "
        const val SIG_CLOSE = " -->"
        val SIG_REGEX = Regex("""<!-- meteor-sig: (.+?) -->""")
    }
}
