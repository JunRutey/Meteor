package com.meteor.memory.ui.search

import com.meteor.memory.core.storage.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `:core-index` excluded from the build (archive R3).
 *
 * Search returns nothing and indexing is a no-op — the rest of the app keeps
 * working on the files in `:core-storage`, which stay the source of truth.
 */
@Singleton
class NoOpSearchService @Inject constructor() : SearchService {

    override suspend fun search(query: String, maxHits: Int, snippetChars: Int): List<SearchMatch> =
        emptyList()

    override suspend fun rebuild() = Unit

    override suspend fun summary(): SearchIndexSummary? = null

    override suspend fun indexDocument(document: Document) = Unit

    override suspend fun rebuildOnLaunch() = Unit

    override suspend fun recordsForPerson(personId: String): List<PersonRecord> = emptyList()
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
object SearchModule {

    @dagger.Provides
    @Singleton
    fun provideSearchService(service: NoOpSearchService): SearchService = service
}
