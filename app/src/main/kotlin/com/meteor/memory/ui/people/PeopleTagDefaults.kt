package com.meteor.memory.ui.people

import android.content.Context
import com.meteor.memory.feature.people.R as PeopleR

/**
 * The three built-in labels seeded into the remembered people-tag library once
 * (ADR-045).
 *
 * They are resolved from `:feature-people`'s resources on purpose: the settings
 * module stores the strings but must never hardcode localized text, and adding a
 * `feature-settings → feature-people` edge would break the declared module
 * graph. `:app` already depends on both, so it is the place that joins them.
 */
internal fun defaultPeopleTags(context: Context): List<String> = listOf(
    context.getString(PeopleR.string.people_relation_family),
    context.getString(PeopleR.string.people_relation_friend),
    context.getString(PeopleR.string.people_relation_former),
)
