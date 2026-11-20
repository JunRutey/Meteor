package com.meteor.memory.ui.search

import android.content.Context
import com.meteor.memory.core.index.IndexModule
import com.meteor.memory.core.index.IndexUpdater
import com.meteor.memory.core.index.MeteorIndex
import com.meteor.memory.core.index.QueryPlan
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.feature.calendar.R as CalendarR
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:core-index` present. Delegates to [MeteorIndex] and owns the launch-time
 * rebuild policy (archive §9) with exponential backoff.
 */
@Singleton
class IndexSearchService @Inject constructor(
    private val index: MeteorIndex,
    private val appLog: AppLog,
    private val attachmentTextIndex: AttachmentTextIndex,
    @ApplicationContext private val context: Context,
) : SearchService {

    private val rebuildMutex = Mutex()

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var blockedUntilMillis = 0L

    override suspend fun search(query: String, maxHits: Int, snippetChars: Int): List<SearchMatch> {
        if (query.isBlank()) return emptyList()
        val recordHits = index.search(
            QueryPlan(ftsExpression = query, maxHits = maxHits, snippetChars = snippetChars),
        ).map { hit ->
            SearchMatch(
                recordId = hit.recordId,
                type = hit.type,
                timestamp = hit.timestamp,
                snippet = hit.snippet,
                sourcePath = hit.sourcePath,
                score = hit.score,
            )
        }
        val attachmentHits = attachmentTextIndex.search(query, maxHits, snippetChars)
        return (recordHits + attachmentHits)
            .sortedWith(compareByDescending<SearchMatch> { it.score }.thenByDescending { it.timestamp })
            .take(maxHits.coerceAtLeast(1))
    }

    override suspend fun rebuild() {
        index.rebuild()
        attachmentTextIndex.rebuild()
    }

    override suspend fun summary(): SearchIndexSummary? = runCatching {
        val overview = index.overview()
        SearchIndexSummary(
            records = overview.typeCounts.values.sum(),
            types = overview.typeCounts.size,
            persons = overview.persons.size,
        )
    }.getOrNull()

    override suspend fun indexDocument(document: Document) {
        index.upsert(searchableDocument(document) { mood -> context.getString(mood.labelRes()) })
    }

    override suspend fun removeRecord(sourcePath: String) {
        index.remove(sourcePath)
    }

    override suspend fun recordsForPerson(personId: String): List<PersonRecord> =
        index.queryByPerson(personId).map { record ->
            PersonRecord(
                recordId = record.recordId,
                type = record.type,
                timestamp = record.timestamp,
            )
        }

    override suspend fun refreshAttachmentIndex() {
        attachmentTextIndex.rebuild()
    }

    override suspend fun clearAttachmentIndex() {
        attachmentTextIndex.clear()
    }

    override suspend fun rebuildOnLaunch() {
        if (System.currentTimeMillis() < blockedUntilMillis) {
            appLog.info(TAG, "index rebuild skipped (backoff active)")
            return
        }

        rebuildMutex.withLock {
            if (System.currentTimeMillis() < blockedUntilMillis) return

            runCatching {
                index.rebuild()
                attachmentTextIndex.rebuild()
            }.fold(
                onSuccess = {
                    consecutiveFailures = 0
                    blockedUntilMillis = 0
                    appLog.info(TAG, "index rebuild ok")
                },
                onFailure = { throwable ->
                    consecutiveFailures++
                    val backoff = backoffFor(consecutiveFailures)
                    blockedUntilMillis = System.currentTimeMillis() + backoff
                    appLog.error(
                        TAG,
                        "index rebuild failed (attempt=$consecutiveFailures backoff=${backoff}ms)",
                        throwable,
                    )
                },
            )
        }
    }

    private fun backoffFor(failures: Int): Long {
        val exponent = (failures - 1).coerceIn(0, MAX_EXPONENT)
        return (BASE_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }

    private companion object {
        const val TAG = "Index"
        const val BASE_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_EXPONENT = 6
    }
}

/**
 * Full-text view of one record.
 *
 * A mood is stored as stable codes (`happy`) instead of the word the user
 * sees, so indexing the raw body would make `开心` unsearchable. [labelOf]
 * resolves every code through resources; only the index copy is replaced, the
 * archive record itself never changes. A day can carry several moods, so all
 * labels are indexed. Other record types are indexed as-is.
 */
internal fun searchableDocument(document: Document, labelOf: (Mood) -> String): Document {
    if (document.type != RecordType.MOOD) return document
    val moods = Mood.decode(document.body)
    if (moods.isEmpty()) return document
    return document.copy(body = moods.joinToString(" ") { labelOf(it) })
}

private fun Mood.labelRes(): Int = when (this) {
    Mood.HAPPY -> CalendarR.string.mood_happy
    Mood.CALM -> CalendarR.string.mood_calm
    Mood.EXCITED -> CalendarR.string.mood_excited
    Mood.TIRED -> CalendarR.string.mood_tired
    Mood.SAD -> CalendarR.string.mood_sad
    Mood.ANGRY -> CalendarR.string.mood_angry
    Mood.EMM -> CalendarR.string.mood_emm
}

/** Kept next to the implementation so the wiring disappears with the module. */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object SearchModule {

    @dagger.Provides
    @Singleton
    fun provideMeteorIndex(
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
        scanner: RecordScanner,
    ): MeteorIndex = MeteorIndex(
        context = context,
        rootDir = File(context.filesDir, com.meteor.memory.core.storage.StorageLayout.ROOT_NAME),
        // A launch rebuild walks the scanner, so enrich there as well as in
        // indexDocument() — both paths must feed the same searchable text.
        scanner = RecordScanner {
            scanner.scan().map { document ->
                searchableDocument(document) { mood -> context.getString(mood.labelRes()) }
            }
        },
    )

    @dagger.Provides
    @Singleton
    fun provideSearchService(
        index: MeteorIndex,
        appLog: AppLog,
        attachmentTextIndex: AttachmentTextIndex,
        @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
    ): SearchService = IndexSearchService(index, appLog, attachmentTextIndex, context)

    @dagger.Provides
    @Singleton
    fun provideIndexModule(index: MeteorIndex): IndexModule = index

    @dagger.Provides
    @Singleton
    fun provideIndexUpdater(index: MeteorIndex): IndexUpdater = index
}
