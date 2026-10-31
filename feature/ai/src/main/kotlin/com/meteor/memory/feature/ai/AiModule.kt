package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.Relation
import java.time.Instant

/**
 * FROZEN INTERFACE (Meteor archive §8). Do not modify without an ADR.
 *
 * The AI domain runs on the user's own provider credentials (ADR-024): skills
 * shape the prompt, a bounded read-only agent reads the archive, and the only
 * persisted artifact is the persona record. Nothing here logs content or keeps
 * chat history — the caller holds history in memory.
 */
interface AiModule {

    /** Bundled skills, in declaration order. */
    suspend fun skills(): List<AiSkill>

    /** One person's derived view; never performs network I/O. */
    suspend fun persona(personId: String): PersonaSnapshot

    /** Distills one person with the chosen skill and persists the result. */
    suspend fun distill(request: DistillRequest): DistillResult

    /** Talks with the archive or with one distilled persona. */
    suspend fun chat(request: ChatRequest): ChatReply

    /** Local retrieval answer; works with AI disabled and is also a chat tool. */
    suspend fun ask(request: AskRequest): AskAnswer

    /** Cheap `/models` probe for the settings "test connection" button. */
    suspend fun probe(config: AiConfig): ProbeResult
}

/** One recurring term of a person's records. */
data class PersonaTopic(val term: String, val count: Int)

/** One memorable line, kept with the moment it was written. */
data class PersonaQuote(val at: Instant, val text: String)

/**
 * Derived picture of one person.
 *
 * [body] holds the model-written layered persona after a distillation; it is
 * empty when only the local statistics exist. [provider] and [skill] record
 * what produced the artifact, so a later refresh is attributable.
 */
data class PersonaSnapshot(
    val personId: String,
    val displayName: String?,
    val relation: Relation?,
    val recordCount: Int,
    val diaryMentions: Int,
    val firstSeen: Instant?,
    val lastSeen: Instant?,
    val topics: List<PersonaTopic> = emptyList(),
    val quotes: List<PersonaQuote> = emptyList(),
    val generatedAt: Instant,
    val savedAt: Instant? = null,
    val body: String = "",
    val provider: String? = null,
    val skill: String? = null,
) {
    val distilled: Boolean get() = body.isNotBlank()
}

/** One question about the archive. */
data class AskRequest(
    val question: String,
    val personId: String? = null,
    val maxHits: Int = 5,
)

/** One retrieved record. [snippet] is a verbatim window of the stored body. */
data class AskMatch(
    val at: Instant,
    val type: RecordType,
    val personId: String?,
    val snippet: String,
    val score: Double,
    /** Archive-relative id, usable with the `read_record` tool. */
    val recordId: String = "",
)

data class AskAnswer(
    val question: String,
    val consultedRecords: Int,
    val matches: List<AskMatch>,
)

/** Called only after the user confirms what will be sent. */
data class DistillRequest(
    val personId: String,
    val skillName: String,
    val config: AiConfig,
)

data class DistillResult(
    val snapshot: PersonaSnapshot,
    val steps: Int,
)

/** One previous chat turn. History lives in the caller's memory only. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * [personId] null = talk to the whole archive (grounded Q&A skill);
 * non-null = talk with that person's persona and only their records.
 */
data class ChatRequest(
    val skillName: String,
    val question: String,
    val personId: String? = null,
    val history: List<ChatTurn> = emptyList(),
    val config: AiConfig,
)

data class ChatReply(val text: String, val steps: Int)
