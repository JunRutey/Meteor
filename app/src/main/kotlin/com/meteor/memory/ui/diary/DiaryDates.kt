package com.meteor.memory.ui.diary

import com.meteor.memory.core.storage.Document
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一篇日记有两个时刻：它属于哪一天（文件名上的 [diaryDay]），以及它是
 * 什么时候真正写下的。
 *
 * 当天写的日记，主日期就是那天加写入时刻；**补写**（写于另一天）时主日期
 * 只剩那天本身，写入时刻挪到「记录于」一行——写下的时刻在页头永远只出现
 * 一次（v1.4 反馈：补写后页头显示的是编辑那天，且小字日期重复）。
 */
internal object DiaryDates {

    /** 写下这份记录的时刻：正文里的 `meteor-sig` 印记，没有印记则取时间戳。 */
    fun writtenAt(doc: Document): Instant = doc.signedAt ?: doc.timestamp

    /** [writtenAt] 是否落在与 [diaryDay] 不同的一天——即这篇是补写的。 */
    fun isBackfilled(
        diaryDay: LocalDate,
        writtenAt: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean = writtenAt.atZone(zone).toLocalDate() != diaryDay
}
