// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import kotlinx.serialization.json.Json

/**
 * Workout archetypes — ports of `strength_plan/archetypes.py` and
 * `flow_archetypes.py`: coach blueprints that name a session, fix its rep
 * schemes per slot, pair supersets, and theme the cooldown.
 *
 * The data is [STRENGTH_ARCHETYPE_FILES]/[FLOW_ARCHETYPE_FILES], the server's
 * JSON embedded verbatim by spec/make_archetype_data.py, and it is validated
 * here the way the server validates it: a malformed file is skipped rather than
 * breaking plan generation, so both sides must skip the *same* files.
 */
internal data class Archetype(
    val key: String,
    val name: String,
    val tagline: String,
    val intent: String,
    val splits: Set<String>,
    val sports: Set<String>?,
    val tierRange: Pair<Int, Int>,
    val phases: Set<String>,
    val schemes: Map<String, Dict>,
    val supersets: List<List<String>>,
    val finisher: String?,
    val cooldownTheme: String?,
) {
    fun applies(splitKey: String, sportFamily: String, tier: Int, stage: String): Boolean {
        if (splits.isNotEmpty() && splitKey !in splits) return false
        if (sports != null && sportFamily !in sports) return false
        if (tier !in tierRange.first..tierRange.second) return false
        if ("any" !in phases && stage !in phases) return false
        return true
    }
}

internal data class FlowArchetype(
    val key: String,
    val name: String,
    val tagline: String,
    val contexts: Set<String>,
    val sports: Set<String>?,
    val themes: Set<String>,
    val closerMuscles: List<String>,
) {
    fun applies(context: String, sportFamily: String): Boolean {
        if (contexts.isNotEmpty() && context !in contexts) return false
        if (sports != null && sportFamily !in sports) return false
        return true
    }

    fun themeScore(theme: String?): Int = if (!theme.isNullOrEmpty() && theme in themes) 1 else 0
}

internal object Archetypes {
    private val VALID_PHASES = setOf("linear", "weekly_undulating", "dup", "any")
    private val VALID_FINISHERS = setOf("plyo", "carry_core", null)
    private val VALID_CONTEXTS = setOf("post_workout", "weekly_mobility")

    @Suppress("UNCHECKED_CAST")
    private fun parse(raw: Dict): Archetype {
        val key = raw["key"]
        val schemes = LinkedHashMap<String, Dict>()
        for (entry in (raw.get("main", emptyList<Any?>()) as List<Dict>)) {
            val slot = entry["slot"] as String
            require(slot in Slots.BY_KEY) { "archetype $key references unknown slot $slot" }
            if (truthy(entry["scheme"])) schemes[slot] = entry["scheme"] as Dict
        }
        val supersets = (raw.get("supersets", emptyList<Any?>()) as List<List<String>>).map { it.toList() }
        for (pair in supersets) for (slot in pair) require(slot in Slots.BY_KEY) { "unknown superset slot $slot" }
        val phases = strList(raw.get("phases", listOf("any"))).toSet()
        require(VALID_PHASES.containsAll(phases)) { "archetype $key has invalid phases" }
        val finisher = raw["finisher"] as String?
        require(finisher in VALID_FINISHERS) { "archetype $key has invalid finisher" }
        // Strength sessions carry no stretches; see archetypes.py.
        require("warmup" !in raw) { "archetype $key has a warmup: strength sessions carry no stretches" }
        val tr = raw.get("tier_range", listOf(1L, 5L)) as List<Any?>
        return Archetype(
            key = raw.getValue("key") as String,
            name = raw.getValue("name") as String,
            tagline = raw.get("tagline", "") as String,
            intent = raw.get("intent", "") as String,
            splits = strList(raw.get("splits", emptyList<Any?>())).toSet(),
            sports = if (truthy(raw["sports"])) strList(raw["sports"]).toSet() else null,
            tierRange = num(tr[0]).toInt() to num(tr[1]).toInt(),
            phases = phases,
            schemes = schemes,
            supersets = supersets,
            finisher = finisher,
            cooldownTheme = raw["cooldown_theme"] as String?,
        )
    }

    private fun parseFlow(raw: Dict): FlowArchetype {
        val contexts = strList(raw.get("contexts", emptyList<Any?>())).toSet()
        require(VALID_CONTEXTS.containsAll(contexts)) { "flow archetype ${raw["key"]} has invalid contexts" }
        return FlowArchetype(
            key = raw.getValue("key") as String,
            name = raw.getValue("name") as String,
            tagline = raw.get("tagline", "") as String,
            contexts = contexts,
            sports = if (truthy(raw["sports"])) strList(raw["sports"]).toSet() else null,
            themes = strList(raw.get("themes", emptyList<Any?>())).toSet(),
            closerMuscles = strList(raw.get("closer_muscles", emptyList<Any?>())),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> load(files: List<Pair<String, String>>, parse: (Dict) -> T): List<T> =
        files.mapNotNull { (_, text) ->
            // A bad file must never break plan generation — skipped, as on the server.
            runCatching { parse(dyn(Json.parseToJsonElement(text)) as Dict) }.getOrNull()
        }

    val STRENGTH: List<Archetype> by lazy { load(STRENGTH_ARCHETYPE_FILES, ::parse) }
    val FLOWS: List<FlowArchetype> by lazy { load(FLOW_ARCHETYPE_FILES, ::parseFlow) }

    /** Rotates per training block through the archetypes that apply, in key order. */
    fun select(splitKey: String, sportFamily: String, tier: Int, stage: String, blockIndex: Int): Archetype? {
        val pool = STRENGTH.filter { it.applies(splitKey, sportFamily, tier, stage) }.sortedBy { it.key }
        if (pool.isEmpty()) return null
        return pool[blockIndex.mod(pool.size)]
    }

    /** Prefers theme matches, then rotates on [varietyKey]. */
    fun selectFlow(context: String, sportFamily: String, theme: String?, varietyKey: Int): FlowArchetype? {
        var pool = FLOWS.filter { it.applies(context, sportFamily) }
        if (pool.isEmpty()) return null
        val best = pool.maxOf { it.themeScore(theme) }
        pool = pool.filter { it.themeScore(theme) == best }.sortedBy { it.key }
        return pool[varietyKey.mod(pool.size)]
    }
}
