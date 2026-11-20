package com.meteor.memory.feature.people

import com.meteor.memory.core.storage.Relation

/**
 * Pure rules for the chips offered by [PersonTagEditor].
 *
 * The chip library used to be "remembered tags + every tag found in every
 * profile". That made a label used by any one person reappear immediately after
 * it was deleted from the library. The library is now scoped:
 *
 * 1. tags the user explicitly remembered in settings, plus
 * 2. tags already stored on the profile currently being edited.
 *
 * Other people's profile labels are still persisted and still render on their
 * own detail/edit pages; they just no longer pollute the shared chip row. A
 * create dialog has no "current person", so it sees only the remembered list.
 *
 * Since ADR-045 the three built-in relation labels are ordinary, deletable tags
 * in that same list. [Relation] and the `relation:` header field survive only as
 * the derived, AI-compatible record of which built-in label is selected.
 */
object PeopleTagLibrary {

    /**
     * Initial selection for a brand-new person: 未分类.
     *
     * The create dialog used to preselect 朋友. That made the default look like a
     * deliberate relationship instead of "not filled in yet", and it made the
     * built-in tag impossible to remove before saving. A new person now starts
     * with no relation and no tags; the UI shows the special 未分类 chip.
     */
    val NEW_PERSON_TAGS: List<String> = emptyList()
    val NEW_PERSON_RELATION: Relation? = null

    /** Merged, trimmed, de-duplicated and sorted labels for one tag row. */
    fun available(
        remembered: Iterable<String>,
        currentPersonTags: Iterable<String> = emptyList(),
    ): List<String> = (remembered + currentPersonTags)
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .toList()

    /**
     * Selected labels for one person, folding a legacy `relation:` back in.
     *
     * Older profiles only carry `relation: friend` and no matching tag. The
     * built-in label is inserted first so the editor can show and delete it like
     * any other tag; once the user saves after removing it, `relation` becomes
     * null as well.
     */
    fun selectedTags(
        tags: Iterable<String>,
        legacyRelationLabel: String?,
    ): List<String> {
        val normalized = tags.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        return if (legacyRelationLabel != null &&
            legacyRelationLabel.isNotBlank() &&
            legacyRelationLabel !in normalized
        ) {
            listOf(legacyRelationLabel) + normalized
        } else {
            normalized
        }
    }

    /**
     * Every chip to offer: available labels plus whatever this profile selects,
     * minus chips that are not deletable.
     *
     * The only excluded label is 未分类: it is an action ("clear the selection"),
     * not a tag, so it must never get a delete affordance or enter the library.
     */
    fun visibleTags(
        available: Iterable<String>,
        selected: Iterable<String>,
        excluded: Iterable<String> = emptyList(),
    ): List<String> {
        val excludedLabels = excluded.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return (available + selected)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in excludedLabels }
            .distinct()
            .toList()
    }

    /**
     * The built-in relation carried by a selection, or null when none is picked.
     *
     * [labels] maps each enum code to its localized label; the caller resolves
     * them from resources. `Relation.entries` order defines the tie-break when
     * several built-in labels are selected (only the first is written to the
     * AI-facing `relation:` header).
     */
    fun relationFor(
        selectedTags: List<String>,
        labels: Map<Relation, String>,
    ): Relation? = Relation.entries.firstOrNull { relation ->
        val label = labels[relation]
        label != null && label in selectedTags
    }
}
