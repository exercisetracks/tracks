// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

/**
 * Supersets: lifts done in turn, a set of each per round — a port of
 * `backend/app/calculators/strength_plan/supersets.py`, where the rules and
 * the research behind them are written down. Used by the plan
 * ([StrengthPlan]) and by sessions built on the spot ([QuickSession]), so both
 * group the same way; the strength-plan fixtures keep it equal to the server.
 *
 * In short: every pair in a group shares a free weight or one of them needs
 * nothing, no two work the same primary muscle, heavy sets (5 reps or fewer)
 * and jumps stay straight, two or three to a group. The output is the
 * builder's block form (`group` on each member, a `rest` step between them),
 * which the watch encoder and the runners already play.
 */
object Supersets {

    private val FREE_WEIGHTS = setOf("dumbbell", "barbell", "kettlebell", "band")
    private const val MAX_GROUP = 3
    private const val HEAVY_REPS = 5

    private fun kit(step: Map<String, Any?>, library: Map<String, Map<String, Any?>>): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val equipment = (library[step["name"]]?.get("equipment") as List<String?>?)
            ?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: listOf("bodyweight")
        return equipment.toSet() - "bodyweight"
    }

    private fun compatible(a: Map<String, Any?>, b: Map<String, Any?>, library: Map<String, Map<String, Any?>>): Boolean {
        val ka = kit(a, library)
        val kb = kit(b, library)
        if (ka.isEmpty() || kb.isEmpty()) return true
        return ka.any { it in FREE_WEIGHTS && it in kb }
    }

    @Suppress("UNCHECKED_CAST")
    private fun muscles(step: Map<String, Any?>): Set<Any?> = (step["primary_muscles"] as List<Any?>?).orEmpty().toSet()

    private fun eligible(step: Map<String, Any?>): Boolean =
        step["type"] == "strength_exercise" &&
            (step["phase"] ?: "main") == "main" &&
            step["movement_pattern"] != "plyometric" &&
            ((step["reps"] as? Number)?.toInt() ?: 0) > HEAVY_REPS

    private fun fits(group: List<Map<String, Any?>>, step: Map<String, Any?>, library: Map<String, Map<String, Any?>>) =
        group.all { compatible(it, step, library) && (muscles(it) intersect muscles(step)).isEmpty() }

    /** [steps] with compatible lifts formed into supersets; [preferred] index pairs first. */
    fun group(
        steps: List<MutableMap<String, Any?>>,
        library: Map<String, Map<String, Any?>>,
        preferred: List<Pair<Int, Int>> = emptyList(),
    ): MutableList<MutableMap<String, Any?>> {
        val taken = HashSet<Int>()
        val groups = ArrayList<List<Int>>()

        for ((i, j) in preferred) {
            if (i in taken || j in taken || i >= steps.size || j >= steps.size) continue
            if (eligible(steps[i]) && eligible(steps[j]) && fits(listOf(steps[i]), steps[j], library)) {
                groups += listOf(i, j)
                taken += i
                taken += j
            }
        }
        for (i in steps.indices) {
            if (i in taken || !eligible(steps[i])) continue
            val group = arrayListOf(i)
            for (j in i + 1 until steps.size) {
                if (group.size >= MAX_GROUP) break
                if (j in taken || !eligible(steps[j])) continue
                if (fits(group.map { steps[it] }, steps[j], library)) group += j
            }
            if (group.size > 1) {
                groups += group
                taken += group
            }
        }
        if (groups.isEmpty()) return steps.toMutableList()

        // Each group is played where its first member was.
        val firstOf = groups.sortedBy { it.min() }
            .mapIndexed { n, g -> g.min() to (n to g.sorted()) }.toMap()
        val out = ArrayList<MutableMap<String, Any?>>()
        for (i in steps.indices) {
            val start = firstOf[i]
            if (start == null) {
                if (i !in taken) out += steps[i]
                continue
            }
            val (n, members) = start
            val rounds = members.maxOf { (steps[it]["sets"] as? Number)?.toLong()?.takeIf { s -> s != 0L } ?: 1L }
            val last = steps[members.last()]
            val group = linkedMapOf<String, Any?>(
                "uid" to "superset-${n + 1}",
                "kind" to "superset",
                "rounds" to rounds,
                "rest_seconds" to ((last["rest_seconds"] as? Number)?.toLong() ?: 0L),
            )
            members.forEachIndexed { k, m ->
                val member = LinkedHashMap(steps[m])
                member["group"] = group
                out += member
                if (k < members.size - 1) {
                    out += linkedMapOf(
                        "type" to "rest",
                        "name" to "Rest",
                        "duration_seconds" to ((member["rest_seconds"] as? Number)?.toLong() ?: 0L),
                        "group" to group,
                    )
                }
            }
        }
        return out
    }
}
