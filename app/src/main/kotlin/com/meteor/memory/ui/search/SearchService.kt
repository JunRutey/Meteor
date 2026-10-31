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
 * Seam that makes `:core-index` removable (archive R3).
 *
 * Everything above this interface — search UI, diagnostics, diary saving — is
 * written against these app-owned types. When the module is excluded from the
 * build, a no-op implementation takes its place and the rest of the app still
 * compiles and runs; only search is degraded.
 */
interface SearchService {
    suspend fun search(query: String, maxHits: Int = 20, snippetChars: Int = 160): List<SearchMatch>

    suspend fun rebuild()

    suspend fun summary(): SearchIndexSummary?

    /** Incremental index update after a record is saved. */
    suspend fun indexDocument(document: Document)

    /** Launch-time full rebuild used as the safety net for incremental updates. */
    suspend fun rebuildOnLaunch()

    /** Records aggregated under one `person_id`, newest first. */
    suspend fun recordsForPerson(personId: String): List<PersonRecord>

    /** Rebuilds the in-memory text index for encrypted file attachments. */
    suspend fun refreshAttachmentIndex() = Unit

    /** Drops decrypted attachment text from memory when the app is backgrounded. */
    suspend fun clearAttachmentIndex() = Unit
}
