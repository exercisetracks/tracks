// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.parse.PyRandom

/**
 * A library or custom stretch eligible for auto-selection. Callers build these
 * after applying the per-device animation gate and the user's preferences, as
 * on the server (`api/flexibility/generation.build_stretch_candidates`).
 */
data class StretchCandidate(
    val name: String,
    val primaryMuscles: List<String>,
    val movementPattern: String,
    val difficulty: Int,
    val durationPerSideSec: Int,
    val sets: Int,
    val eachSide: Boolean,
    val description: String,
    val garminCategory: String?,
    val garminSubtype: Long?,
    val isCustom: Boolean = false,
    val preferred: Boolean = false,
    val secondaryMuscles: List<String> = emptyList(),
    val equipment: List<String> = listOf("bodyweight"),
    val cues: List<String> = emptyList(),
    val breathCue: String? = null,
    val position: String? = null,
)

/**
 * Stretch and mobility flow selection — a port of
 * `backend/app/calculators/flexibility.py`, the engine behind post-workout
 * flows, the weekly mobility session and the strength warm-up.
 *
 * One stretch per target muscle in priority order; within a muscle, candidates
 * are ranked (preferred → custom → recovery-appropriate pattern → easier →
 * name) and the pick rotates among the top few with CPython's own shuffle
 * ([PyRandom]), so a phone and a server choose the same stretch for the same
 * week.
 */
object Flexibility {
    /** Sport substring → muscles it loads, first hit wins, in this order. */
    val SPORT_STRETCH_MUSCLES: List<Pair<String, List<String>>> = listOf(
        "run" to listOf("hip_flexors", "calves", "hamstrings", "glutes", "quads", "lower_back"),
        "cycling" to listOf("hip_flexors", "quads", "glutes", "lower_back", "upper_back", "chest"),
        "biking" to listOf("hip_flexors", "quads", "glutes", "lower_back", "upper_back", "chest"),
        "bike" to listOf("hip_flexors", "quads", "glutes", "lower_back", "upper_back", "chest"),
        "swim" to listOf("shoulders", "chest", "upper_back", "lats", "neck", "hip_flexors"),
        "climb" to listOf("forearms", "lats", "upper_back", "chest", "shoulders", "hip_flexors"),
        "boulder" to listOf("forearms", "lats", "upper_back", "chest", "shoulders", "hip_flexors"),
        "paddl" to listOf("upper_back", "lats", "shoulders", "obliques", "forearms", "lower_back"),
        "kayak" to listOf("upper_back", "lats", "shoulders", "obliques", "forearms", "lower_back"),
        "canoe" to listOf("upper_back", "lats", "shoulders", "obliques", "forearms", "lower_back"),
        "row" to listOf("hamstrings", "glutes", "lower_back", "upper_back", "lats", "forearms"),
        "hik" to listOf("calves", "quads", "hamstrings", "hip_flexors", "glutes", "feet"),
        "walk" to listOf("calves", "hip_flexors", "hamstrings", "lower_back"),
        "strength" to listOf("hip_flexors", "hamstrings", "glutes", "chest", "shoulders",
            "upper_back", "quads", "lower_back"),
    )
    val DEFAULT_STRETCH_MUSCLES = listOf("hip_flexors", "hamstrings", "glutes", "lower_back", "upper_back", "quads")
    val FULL_BODY_MOBILITY_MUSCLES = listOf(
        "hip_flexors", "hamstrings", "glutes", "lower_back", "thoracic_spine",
        "upper_back", "quads", "chest", "calves", "shoulders",
    )
    val POSTWORKOUT_PATTERN_RANK: Map<String, Int> = mapOf(
        "static_stretch" to 0, "pnf_stretch" to 1, "yoga_pose" to 2,
        "myofascial_release" to 3, "dynamic_stretch" to 4,
    )
    val EXCLUDE_POSTWORKOUT_PATTERNS = setOf("balance_pose")
    private val POSITION_ORDER = mapOf("standing" to 0, "kneeling" to 1, "seated" to 2, "supine" to 3, "prone" to 3)
    private const val POSITION_MIDDLE = 2
    private val PORTABLE_STRETCH_EQUIPMENT = setOf("strap", "foam_roller", "block", "band")
    private const val ROTATION_DEPTH = 5

    fun isEquipmentFree(equipment: List<String>?): Boolean =
        equipment.orEmpty().none { it in PORTABLE_STRETCH_EQUIPMENT }

    fun sportTargetMuscles(sport: String?, fallback: List<String>? = null): List<String> {
        val s = (sport ?: "").lowercase()
        SPORT_STRETCH_MUSCLES.firstOrNull { it.first in s }?.let { return it.second }
        if (!fallback.isNullOrEmpty()) return fallback
        return DEFAULT_STRETCH_MUSCLES
    }

