package com.meteor.memory.feature.media

import com.meteor.memory.core.storage.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [MediaImportLogic] is the Android-free half of the import pipeline (ADR-052
 * §6B): the MIME → kind decision and the source-date rules are the parts that
 * silently corrupt sorting when they are wrong, so they are pinned here.
 */
class MediaImportLogicTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `image video and audio prefixes decide the kind`() {
        assertEquals(MediaKind.IMAGE, MediaImportLogic.kindForMimeType("image/jpeg"))
        assertEquals(MediaKind.VIDEO, MediaImportLogic.kindForMimeType("video/mp4"))
        assertEquals(MediaKind.AUDIO, MediaImportLogic.kindForMimeType("audio/mpeg"))
    }

    @Test
    fun `the kind decision ignores case and parameters`() {
        assertEquals(MediaKind.IMAGE, MediaImportLogic.kindForMimeType("IMAGE/PNG; charset=binary"))
        assertEquals(MediaKind.VIDEO, MediaImportLogic.kindForMimeType("  Video/QuickTime  "))
    }

    @Test
    fun `an unrelated mime type is not media`() {
        assertNull(MediaImportLogic.kindForMimeType("application/pdf"))
        assertNull(MediaImportLogic.kindForMimeType("text/plain"))
        assertNull(MediaImportLogic.kindForMimeType("application/octet-stream"))
    }

    @Test
    fun `a missing mime type is rejected instead of guessed`() {
        assertNull(MediaImportLogic.kindForMimeType(null))
        assertNull(MediaImportLogic.kindForMimeType(""))
        assertNull(MediaImportLogic.kindForMimeType("   "))
    }

    @Test
    fun `file extensions only fill in a missing provider mime type`() {
        assertEquals("image/jpeg", MediaImportLogic.mimeTypeForFileName("a.JPG"))
        assertEquals("video/mp4", MediaImportLogic.mimeTypeForFileName("holiday.mp4"))
        assertEquals("audio/mpeg", MediaImportLogic.mimeTypeForFileName("voice.MP3"))
        assertNull(MediaImportLogic.mimeTypeForFileName("notes.txt"))
        assertNull(MediaImportLogic.mimeTypeForFileName("no-extension"))
        assertNull(MediaImportLogic.mimeTypeForFileName(null))
    }

    @Test
    fun `exif date time original is parsed`() {
        assertEquals(
            LocalDateTime.of(2024, 5, 17, 8, 30, 5),
            MediaImportLogic.parseExifDateTime("2024:05:17 08:30:05"),
        )
    }

    @Test
    fun `a malformed exif date is refused`() {
        assertNull(MediaImportLogic.parseExifDateTime(null))
        assertNull(MediaImportLogic.parseExifDateTime(""))
        assertNull(MediaImportLogic.parseExifDateTime("not a date"))
        assertNull(MediaImportLogic.parseExifDateTime("2024:13:45 99:61:61"))
    }

    @Test
    fun `exif offset time original is parsed`() {
        assertEquals(ZoneOffset.ofHours(8), MediaImportLogic.parseExifOffset("+08:00"))
        assertEquals(ZoneOffset.ofHours(-5), MediaImportLogic.parseExifOffset("-05:00"))
        assertEquals(ZoneOffset.ofHours(8), MediaImportLogic.parseExifOffset("+0800"))
        assertEquals(ZoneOffset.UTC, MediaImportLogic.parseExifOffset("Z"))
        assertNull(MediaImportLogic.parseExifOffset(""))
        assertNull(MediaImportLogic.parseExifOffset("+8"))
        assertNull(MediaImportLogic.parseExifOffset("08:00"))
    }

    @Test
    fun `date time original wins over the other exif dates`() {
        val instant = MediaImportLogic.sourceDateFromExif(
            dateTimeOriginal = "2024:05:17 08:30:05",
            offsetTimeOriginal = "+08:00",
            dateTimeDigitized = "2020:01:01 00:00:00",
            dateTime = "2019:01:01 00:00:00",
            zone = shanghai,
        )
        assertEquals(Instant.parse("2024-05-17T00:30:05Z"), instant)
    }

    @Test
    fun `date time digitized is the second choice`() {
        val instant = MediaImportLogic.sourceDateFromExif(
            dateTimeOriginal = null,
            dateTimeDigitized = "2021:03:04 05:06:07",
            dateTime = "2019:01:01 00:00:00",
            zone = ZoneOffset.UTC,
        )
        assertEquals(Instant.parse("2021-03-04T05:06:07Z"), instant)
    }

    @Test
    fun `without a timezone the local zone is applied`() {
        val instant = MediaImportLogic.sourceDateFromExif(
            dateTimeOriginal = "2024:05:17 08:30:05",
            zone = ZoneOffset.UTC,
        )
        assertEquals(Instant.parse("2024-05-17T08:30:05Z"), instant)
    }

    @Test
    fun `no exif date at all leaves the source date unknown`() {
        assertNull(MediaImportLogic.sourceDateFromExif(null, null, null, null, shanghai))
        assertNull(MediaImportLogic.sourceDateFromExif("garbage", null, null, null, shanghai))
    }

    @Test
    fun `media store prefers date taken over date added`() {
        val instant = MediaImportLogic.sourceDateFromMediaStore(
            dateTakenMillis = 1_700_000_000_000L,
            dateAddedSeconds = 1_600_000_000L,
        )
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), instant)
    }

    @Test
    fun `media store falls back to date added in seconds`() {
        val instant = MediaImportLogic.sourceDateFromMediaStore(
            dateTakenMillis = null,
            dateAddedSeconds = 1_600_000_000L,
        )
        assertEquals(Instant.ofEpochSecond(1_600_000_000L), instant)
    }

    @Test
    fun `zero media store dates mean unknown`() {
        assertNull(MediaImportLogic.sourceDateFromMediaStore(0L, null))
        assertNull(MediaImportLogic.sourceDateFromMediaStore(null, 0L))
        assertNull(MediaImportLogic.sourceDateFromMediaStore(-1L, -1L))
        assertNull(MediaImportLogic.sourceDateFromMediaStore(null, null))
    }

    @Test
    fun `retriever date is parsed as utc`() {
        assertEquals(
            Instant.parse("2010-02-14T15:30:00Z"),
            MediaImportLogic.parseRetrieverDate("20100214T153000.000Z"),
        )
        assertNull(MediaImportLogic.parseRetrieverDate(""))
        assertNull(MediaImportLogic.parseRetrieverDate("2010"))
    }

    @Test
    fun `duration and dimension parsing rejects nonsense`() {
        assertEquals(65_000L, MediaImportLogic.parseDurationMs("65000"))
        assertNull(MediaImportLogic.parseDurationMs("-5"))
        assertNull(MediaImportLogic.parseDurationMs("abc"))
        assertNull(MediaImportLogic.parseDurationMs(null))

        assertEquals(1920, MediaImportLogic.parseDimension("1920"))
        assertNull(MediaImportLogic.parseDimension("0"))
        assertNull(MediaImportLogic.parseDimension("-3"))
        assertNull(MediaImportLogic.parseDimension(null))
    }

    @Test
    fun `a rotated video swaps its displayed size`() {
        assertEquals(1080 to 1920, MediaImportLogic.orientedVideoSize(1920, 1080, 90))
        assertEquals(1080 to 1920, MediaImportLogic.orientedVideoSize(1920, 1080, 270))
        assertEquals(1920 to 1080, MediaImportLogic.orientedVideoSize(1920, 1080, 0))
        assertEquals(1920 to 1080, MediaImportLogic.orientedVideoSize(1920, 1080, 180))
        assertEquals(1080 to 1920, MediaImportLogic.orientedVideoSize(1920, 1080, -90))
        assertNull(MediaImportLogic.orientedVideoSize(null, 1080, 0))
        assertNull(MediaImportLogic.orientedVideoSize(0, 1080, 0))
    }

    @Test
    fun `month buckets follow the local zone`() {
        val instant = Instant.parse("2024-01-31T20:00:00Z")
        // 2024-02-01 04:00 in Shanghai: the same instant is January in UTC.
        assertEquals(YearMonth.of(2024, 2), MediaImportLogic.monthBucket(instant, shanghai))
        assertEquals(YearMonth.of(2024, 1), MediaImportLogic.monthBucket(instant, ZoneOffset.UTC))
        assertNull(MediaImportLogic.monthBucket(null, shanghai))
    }

    @Test
    fun `durations are formatted with seconds zero padded`() {
        assertEquals("0:00", MediaImportLogic.formatDuration(0))
        assertEquals("0:09", MediaImportLogic.formatDuration(9_400))
        assertEquals("1:05", MediaImportLogic.formatDuration(65_000))
        assertEquals("1:00:00", MediaImportLogic.formatDuration(3_600_000))
        assertEquals("2:03:04", MediaImportLogic.formatDuration(7_384_000))
        assertEquals("0:00", MediaImportLogic.formatDuration(null))
        assertEquals("0:00", MediaImportLogic.formatDuration(-1))
    }

    @Test
    fun `permission matrix asks per media type on android 13 and up`() {
        val imageVideo = MediaPermissions.required(setOf(MediaKind.IMAGE, MediaKind.VIDEO), sdkInt = 34)
        assertEquals(
            listOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
            ),
            imageVideo,
        )

        assertEquals(
            listOf("android.permission.READ_MEDIA_AUDIO"),
            MediaPermissions.required(setOf(MediaKind.AUDIO), sdkInt = 33),
        )
    }

    @Test
    fun `permission matrix uses the single storage read on android 12 and below`() {
        assertEquals(
            listOf("android.permission.READ_EXTERNAL_STORAGE"),
            MediaPermissions.required(setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO), sdkInt = 32),
        )
        assertEquals(
            listOf("android.permission.READ_EXTERNAL_STORAGE"),
            MediaPermissions.required(setOf(MediaKind.IMAGE), sdkInt = 26),
        )
    }

    @Test
    fun `an empty kind set asks for every media permission`() {
        assertEquals(
            listOf(
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
            ),
            MediaPermissions.required(emptySet(), sdkInt = 34),
        )
    }

    @Test
    fun `playback file extensions follow the container`() {
        assertEquals(".mp4", MediaPlaybackFiles.extensionFor("video/mp4"))
        assertEquals(".mov", MediaPlaybackFiles.extensionFor("video/QuickTime; codecs=hvc1"))
        assertEquals(".mp3", MediaPlaybackFiles.extensionFor("audio/mpeg"))
        assertEquals(".m4a", MediaPlaybackFiles.extensionFor("audio/mp4"))
        assertEquals(".bin", MediaPlaybackFiles.extensionFor("application/octet-stream"))
        assertEquals(".bin", MediaPlaybackFiles.extensionFor(null))
    }
}
