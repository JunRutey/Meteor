package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Relation

/**
 * Narrow write seam for the people archive.
 *
 * The AI module only needs "create" and "update" by value; depending on the
 * concrete `PersonQueries` signature here has proven brittle across compiled
 * variants, so the app supplies this adapter where the real interface is in
 * scope. Reads still go through [com.meteor.memory.core.storage.PersonQueries].
 */
interface PersonWriter {
    fun create(displayName: String, relation: Relation?, notes: String): String

    fun update(personId: String, displayName: String, relation: Relation?, notes: String)
}