    /**
     * A strength session's trained muscles in stretch priority, deduplicated,
     * with the postural staples appended.
     *
     * Deterministic where the server's is not: the Python sorts a `set` with
     * a key that ties for every muscle outside the priority list, so their
     * order follows string hashing, which CPython randomises per process. Ties
     * here keep first-seen order, which is one of the orders the server can
     * produce.
     */
    fun strengthTargetMuscles(trained: List<String>): List<String> {
        val priority = SPORT_STRETCH_MUSCLES.first { it.first == "strength" }.second
        val ranked = trained.distinct()
            // Name breaks ties, as on the server: every muscle outside the
            // priority list ties on the rank alone.
            .sortedWith(compareBy<String>({ m -> priority.indexOf(m).takeIf { it >= 0 } ?: priority.size }, { it }))
            .toMutableList()
        for (staple in listOf("hip_flexors", "lower_back", "upper_back")) if (staple !in ranked) ranked += staple
        return ranked
    }

    private fun rankComparator(patternRank: Map<String, Int>): Comparator<StretchCandidate> =
        compareBy<StretchCandidate>(
            { if (it.preferred) 0 else 1 },
            { if (it.isCustom) 0 else 1 },
            { patternRank[it.movementPattern] ?: 5 },
            { it.difficulty },
            { it.name },
        )

    fun selectStretchFlow(
        candidates: List<StretchCandidate>,
        targetMuscles: List<String>,
        count: Int,
        seed: String,
        patternRank: Map<String, Int>? = null,
        excludePatterns: Set<String> = EXCLUDE_POSTWORKOUT_PATTERNS,
        equipmentFree: Boolean = false,
        minCount: Int = 3,
        orderByPosition: Boolean = false,
        closerMuscles: List<String>? = null,
    ): List<StretchCandidate> {
        // `pattern_rank or DEFAULT`: an empty map is falsy in Python too.
        val rank = patternRank?.takeIf { it.isNotEmpty() } ?: POSTWORKOUT_PATTERN_RANK
        val byMuscle = LinkedHashMap<String, MutableList<StretchCandidate>>()
        for (c in candidates) {
            if (c.movementPattern in excludePatterns && !c.preferred) continue
            if (equipmentFree && !c.preferred && !isEquipmentFree(c.equipment)) continue
            for (m in c.primaryMuscles) byMuscle.getOrPut(m) { ArrayList() } += c
        }
        val cmp = rankComparator(rank)

        fun ordered(muscle: String): List<StretchCandidate> {
            val pool = byMuscle[muscle].orEmpty().sortedWith(cmp)
            val pref = pool.filter { it.preferred }
            val others = pool.filter { !it.preferred }
            val top = others.take(ROTATION_DEPTH).toMutableList()
            val rest = others.drop(ROTATION_DEPTH)
            PyRandom("$seed-$muscle").shuffle(top)
            return pref + top + rest
        }

        val selected = ArrayList<StretchCandidate>()
        val seen = HashSet<String>()
        fun fill(muscles: List<String>, limit: Int) {
            for (muscle in muscles) {
                if (selected.size >= limit) break
                for (c in ordered(muscle)) {
                    if (c.name !in seen) {
                        selected += c
                        seen += c.name
                        break
                    }
                }
            }
        }

        fill(targetMuscles, count)
        if (selected.size < minOf(minCount, count)) fill(targetMuscles.flatMap { listOf(it, it) }, count)
        return if (orderByPosition) orderFlowByPosition(selected, closerMuscles) else selected
    }

    private fun positionRank(c: StretchCandidate): Int = POSITION_ORDER[c.position] ?: POSITION_MIDDLE

    /** Standing → kneeling → seated → floor, with an optional calming closer last. */
    fun orderFlowByPosition(selected: List<StretchCandidate>, closerMuscles: List<String>? = null): List<StretchCandidate> {
        if (selected.isEmpty()) return selected
        var closer: StretchCandidate? = null
        if (!closerMuscles.isNullOrEmpty()) {
            val wanted = closerMuscles.toSet()
            val floor = selected.filter { positionRank(it) >= 3 && it.primaryMuscles.any { m -> m in wanted } }
            val pick = floor.ifEmpty { selected.filter { it.primaryMuscles.any { m -> m in wanted } } }
            if (pick.isNotEmpty()) closer = pick.last()
        }
        // Identity, as the Python's `is not`: the closer is one particular object.
        val body = selected.filter { it !== closer }.sortedBy(::positionRank)
        return if (closer != null) body + closer else body
    }

    /** A selected stretch as a planned-workout `mobility_exercise` step. */
    fun toStep(c: StretchCandidate): MutableMap<String, Any?> = linkedMapOf(
        "type" to "mobility_exercise",
        "name" to c.name,
        "duration_seconds" to (if (c.durationPerSideSec != 0) c.durationPerSideSec else 60).toLong(),
        "sets" to (if (c.sets != 0) c.sets else 1).toLong(),
        "each_side" to c.eachSide,
        "description" to c.description,
        "muscles" to c.primaryMuscles,
        "cues" to c.cues,
        "breath_cue" to c.breathCue,
        "position" to c.position,
        "garmin_category" to c.garminCategory,
        "garmin_subtype" to c.garminSubtype,
    )
}
