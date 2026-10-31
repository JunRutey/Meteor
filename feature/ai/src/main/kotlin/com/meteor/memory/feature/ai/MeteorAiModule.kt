package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Read/write seam for the persisted persona snapshot. */
interface PersonaStore {
    fun read(personId: String): PersonaSnapshot?

    fun write(snapshot: PersonaSnapshot)
}

/**
 * Default store: an ordinary encrypted record at
 * `数字遗产/数字人格/<person_id>/persona.md` (archive §4), written through the
 * frozen [StorageRouter] so it carries normal frontmatter and joins backups
 * without any special casing.
 */
class FilePersonaStore(private val router: StorageRouter) : PersonaStore {

    override fun read(personId: String): PersonaSnapshot? {
        val file = router.route(personId, RecordType.PERSONA, LocalDate.now())
        if (!file.isFile) return null
        val document = runCatching { router.read(file) }.getOrNull() ?: return null
        return PersonaCodec.decode(personId, document.body)?.copy(savedAt = document.timestamp)
    }

    override fun write(snapshot: PersonaSnapshot) {
        val file = router.route(snapshot.personId, RecordType.PERSONA, LocalDate.now())
        router.write(
            file,
            Document(
                file = file,
                personId = snapshot.personId,
                type = RecordType.PERSONA,
                timestamp = snapshot.generatedAt,
                source = RecordSource.METEOR_AI,
                body = PersonaCodec.encode(snapshot),
            ),
        )
    }
}

/**
 * The v0.6 implementation of the frozen [AiModule] (ADR-027).
 *
 * It owns three things: local retrieval (works with AI off), the skill library,
 * and the bounded read-only agent used for distillation and chat. The provider
 * is created per call from the caller-supplied [AiConfig], so changing the key
 * or model never needs a restart. Nothing here logs a prompt or a response.
 */
