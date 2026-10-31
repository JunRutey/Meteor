package com.meteor.memory.ui.search

import android.content.Context
import com.meteor.memory.core.index.IndexModule
import com.meteor.memory.core.index.IndexUpdater
import com.meteor.memory.core.index.MeteorIndex
import com.meteor.memory.core.index.QueryPlan
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordScanner
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
) : SearchService {

    private val rebuildMutex = Mutex()

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var blockedUntilMillis = 0L

    override suspend fun search(query: String, maxHits: Int, snippetChars: Int): List<SearchMatch> {
        if (query.isBlank()) return emptyList()
        return index.search(
            QueryPlan(ftsExpression = query, maxHits = maxHits, snippetChars = snippetChars),
        ).map { hit ->
            SearchMatch(
                recordId = hit.recordId,
                type = hit.type,
                timestamp = hit.timestamp,
                snippet = hit.snippet,
            )
        }
    }

    override suspend fun rebuild() {
        index.rebuild()
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
        index.upsert(document)
    }

    override suspend fun recordsForPerson(personId: String): List<PersonRecord> =
        index.queryByPerson(personId).map { record ->
            PersonRecord(
                recordId = record.recordId,
                type = record.type,
                timestamp = record.timestamp,
            )
        }

    override suspend fun rebuildOnLaunch() {
        if (System.currentTimeMillis() < blockedUntilMillis) {
            appLog.info(TAG, "index rebuild skipped (backoff active)")
            return
        }

        rebuildMutex.withLock {
            if (System.currentTimeMillis() < blockedUntilMillis) return

            runCatching { index.rebuild() }.fold(
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
        scanner = scanner,
    )

    @dagger.Provides
    @Singleton
    fun provideSearchService(
        index: MeteorIndex,
        appLog: AppLog,
    ): SearchService = IndexSearchService(index, appLog)

    @dagger.Provides
    @Singleton
    fun provideIndexModule(index: MeteorIndex): IndexModule = index

    @dagger.Provides
    @Singleton
    fun provideIndexUpdater(index: MeteorIndex): IndexUpdater = index
}
