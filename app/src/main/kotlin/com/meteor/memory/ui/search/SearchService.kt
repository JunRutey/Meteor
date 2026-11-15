package com.meteor.memory.ui.search

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordType
import java.time.Instant

/** One search hit, in app-owned types so the UI never depends on `:core-index`. */
data class SearchMatch(
    val recordId: String,
    val type: RecordType,
    val timestamp: Instant,
    val snippet: String,
    /**
     * Archive-relative path of the record, e.g.
     * `时光轴/心情/日记/2026/2026-11-15.md`.
     *
     * The timestamp is only the last save time, so a diary back-filled for an
     * older day must be addressed by the date in this path. Empty for in-memory
     * attachment hits, which have no archive record.
     */
    val sourcePath: String = "",
    val score: Double = 0.0,
)

data class SearchIndexSummary(
    val records: Int,
    val types: Int,
    val persons: Int,
)

/** One record belonging to a person, used by the people overview. */
data class PersonRecord(
    val recordId: String,
    val type: RecordType,
    val timestamp: Instant,
)

/**
 * Search seam owned by `:app`.
 *
 * Everything above this interface — search UI, diagnostics, diary saving — is
 * written against these app-owned types instead of `:core-index`'s, so the index
 * backend can change without the UI learning about it.
 */
interface SearchService {
    suspend fun search(query: String, maxHits: Int = 20, snippetChars: Int = 160): List<SearchMatch>

    suspend fun rebuild()

    suspend fun summary(): SearchIndexSummary?

    /** Incremental index update after a record is saved. */
    suspend fun indexDocument(document: Document)

    /**
     * Drops one record from the index, addressed by its archive-relative path.
     */
    suspend fun removeRecord(sourcePath: String)

    /** Launch-time full rebuild used as the safety net for incremental updates. */
    suspend fun rebuildOnLaunch()

    /** Records aggregated under one `person_id`, newest first. */
    suspend fun recordsForPerson(personId: String): List<PersonRecord>

    /** Rebuilds the in-memory text index for encrypted file attachments. */
    suspend fun refreshAttachmentIndex()

    /** Drops decrypted attachment text from memory when the app is backgrounded. */
    suspend fun clearAttachmentIndex()
}
