package com.meteor.memory.core.index

import com.meteor.memory.core.storage.RecordType
import java.time.Instant
import java.time.LocalDate

/**
 * FROZEN INTERFACE (Meteor archive §8). Do not modify without an ADR.
 *
 * The index is a derived cache: every byte can be rebuilt from the Markdown
 * files in `:core-storage`, so losing the database is never data loss.
 */
interface IndexModule {
    suspend fun search(plan: QueryPlan): List<SearchHit>
    suspend fun overview(): IndexOverview
    suspend fun queryByPerson(personId: String): List<Record>
    suspend fun rebuild()
}

data class QueryPlan(
    val ftsExpression: String,
    val language: String = "zh",
    val personIds: List<String> = emptyList(),
    val timeRange: ClosedRange<LocalDate>? = null,
    val types: List<RecordType> = emptyList(),
    val maxHits: Int = 5,
    val snippetChars: Int = 500,
)

data class SearchHit(
    val recordId: String,
    val personId: String?,
    val type: RecordType,
    val timestamp: Instant,
    val snippet: String,
    val score: Double,
    val sourcePath: String,
)

data class IndexOverview(
    val persons: List<PersonBrief>,
    val typeCounts: Map<RecordType, Int>,
    val timeSpan: ClosedRange<LocalDate>?,
)

/**
 * One person aggregated from the files.
 *
 * [displayName] is filled once a profile record is decrypted during indexing;
 * it stays null when no `profile.md` exists yet, which is why the frozen
 * contract allows it to be absent.
 */
data class PersonBrief(
    val personId: String,
    val displayName: String?,
    val recordCount: Int,
    val lastActive: Instant?,
)

/** A record reference returned by [IndexModule.queryByPerson]. */
data class Record(
    val recordId: String,
    val personId: String?,
    val type: RecordType,
    val timestamp: Instant,
    val sourcePath: String,
)
