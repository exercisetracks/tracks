// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.parse.PyRandom

/**
 * Slot-based, relevance-ranked exercise selection — a port of
 * `strength_plan/exercises.py`.
 *
 * Each of a split's slots is filled from the whole library with the most
 * sport-relevant movement the user can actually do, rotating among the top
 * few with CPython's shuffle ([PyRandom]). Main lifts rotate per training
 * block, accessories per week.
 *
 * The library is a `LinkedHashMap` on purpose: the backfill step sorts the
 * remaining pool with a key that ties often, and Python's stable sort then
 * keeps the dict's insertion order — so the phone must hold the library in
 * the same order the server does.
 */
internal object Exercises {
    private val RELEVANCE_FALLBACK = mapOf("generic" to "strength", "triathlon" to "running", "swimming" to "strength")
    private const val ROTATION_DEPTH = 3

    /** `int(x or 1)` — a null, zero or missing score counts as 1. */
    private fun intOr1(v: Any?): Long = if (truthy(v)) num(v).toLong() else 1L

    @Suppress("UNCHECKED_CAST")
    fun relevanceScore(ex: Dict, sportFamily: String): Long {
        val rel = (ex["sport_relevance"] as Map<String, Any?>?)?.takeIf { it.isNotEmpty() } ?: return 1
        val key = if (sportFamily in rel) sportFamily else RELEVANCE_FALLBACK[sportFamily] ?: ""
        if (key.isNotEmpty() && key in rel) return intOr1(rel[key])
        for (fallback in listOf("strength", "generic")) if (fallback in rel) return intOr1(rel[fallback])
        // `int(max(rel.values()) or 1)`: Python's max, first of any tie.
        var best: Any? = null
        for (v in rel.values) if (best == null || num(v) > num(best)) best = v
        return intOr1(best)
    }

    @Suppress("UNCHECKED_CAST")
    private fun equipmentOf(ex: Dict): List<Any?> =
        (ex["equipment"] as List<Any?>?)?.takeIf { it.isNotEmpty() } ?: listOf("bodyweight")

    fun eligiblePool(
        library: Map<String, Dict>,
        equipment: List<String>,
        injuries: List<ActiveInjury>,
        tier: Int,
        preferred: Set<String>,
        excluded: Set<String>,
        confirmedNo: Set<String>,
        maxDifficulty: Int?,
    ): LinkedHashMap<String, Dict> {
        val available = equipment.toSet()
        var maxDiff = Slots.TIER_MAX_DIFFICULTY[tier] ?: 5
        if (maxDifficulty != null) maxDiff = minOf(maxDiff, maxDifficulty)
        val out = LinkedHashMap<String, Dict>()
        for ((name, ex) in library) {
            if (name in excluded) continue
            val isCustom = truthy(ex["_is_custom"])
            val isPref = name in preferred
            if (!isCustom && Slots.STRETCH_CATEGORIES.has(ex["garmin_category"])) continue
            if (name in confirmedNo && !(isCustom || isPref)) continue
            if (equipmentOf(ex).none { available.has(it) }) continue
            if (!Injuries.isSafe(ex, injuries)) continue
            val difficulty = if (truthy(ex["difficulty"])) num(ex["difficulty"]) else 1.0
            if (!(isCustom || isPref) && difficulty > maxDiff) continue
            out[name] = ex
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun slotCandidates(slot: Slot, pool: Map<String, Dict>): List<String> {
        val out = ArrayList<String>()
        for ((name, ex) in pool) {
            if (!slot.patterns.has(ex["movement_pattern"])) continue
            val pm = ((ex["primary_muscles"] as List<Any?>?) ?: emptyList()).toSet()
            if (pm.none { slot.muscles.has(it) }) continue
            if (slot.excludeMuscles.isNotEmpty() && pm.any { slot.excludeMuscles.has(it) }) continue
            if (slot.unilateral == true && !Slots.isUnilateral(name)) continue
            if (slot.unilateral == false && Slots.isUnilateral(name)) continue
            if (slot.verticalPull == true && !Slots.isVerticalPull(name)) continue
            if (slot.verticalPull == false && Slots.isVerticalPull(name)) continue
            if (slot.isolationOnly && truthy(ex.get("is_compound", true))) continue
            out += name
        }
        return out
    }

    private fun rankSlot(names: List<String>, pool: Map<String, Dict>, sportFamily: String, slot: Slot, preferred: Set<String>): List<String> {
        fun key(n: String): List<Comparable<*>> {
            val ex = pool[n].orEmpty()
            val isPref = if (n in preferred) 0 else 1
            val animates = if (truthy(ex["has_animation"]) || n in preferred) 0 else 1
            val relevance = -relevanceScore(ex, sportFamily)
            val isComp = truthy(ex.get("is_compound", true))
            val difficulty = if (truthy(ex["difficulty"])) num(ex["difficulty"]) else 1.0
            if (slot.compound == false) {
                val comp = if (!isComp) 0 else 1
                return listOf(isPref, comp, relevance, animates, difficulty, n)
            }
            val comp = if (slot.compound == true) (if (isComp) 0 else 1) else 0
            return listOf(isPref, relevance, comp, animates, difficulty, n)
        }
        return names.sortedWith(TUPLE_ORDER.let { cmp -> Comparator { a, b -> cmp.compare(key(a), key(b)) } })
    }

    /** Python tuple comparison over ints, doubles and strings. */
    private val TUPLE_ORDER = Comparator<List<Comparable<*>>> { a, b ->
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]; val y = b[i]
            val c = if (x is String && y is String) x.compareTo(y) else num(x).compareTo(num(y))
            if (c != 0) return@Comparator c
        }
        a.size.compareTo(b.size)
    }

