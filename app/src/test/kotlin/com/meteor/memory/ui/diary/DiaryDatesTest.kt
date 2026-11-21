package com.meteor.memory.ui.diary

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 补写判定与写入时刻的取值规则。
 *
 * 页头怎么排这两行就靠这两个函数：判错一天，补写的日记就会拿编辑那天当主日期。
 */
class DiaryDatesTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `write moment prefers the signature stamp and falls back to the timestamp`() {
        val stamp = Instant.parse("2026-11-21T07:30:00Z")
        val stamped = document(timestamp = Instant.parse("2026-11-20T00:00:00Z"), signedAt = stamp)
        assertEquals(stamp, DiaryDates.writtenAt(stamped))

        val unsigned = document(timestamp = stamp, signedAt = null)
        assertEquals(stamp, DiaryDates.writtenAt(unsigned))
    }

    @Test
    fun `a diary written on its own day is not back filled`() {
        // 23:59:59 仍然属于那一天。
        val writtenAt = Instant.parse("2026-11-03T15:59:59Z") // +08 → 23:59:59
        assertFalse(DiaryDates.isBackfilled(LocalDate.parse("2026-11-03"), writtenAt, zone))
    }

    @Test
    fun `a diary written on another day is back filled`() {
        val writtenAt = Instant.parse("2026-11-21T07:30:00Z")
        assertTrue(DiaryDates.isBackfilled(LocalDate.parse("2026-11-03"), writtenAt, zone))
    }

    @Test
    fun `crossing midnight makes the entry back filled`() {
        val writtenAt = Instant.parse("2026-11-03T16:00:01Z") // +08 → 11-04 00:00:01
        assertTrue(DiaryDates.isBackfilled(LocalDate.parse("2026-11-03"), writtenAt, zone))
    }

    @Test
    fun `back fill is decided in the local zone, not UTC`() {
        // UTC 看还是 11-03，上海已经是 11-04：补写与否必须按用户所在时区。
        val writtenAt = Instant.parse("2026-11-03T16:30:00Z")
        assertTrue(DiaryDates.isBackfilled(LocalDate.parse("2026-11-03"), writtenAt, zone))
        assertFalse(
            DiaryDates.isBackfilled(
                LocalDate.parse("2026-11-03"),
                writtenAt,
                ZoneId.of("UTC"),
            ),
        )
    }

    private fun document(timestamp: Instant, signedAt: Instant?): Document = Document(
        file = File("2026-11-03.md"),
        personId = null,
        type = RecordType.DIARY,
        timestamp = timestamp,
        source = RecordSource.USER,
        body = "",
        signedAt = signedAt,
    )
}
