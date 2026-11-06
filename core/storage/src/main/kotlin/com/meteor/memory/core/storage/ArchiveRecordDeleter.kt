package com.meteor.memory.core.storage

import java.time.LocalDate

enum class ArchiveDeleteResult {
    DELETED,
    NOT_FOUND,
    FAILED,
}

/**
 * Narrow deletion surface for calendar records. It deliberately offers no
 * arbitrary path or record-id operation.
 */
interface ArchiveRecordDeleter {
    fun deleteDiary(date: LocalDate): ArchiveDeleteResult

    fun deleteMood(date: LocalDate): ArchiveDeleteResult
}

class PrivateArchiveRecordDeleter(
    private val router: StorageRouter,
) : ArchiveRecordDeleter {

    override fun deleteDiary(date: LocalDate): ArchiveDeleteResult =
        delete(RecordType.DIARY, date)

    override fun deleteMood(date: LocalDate): ArchiveDeleteResult =
        delete(RecordType.MOOD, date)

    private fun delete(type: RecordType, date: LocalDate): ArchiveDeleteResult {
        val file = router.route(personId = null, type = type, date = date)
        if (!file.isFile) return ArchiveDeleteResult.NOT_FOUND
        return if (file.delete()) ArchiveDeleteResult.DELETED else ArchiveDeleteResult.FAILED
    }
}