    private fun rotate(ranked: List<String>, preferred: Set<String>, seed: String): List<String> {
        val pref = ranked.filter { it in preferred }
        val others = ranked.filter { it !in preferred }
        val top = others.take(ROTATION_DEPTH).toMutableList()
        val rest = others.drop(ROTATION_DEPTH)
        PyRandom(seed).shuffle(top)
        return pref + top + rest
    }

    /** One session's picks as (slot key, name); the slot is null for a backfill. */
    fun select(
        sportFamily: String,
        tier: Int,
        splitType: String,
        equipment: List<String>,
        library: Map<String, Dict>,
        injuries: List<ActiveInjury>,
        maxExercises: Int,
        preferred: Set<String>,
        excluded: Set<String>,
        weekNum: Int,
        regenSalt: String,
        confirmedNo: Set<String>,
        usedThisWeek: Set<String>,
        maxDifficulty: Int?,
        blockNum: Int,
    ): List<Pair<String?, String>> {
        val pool = eligiblePool(library, equipment, injuries, tier, preferred, excluded, confirmedNo, maxDifficulty)
        val slots = Slots.splitSlots(splitType, sportFamily)
        val result = ArrayList<String>()
        val slotOf = HashMap<String, String?>()
        val usedSession = HashSet<String>()

        fun take(candidates: List<String>, avoidWeek: Boolean): String? =
            candidates.firstOrNull { it !in usedSession && !(avoidWeek && it in usedThisWeek) }

        for (slot in slots) {
            if (result.size >= maxExercises) break
            val names = slotCandidates(slot, pool)
            if (names.isEmpty()) continue
            val ranked = rankSlot(names, pool, sportFamily, slot, preferred)
            val seed = if (slot.role == "main") "$sportFamily-$splitType-${slot.key}-block$blockNum"
            else "$sportFamily-$splitType-${slot.key}-$weekNum-$regenSalt"
            val ordered = rotate(ranked, preferred, seed)
            val pick = take(ordered, avoidWeek = true) ?: take(ordered, avoidWeek = false)
            if (pick != null) {
                result += pick
                usedSession += pick
                slotOf[pick] = slot.key
            }
        }

        if (result.size < maxExercises) {
            val wanted = slots.flatMap { it.patterns }.toSet()
            val extras = pool.entries.filter { (n, ex) -> n !in usedSession && wanted.has(ex["movement_pattern"]) }
                .map { it.key }
                .sortedWith(compareBy<String>(
                    { if (it in usedThisWeek) 0 else -1 },
                    { -relevanceScore(pool.getValue(it), sportFamily) },
                    { if (truthy(pool.getValue(it).get("is_compound", true))) 0 else 1 },
                ))
            for (n in extras) {
                if (result.size >= maxExercises) break
                result += n
                usedSession += n
                if (n !in slotOf) slotOf[n] = null
            }
        }
        return result.map { slotOf[it] to it }
    }
}
