// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

/**
 * Evaluate the generated sport-taxonomy rules.
 *
 * Hand-written, unlike the rule table it walks (spec/sport_taxonomy.yaml →
 * SportTaxonomyData.kt). Templating this loop into three languages would be
 * worse to maintain than the drift it prevents. What keeps it honest is
 * spec/fixtures/sport_taxonomy.json: the same input/output corpus runs against
 * the Python, JavaScript, and Kotlin evaluators, so an implementation that
 * diverges fails its own test suite.
 *
 * If you change the matching semantics here, change them in
 * backend/app/spec/taxonomy.py and frontend/src/spec/taxonomy.js in the same
 * commit — the fixtures will tell you if you forgot, but only after the fact.
 */

/** Which normalised field a condition reads. */
enum class Field { SPORT, SUB_SPORT, COMBINED }

/**
 * One test against one field. Exactly one of [matches] or [eq] is set — the
 * generator guarantees it, and the spec tests assert it on the Python side.
 *
 * Named `eq` rather than `equals` because `equals` is inherited from `Any`, and
 * a constructor property by that name is a trap even where it compiles.
 */
data class Condition(
    val field: Field,
    val matches: String? = null,
    val eq: String? = null,
)

/** An `any`/`none` list entry: a bare condition, or several ANDed together. */
sealed interface Entry {
    data class One(val condition: Condition) : Entry
    data class All(val conditions: List<Condition>) : Entry
}

/** Matches when ANY entry in [any] matches and NO entry in [none] does. */
data class Rule(
    val type: String,
    val any: List<Entry>,
    val none: List<Entry> = emptyList(),
)

/** The sport types this taxonomy can produce. */
val sportTypes: List<String> get() = SPORT_TYPE_LIST

private val NON_ALNUM = Regex("[^a-z0-9]")

// Rule patterns are a small fixed set reused across every activity in a list,
// so they are compiled once rather than per call.
private val patternCache = HashMap<String, Regex>()

private fun compiled(pattern: String): Regex =
    patternCache.getOrPut(pattern) { Regex(pattern) }

internal fun normalise(value: String?): String =
    NON_ALNUM.replace((value ?: "").lowercase(), "_")

private class Fields(val sport: String, val subSport: String) {
    val combined: String = "$sport $subSport"

    fun get(field: Field): String = when (field) {
        Field.SPORT -> sport
        Field.SUB_SPORT -> subSport
        Field.COMBINED -> combined
    }
}

private fun Condition.matches(fields: Fields): Boolean {
    val value = fields.get(field)
    return when {
        eq != null -> value == eq
        matches != null -> compiled(matches).containsMatchIn(value)
        // The generator never emits this; treat a malformed rule as
        // non-matching rather than throwing inside a list render.
        else -> false
    }
}

private fun Entry.matches(fields: Fields): Boolean = when (this) {
    is Entry.One -> condition.matches(fields)
    is Entry.All -> conditions.all { it.matches(fields) }
}

/**
 * Classify a FIT sport/sub_sport pair into a Tracks sport type.
 *
 * Rules are evaluated in order and the first match wins — see the ordering
 * notes in spec/sport_taxonomy.yaml before assuming any rule is independent.
 */
fun sportType(sport: String?, subSport: String? = null): String {
    val fields = Fields(normalise(sport), normalise(subSport))

    for (rule in RULES) {
        if (rule.any.none { it.matches(fields) }) continue
        if (rule.none.any { it.matches(fields) }) continue
        return rule.type
    }
    return FALLBACK
}
