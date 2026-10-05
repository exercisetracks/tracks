// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.fit.EXERCISE_CATEGORY
import com.tracks.core.plan.PlanBase

/**
 * Which exercises animate on the watch — a port of
 * `calculators/garmin_animations.py` and `api/flexibility/animation.py`.
 *
 * Only the two questions the planners ask: is this pair in Garmin's manifest at
 * all, and does the Yoga app (where every stretch flow runs) render it. The
 * manifest itself is generated from the server's JSON (GarminAnimationsData.kt).
 */
object GarminAnimations {

    private fun parse(encoded: String): Set<Long> =
        encoded.split(',').filter { it.isNotEmpty() }.mapTo(HashSet()) {
            val (c, n) = it.split(':'); key(c.toInt(), n.toLong())
        }

    private fun key(cat: Int, name: Long): Long = (cat.toLong() shl 32) or (name and 0xffffffffL)

    private val animated by lazy { parse(ANIMATED_PAIRS) }
    private val yoga by lazy { parse(YOGA_ANIMATED_PAIRS) }

    private fun catInt(category: String?): Int? = category?.lowercase()?.let(EXERCISE_CATEGORY::get)

    /** `is_animatable` over a category name: in any of Garmin's manifests. */
    fun isAnimatable(category: String?, subtype: Long?): Boolean {
        val c = catInt(category) ?: return false
        return subtype != null && key(c, subtype) in animated
    }

    /** `yoga_animatable`: the Yoga app renders it as a pose, move or plank. */
    fun yogaAnimatable(category: String?, subtype: Long?): Boolean {
        val c = catInt(category) ?: return false
        return subtype != null && key(c, subtype) in yoga
    }

    const val CONFIRMED_YES = "confirmed_yes"
    const val CONFIRMED_NO = "confirmed_no"
    const val LIKELY = "likely"
    const val UNKNOWN = "unknown"

    /**
     * `resolve_state` for this user's own confirmation.
     *
     * The server also weighs other accounts' confirmations for the same watch
     * model; a phone holds only its own account's rows, so it sees only
     * [own]. The two agree for a single-account server and whenever this
     * user has said anything, which is the case the confirmation exists for.
     */
    fun state(category: String?, subtype: Long?, own: Boolean?): String = when {
        !isAnimatable(category, subtype) -> UNKNOWN
        own == true -> CONFIRMED_YES
        own == false -> CONFIRMED_NO
        else -> LIKELY
    }
}

/** One stretch as the library or a custom row holds it, before the eligibility gate. */
data class StretchRow(
    val name: String,
    val primaryMuscles: List<String>,
    val secondaryMuscles: List<String>,
    val movementPattern: String?,
    val difficulty: Int?,
    val durationPerSideSec: Int?,
    val sets: Int?,
    val eachSide: Boolean?,
    val description: String?,
    val garminCategory: String?,
    val garminSubtype: Long?,
    val equipment: List<String>?,
    val cues: List<String>?,
    val breathCue: String?,
    val position: String?,
)

/**
 * The server's stretch-flow planning, from `api/flexibility/generation.py`:
 * the eligible candidate pool and the post-activity flow built from it.
 */
object StretchPlanning {

    /**
     * `_build_candidates`: the library plus the user's customs, minus what the
     * user excluded, what they confirmed does not animate, and — for library
     * stretches they have not preferred — what the Yoga app cannot render.
     *
     * [ownConfirmation] maps a stretch's (category, subtype) to this user's
     * own yes/no for their primary watch, when they have given one.
     */
    fun buildCandidates(
        library: List<StretchRow>,
        custom: List<StretchRow>,
        ownConfirmation: (String?, Long?) -> Boolean?,
        preferred: Set<String>,
        excluded: Set<String>,
    ): List<StretchCandidate> {
        val out = mutableListOf<StretchCandidate>()
        fun add(r: StretchRow, isCustom: Boolean) {
            if (r.name in excluded) return
            val isPref = r.name in preferred
            if (!isCustom && !isPref) {
                val state = GarminAnimations.state(r.garminCategory, r.garminSubtype, ownConfirmation(r.garminCategory, r.garminSubtype))
                if (state == GarminAnimations.CONFIRMED_NO) return
                if (!GarminAnimations.yogaAnimatable(r.garminCategory, r.garminSubtype)) return
            }
            out += StretchCandidate(
                name = r.name,
                primaryMuscles = r.primaryMuscles,
                secondaryMuscles = r.secondaryMuscles,
                movementPattern = r.movementPattern ?: "static_stretch",
                difficulty = r.difficulty?.takeIf { it != 0 } ?: 1,
                durationPerSideSec = r.durationPerSideSec?.takeIf { it != 0 } ?: 60,
                sets = r.sets?.takeIf { it != 0 } ?: 1,
                eachSide = r.eachSide == true,
                description = r.description.orEmpty(),
                garminCategory = r.garminCategory,
                garminSubtype = r.garminSubtype,
                isCustom = isCustom,
                preferred = isPref,
                equipment = r.equipment?.takeIf { it.isNotEmpty() } ?: listOf("bodyweight"),
                cues = r.cues.orEmpty(),
                breathCue = r.breathCue,
                position = r.position,
            )
        }
        library.forEach { add(it, false) }
        custom.forEach { add(it, true) }
        return out
    }

    /** What `generate_post_activity_stretch_flow(return_meta=True)` returns. */
    data class Flow(val steps: List<Map<String, Any?>>, val title: String?, val tagline: String?)

    /** `generate_post_activity_stretch_flow`, with the candidate pool supplied. */
    fun postActivityFlow(
        sport: String,
        candidates: List<StretchCandidate>,
        primaryMuscles: List<String>? = null,
        regenSalt: String = "",
        varietyKey: String = "",
        isStrength: Boolean = false,
        count: Int = 5,
        cooldownTheme: String? = null,
    ): Flow {
        if (candidates.isEmpty()) return Flow(emptyList(), null, null)
        val strength = isStrength || "strength" in sport.lowercase()
        val targets = if (strength) Flexibility.strengthTargetMuscles(primaryMuscles.orEmpty())
        else Flexibility.sportTargetMuscles(sport, fallback = primaryMuscles)

        val family = if (isStrength) "strength" else PlanBase.sportFamily(sport)
        // int(variety_key.replace("-", "")[-6:]), 0 when that is not a number.
        val varietyInt = varietyKey.replace("-", "").takeLast(6).toIntOrNull() ?: 0
        val archetype = Archetypes.selectFlow("post_workout", family, cooldownTheme, varietyInt)

        val selected = Flexibility.selectStretchFlow(
            candidates, targets,
            count = count,
            seed = "$sport-$regenSalt-$varietyKey",
            equipmentFree = true,
            orderByPosition = true,
            closerMuscles = archetype?.closerMuscles,
        )
        return Flow(selected.map(Flexibility::toStep), archetype?.name, archetype?.tagline)
    }
}
