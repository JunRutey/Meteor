package com.meteor.memory.ui.search

import com.meteor.memory.core.index.CjkTokenizer
import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StoredAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searchable attachment text stays encrypted at rest. The in-memory index is
 * reconstructed from encrypted sidecars and is never written to SQLite.
 */
@Singleton
class AttachmentTextIndex @Inject constructor(
    private val attachmentStore: AttachmentStore,
) {

    private val mutex = Mutex()

    @Volatile
    private var indexedAttachments: List<IndexedAttachment>? = null

    suspend fun rebuild() = withContext(Dispatchers.IO) {
        mutex.withLock {
            indexedAttachments = attachmentStore.list()
                .mapNotNull { attachment ->
                    attachmentStore.readIndexedText(attachment.id)?.let { text ->
                        IndexedAttachment(
                            attachment = attachment,
                            terms = CjkTokenizer.tokenize(text).toSet(),
                        )
                    }
                }
        }
    }

    suspend fun search(query: String, maxHits: Int, snippetChars: Int): List<SearchMatch> =
        withContext(Dispatchers.IO) {
        val queryTerms = CjkTokenizer.tokenize(query).distinct()
        if (queryTerms.isEmpty()) return@withContext emptyList()

        ensureLoaded()
        indexedAttachments.orEmpty()
            .mapNotNull { indexed ->
                val score = queryTerms.count(indexed.terms::contains)
                if (score == 0) return@mapNotNull null
                val text = attachmentStore.readIndexedText(indexed.attachment.id)
                    ?: return@mapNotNull null
                SearchMatch(
                    recordId = indexed.attachment.id,
                    type = RecordType.CHAT_LOG,
                    timestamp = indexed.attachment.modifiedAt,
                    snippet = snippet(text, queryTerms, snippetChars),
                    score = score.toDouble(),
                )
            }
            .sortedWith(
                compareByDescending<SearchMatch> { it.score }
                    .thenByDescending { it.timestamp },
            )
            .take(maxHits.coerceAtLeast(1))
    }

    suspend fun clear() = mutex.withLock {
        indexedAttachments = null
    }

    private suspend fun ensureLoaded() {
        if (indexedAttachments != null) return
        mutex.withLock {
            if (indexedAttachments == null) {
                indexedAttachments = withContext(Dispatchers.IO) {
                    attachmentStore.list()
                        .mapNotNull { attachment ->
                            attachmentStore.readIndexedText(attachment.id)?.let { text ->
                                IndexedAttachment(
                                    attachment = attachment,
                                    terms = CjkTokenizer.tokenize(text).toSet(),
                                )
                            }
                        }
                }
            }
        }
    }

    private fun snippet(text: String, terms: List<String>, maxChars: Int): String {
        val limit = maxChars.coerceAtLeast(0)
        val lower = text.lowercase()
        val start = terms.asSequence()
            .map { term -> lower.indexOf(term.lowercase()) }
            .filter { it >= 0 }
            .minOrNull()
            ?.let { (it - limit / 4).coerceAtLeast(0) }
            ?: 0
        return text.drop(start).take(limit)
    }

    private data class IndexedAttachment(
        val attachment: StoredAttachment,
        val terms: Set<String>,
    )
}
