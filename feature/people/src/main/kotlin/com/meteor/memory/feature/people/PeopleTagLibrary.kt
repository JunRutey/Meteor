package com.meteor.memory.feature.people

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
 */
object PeopleTagLibrary {

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
}