class MeteorAiModule(
    private val rootDir: File,
    private val scanner: RecordScanner,
    private val personQueries: PersonQueries,
    private val moodQueries: MoodQueries,
    private val diaryQueries: DiaryQueries,
    private val personaStore: PersonaStore,
    private val skillLoader: (String) -> String?,
    private val providerFactory: (AiConfig) -> AiProvider = { OpenAiCompatibleProvider(it) },
    private val now: () -> Instant = { Instant.now() },
) : AiModule {

    private val library = SkillLibrary(skillLoader)

    override suspend fun skills(): List<AiSkill> = library.all()

    override suspend fun persona(personId: String): PersonaSnapshot = withContext(Dispatchers.IO) {
        val stored = personaStore.read(personId)
        PersonaEngine.snapshot(
            personId = personId,
            profile = personQueries.read(personId),
            corpus = scanner.scan(),
            generatedAt = now(),
            savedAt = stored?.savedAt,
        ).copy(
            body = stored?.body.orEmpty(),
            provider = stored?.provider,
            skill = stored?.skill,
        )
    }

    override suspend fun distill(request: DistillRequest): DistillResult {
        val skill = library.find(request.skillName)?.takeIf { it.stage == SkillStage.DISTILL }
            ?: throw AiProviderException(0, "unknown-skill")
        requireConfigured(request.config)

        val snapshot = persona(request.personId)
        val profile = withContext(Dispatchers.IO) { personQueries.read(request.personId) }

        val system = buildString {
            append(skill.body)
            append("\n\nCurrent date: ").append(LocalDate.now()).append('.')
            append(" Target person_id: ").append(request.personId).append('.')
        }
        val user = distillPrompt(request.personId, profile?.displayName, snapshot)
        val tools = archiveTools(writeEnabled = false)
        val result = withContext(Dispatchers.IO) {
            agent(request.config, tools).run(
                conversation = listOf(AiMessage.system(system), AiMessage.user(user)),
                allowedPersonId = request.personId,
            )
        }

        val generatedAt = now()
        val distilled = snapshot.copy(
            body = result.content,
            provider = request.config.provider,
            skill = skill.name,
            generatedAt = generatedAt,
            savedAt = generatedAt,
        )
        withContext(Dispatchers.IO) { personaStore.write(distilled) }
        return DistillResult(distilled, result.steps)
    }

    override suspend fun chat(request: ChatRequest): ChatReply = chatInternal(request, listener = null)

    override suspend fun chatStreaming(request: ChatRequest, listener: AiChatListener): ChatReply =
        chatInternal(request, listener)

    private suspend fun chatInternal(request: ChatRequest, listener: AiChatListener?): ChatReply {
        val skill = library.find(request.skillName)?.takeIf { it.stage == SkillStage.CHAT }
            ?: throw AiProviderException(0, "unknown-skill")
        requireConfigured(request.config)
        if (skill.requiresPerson && request.personId.isNullOrBlank()) {
            throw AiProviderException(0, "person-required")
        }

        val persona = request.personId?.let { id ->
            withContext(Dispatchers.IO) { personaStore.read(id) }
        }

        // Time questions are answered from a calendar window chosen locally,
        // not by hoping the model guesses a date. Other questions fall back to
        // keyword retrieval. Either way only a few short excerpts are sent.
        val today = LocalDate.now()
        val window = TimePhrase.parse(request.question, today)
        val tools = archiveTools(writeEnabled = request.config.writeEnabled)
        val prefetched = withContext(Dispatchers.IO) {
            if (window != null) {
                tools.dateContext(window, request.personId, PREFETCH_HITS, PREFETCH_CHARS)
            } else {
                tools.searchContext(request.question, request.personId, PREFETCH_HITS, PREFETCH_CHARS)
            }
        }
        val overview = withContext(Dispatchers.IO) { tools.archiveOverview() }

        val system = buildString {
            append(skill.body)
            append("\n\nCurrent date: ").append(today)
            append(" (").append(today.dayOfWeek).append("), time zone ")
                .append(java.util.TimeZone.getDefault().id).append('.')
            append("\nArchive facts: ").append(overview).append('.')
            append("\nFor time questions use read_diary(date) for a single day, list_diary_dates to list ")
            append("which days exist, list_records(from,to) for a span, and read_moods(days=N) for moods. ")
            append("Never claim a day is empty before checking these tools.")
            if (persona != null && persona.body.isNotBlank()) {
                append("\n\n# Persona file\n\n").append(persona.body.take(MAX_PERSONA_CHARS))
            } else if (!request.personId.isNullOrBlank()) {
                append("\n\nNo distilled persona exists yet for person_id=")
                    .append(request.personId)
                    .append(". Answer from the archive only; if asked to role-play, say the persona is not distilled yet.")
            }
            if (request.config.writeEnabled) {
                append("\n\nWrite tools are enabled: save_diary, append_diary, set_mood, ")
                append("create_person, update_person. State the exact date and change in your reply, ")
                append("and never overwrite or delete anything the user did not ask for.")
            } else {
                append("\n\nTools are read-only; never claim to have changed anything.")
            }
            if (prefetched.isNotBlank()) {
                append("\n\n# Pre-retrieved archive context\n\n")
                append(prefetched)
                if (window != null) {
                    append("\n\nThis block is the requested time window, retrieved locally. ")
                    append("Answer from it and cite dates; use read_record only for a fuller passage.")
                } else {
                    append("\n\nThis block was retrieved locally by keywords from the question. ")
                    append("Prefer it and cite dates. Call read_record (with its record_id) only when you need a fuller passage.")
                }
            }
        }

        val messages = mutableListOf(AiMessage.system(system))
        request.history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
            messages += if (turn.fromUser) {
                AiMessage.user(turn.text)
            } else {
                AiMessage(AiRole.ASSISTANT, turn.text)
            }
        }
        messages += AiMessage.user(request.question)

        val startedAt = System.nanoTime()
        val result = withContext(Dispatchers.IO) {
            agent(request.config, tools, listener)
                .run(conversation = messages, allowedPersonId = request.personId)
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        return ChatReply(result.content, result.steps, result.usage, elapsedMs)
    }

    override suspend fun ask(request: AskRequest): AskAnswer = withContext(Dispatchers.IO) {
        AskEngine.answer(request, scanner.scan())
    }

    override suspend fun probe(config: AiConfig): ProbeResult = withContext(Dispatchers.IO) {
        providerFactory(config).probe()
    }

    private fun archiveTools(writeEnabled: Boolean): ArchiveTools = ArchiveTools(
        rootDir = rootDir,
        scanner = scanner,
        personQueries = personQueries,
        moodQueries = moodQueries,
        diaryQueries = diaryQueries,
        writeEnabled = writeEnabled,
    )

    private fun agent(
        config: AiConfig,
        tools: ArchiveTools,
        listener: AiChatListener? = null,
    ): AiAgent = AiAgent(
        provider = providerFactory(config),
        tools = tools,
        listener = listener,
    )

    private fun requireConfigured(config: AiConfig) {
        if (!config.isConfigured()) throw AiProviderException(0, "not-configured")
    }

    private fun distillPrompt(personId: String, name: String?, snapshot: PersonaSnapshot): String = buildString {
        append("Distill the persona for person_id=").append(personId).append(".\n")
        append("Known name: ").append(name ?: "(unknown)").append('\n')
        append("Local statistics: records=").append(snapshot.recordCount)
        append(", diary_mentions=").append(snapshot.diaryMentions).append('\n')
        snapshot.firstSeen?.let { append("First seen: ").append(it).append('\n') }
        snapshot.lastSeen?.let { append("Last seen: ").append(it).append('\n') }
        if (snapshot.topics.isNotEmpty()) {
            append("Frequent terms: ")
                .append(snapshot.topics.joinToString { "${it.term}(${it.count})" })
                .append('\n')
        }
        append("Read the archive with the tools before writing. Output only the Markdown persona body.")
    }

    private companion object {
        const val MAX_PERSONA_CHARS = 12_000
        const val MAX_HISTORY_TURNS = 12

        /** Pre-fetch budget per question: four short windows, ~1.8k characters. */
        const val PREFETCH_HITS = 4
        const val PREFETCH_CHARS = 1800
    }
}
