package com.meteor.memory.feature.ai

/**
 * What a single tool needs. Every tool in [ArchiveTools] is classified here;
 * nothing is granted implicitly, and an unclassified tool is never dispatched.
 */
enum class AiToolCapability {
    /** Read records, people, moods and attachment metadata — never gated. */
    READ_ARCHIVE,

    /** Create or overwrite diary entries and moods. */
    WRITE_DIARY,

    /** Create, update or delete person profiles. */
    WRITE_PEOPLE,

    /** Create questions or add answers (ADR-075). */
    WRITE_QUESTION,
}

/**
 * The capability set granted to one agent run.
 *
 * ADR-030 splits the former single write switch into two independent grants, so
 * a user can let the model write diary entries without also letting it rewrite
 * the address book. Both are still nested under the master switch in [AiConfig];
 * with the master off this type is always [READ_ONLY].
 *
 * ADR-075 adds question writing as its own capability, but derives it from the
 * diary grant: writing a question is the same class of act as writing a diary
 * entry, so reusing that consent avoids a new preference and a backup-format
 * change while keeping the capability separate for a future split.
 *
 * Reads are not gated: a chat without them is useless, and the prompt the user
 * already consented to is the only place they end up.
 */
data class AiToolPermissions(
    val writeDiary: Boolean = false,
    val writePeople: Boolean = false,
    val writeQuestion: Boolean = false,
) {

    fun allows(capability: AiToolCapability): Boolean = when (capability) {
        AiToolCapability.READ_ARCHIVE -> true
        AiToolCapability.WRITE_DIARY -> writeDiary
        AiToolCapability.WRITE_PEOPLE -> writePeople
        AiToolCapability.WRITE_QUESTION -> writeQuestion
    }

    companion object {
        val READ_ONLY = AiToolPermissions()
    }
}
