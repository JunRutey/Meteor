package com.meteor.memory.core.storage

import java.io.File
import java.time.Instant
import java.util.UUID

/** One row of the people overview, aggregated from the filesystem. */
data class PersonEntry(
    val personId: String,
    val displayName: String?,
    val relation: Relation?,
    val hasProfile: Boolean,
)

/**
 * Person directory access (archive v0.5).
 *
 * Physical layout stays per-person (`记忆档案/人际关系/<person_id>/`); this
 * interface is the "logical aggregation" view the overview page renders.
 */
interface PersonQueries {
    /** Every person directory, sorted by display name when known. */
    fun list(): List<PersonEntry>

    fun read(personId: String): PersonProfile?

    /** Creates the directory + profile and returns the new `person_id`. */
    fun create(displayName: String, relation: Relation?, notes: String = ""): String

    fun update(personId: String, displayName: String, relation: Relation?, notes: String)
}

class PrivatePersonQueries(
    rootDir: File,
    private val router: StorageRouter,
) : PersonQueries {

    private val peopleDir = File(
        rootDir,
        "${StorageLayout.ARCHIVE}/${StorageLayout.RELATIONS}",
    )

    override fun list(): List<PersonEntry> {
        if (!peopleDir.isDirectory) return emptyList()

        return peopleDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && StorageLayout.PERSON_ID_PATTERN.matches(it.name) }
            ?.map { dir ->
                val profile = runCatching { read(dir.name) }.getOrNull()
                PersonEntry(
                    personId = dir.name,
                    displayName = profile?.displayName,
                    relation = profile?.relation,
                    hasProfile = profile != null,
                )
            }
            ?.sortedWith(
                compareBy<PersonEntry> { it.displayName == null }
                    .thenBy { it.displayName.orEmpty() }
                    .thenBy { it.personId },
            )
            ?.toList()
            .orEmpty()
    }

    override fun read(personId: String): PersonProfile? {
        val file = profileFile(personId) ?: return null
        if (!file.isFile) return null
        val body = runCatching { router.read(file).body }.getOrNull() ?: return null
        return PersonProfileCodec.decode(body)
    }

    override fun create(displayName: String, relation: Relation?, notes: String): String {
        // 32 hex chars, satisfies PERSON_ID_PATTERN and stays opaque.
        val personId = UUID.randomUUID().toString().replace("-", "")
        writeProfile(personId, displayName, relation, notes)
        return personId
    }

    override fun update(personId: String, displayName: String, relation: Relation?, notes: String) {
        writeProfile(personId, displayName, relation, notes)
    }

    private fun writeProfile(
        personId: String,
        displayName: String,
        relation: Relation?,
        notes: String,
    ) {
        val file = profileFile(personId) ?: error("illegal person_id: $personId")
        val existing = if (file.isFile) runCatching { router.read(file) }.getOrNull() else null

        router.write(
            file,
            Document(
                file = file,
                personId = personId,
                type = RecordType.PROFILE,
                timestamp = existing?.timestamp ?: Instant.now(),
                source = RecordSource.USER,
                body = PersonProfileCodec.encode(
                    PersonProfile(displayName = displayName, relation = relation, notes = notes),
                ),
            ),
        )
    }

    private fun profileFile(personId: String): File? {
        if (!StorageLayout.PERSON_ID_PATTERN.matches(personId)) return null
        return router.route(personId, RecordType.PROFILE, java.time.LocalDate.now())
    }
}
