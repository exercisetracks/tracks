// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

/**
 * Session structure as an ordered list of movement contracts — a port of
 * `backend/app/calculators/strength_plan/slots.py`.
 *
 * A coach fills a session template ("squat, then hinge, then a unilateral,
 * then calves, then core"); each [Slot] is one such contract and [splitSlots]
 * returns a split's ordered list. The definitions are copied, not generated:
 * they are code in the Python too, and `spec/fixtures/strength_plan.json`
 * exercises every split through the selector, so a drifted slot shows up as a
 * different pick.
 */
internal data class Slot(
    val key: String,
    val patterns: Set<String>,
    val muscles: Set<String>,
    val unilateral: Boolean? = null,
    val compound: Boolean? = null,
    val verticalPull: Boolean? = null,
    val isolationOnly: Boolean = false,
    val excludeMuscles: Set<String> = emptySet(),
    /** "main" stays stable for a training block; "accessory" rotates weekly. */
    val role: String = "accessory",
)

internal object Slots {
    val LOWER = setOf("quads", "hamstrings", "glutes", "calves", "hip_flexors",
        "hip_adductors", "hip_abductors", "hip_external_rotators")
    private val CHEST = setOf("chest")
    private val V_PUSH_M = setOf("shoulders", "front_delts", "side_delts")
    private val H_PULL_M = setOf("upper_back", "mid_back", "rear_delts")
    private val V_PULL_M = setOf("lats")
    private val CORE = setOf("core", "abs")
    private val ROTATION = setOf("obliques", "abs", "core")
    private val HIP_STAB_M = setOf("hip_abductors", "hip_external_rotators")
    private val CALF_M = setOf("calves")
    private val BICEPS = setOf("biceps", "brachialis")
    private val TRICEPS = setOf("triceps")
    private val SIDE_DELT_M = setOf("side_delts", "shoulders")
    private val REAR_DELT_M = setOf("rear_delts")
    private val FOREARM = setOf("forearms")
    private val TRAPS = setOf("traps")

    /** Stretches leaked into the exercise library under these Garmin categories. */
    val STRETCH_CATEGORIES = setOf("warm_up", "pose", "move")

    private val UNILATERAL_FRAGMENTS = listOf(
        "single leg", "single-leg", "bulgarian", "split squat", "step up",
        "step-up", "lunge", "pistol", "single arm", "single-arm", "one leg",
        "one-leg", "b-stance",
    )
    private val VERTICAL_PULL_FRAGMENTS = listOf(
        "pull-up", "pull up", "pullup", "chin-up", "chin up", "chinup",
        "pulldown", "pull-down", "pull down", "lat pull", "pullover",
    )

    fun isUnilateral(name: String): Boolean = name.lowercase().let { n -> UNILATERAL_FRAGMENTS.any { it in n } }
    fun isVerticalPull(name: String): Boolean = name.lowercase().let { n -> VERTICAL_PULL_FRAGMENTS.any { it in n } }

    val SQUAT = Slot("squat", setOf("squat"), LOWER, unilateral = false, compound = true, role = "main")
    val HINGE = Slot("hinge", setOf("hinge"), LOWER, unilateral = false, compound = true, role = "main")
    val LOWER_UNI = Slot("lower_uni", setOf("squat", "hinge"), LOWER, unilateral = true)
    val CALF = Slot("calf", setOf("isolation"), CALF_M)
    val HIP_STAB = Slot("hip_stability", setOf("isolation", "isometric"), HIP_STAB_M)
    val H_PUSH = Slot("h_push", setOf("push"), CHEST, compound = true, role = "main")
    val V_PUSH = Slot("v_push", setOf("push"), V_PUSH_M, compound = true, excludeMuscles = CHEST, role = "main")
    val H_PULL = Slot("h_pull", setOf("pull"), H_PULL_M, compound = true, verticalPull = false, role = "main")
    val V_PULL = Slot("v_pull", setOf("pull"), V_PULL_M, compound = true, verticalPull = true, role = "main")
    val CHEST_ACC = Slot("chest_acc", setOf("push", "isolation"), CHEST, compound = false)
    val BICEPS_ACC = Slot("biceps_acc", setOf("pull", "isolation"), BICEPS, compound = false, isolationOnly = true)
    val TRICEPS_ACC = Slot("triceps_acc", setOf("push", "isolation"), TRICEPS, compound = false, isolationOnly = true)
    val SIDE_DELT = Slot("side_delt_acc", setOf("isolation"), SIDE_DELT_M, isolationOnly = true)
    val REAR_DELT = Slot("rear_delt_acc", setOf("pull", "isolation"), REAR_DELT_M, compound = false)
    val TRAPS_ACC = Slot("traps_acc", setOf("pull"), TRAPS)
    val FOREARM_GRIP = Slot("forearm_grip", setOf("pull", "isolation", "isometric", "carry"), FOREARM)
    val CORE_BRACE = Slot("core_brace", setOf("isometric", "isolation"), CORE)
    val CORE_ROT = Slot("core_rotation", setOf("rotation"), ROTATION)
    val LOWER_COMP = Slot("lower_compound", setOf("squat", "hinge"), LOWER, unilateral = false, compound = true, role = "main")
    val ANY_PULL = Slot("pull", setOf("pull"), H_PULL_M + V_PULL_M, compound = true, role = "main")
    val PLYO = Slot("plyo", setOf("plyometric"), LOWER)

    private val SPLIT_SLOTS: Map<String, List<Slot>> = mapOf(
        "upper_a" to listOf(H_PUSH, H_PULL, V_PUSH, V_PULL, BICEPS_ACC, TRICEPS_ACC),
        "upper_b" to listOf(V_PUSH, V_PULL, H_PUSH, H_PULL, SIDE_DELT, REAR_DELT),
        "lower_a" to listOf(SQUAT, HINGE, LOWER_UNI, CALF, CORE_BRACE),
        "lower_b" to listOf(HINGE, SQUAT, LOWER_UNI, HIP_STAB, CALF, CORE_BRACE),
        "ppl_push" to listOf(H_PUSH, V_PUSH, CHEST_ACC, TRICEPS_ACC, SIDE_DELT),
        "ppl_pull" to listOf(V_PULL, H_PULL, REAR_DELT, BICEPS_ACC, TRAPS_ACC),
        "ppl_legs" to listOf(SQUAT, HINGE, LOWER_UNI, CALF, CORE_BRACE),
        "full_body" to listOf(LOWER_COMP, H_PUSH, ANY_PULL, LOWER_UNI, CORE_BRACE, V_PUSH),
        "supp_lower" to listOf(HINGE, LOWER_UNI, HIP_STAB, CALF, CORE_BRACE),
        "supp_upper_core" to listOf(H_PUSH, H_PULL, CORE_BRACE, CORE_ROT, V_PULL),
    )
    private val SUPP_UPPER_BY_SPORT: Map<String, List<Slot>> = mapOf(
        "climbing" to listOf(V_PULL, H_PUSH, FOREARM_GRIP, CORE_BRACE, H_PULL),
        "paddling" to listOf(H_PULL, CORE_ROT, H_PUSH, CORE_BRACE, V_PULL),
    )

    /** Splits that earn a plyometric finisher for running/MTB athletes. */
    val LEG_SPLITS = setOf("lower_a", "lower_b", "ppl_legs", "full_body", "supp_lower")

    val TIER_MAX_DIFFICULTY = mapOf(1 to 3, 2 to 3, 3 to 4, 4 to 5, 5 to 5)

    /** Every slot by key, for archetypes that reference slots by name. */
    val BY_KEY: Map<String, Slot> = listOf(
        SQUAT, HINGE, LOWER_UNI, CALF, HIP_STAB, H_PUSH, V_PUSH, H_PULL, V_PULL, CHEST_ACC,
        BICEPS_ACC, TRICEPS_ACC, SIDE_DELT, REAR_DELT, TRAPS_ACC, FOREARM_GRIP, CORE_BRACE,
        CORE_ROT, LOWER_COMP, ANY_PULL, PLYO,
    ).associateBy { it.key }

    fun splitSlots(splitType: String, sportFamily: String): List<Slot> {
        if (splitType == "supp_upper_core") SUPP_UPPER_BY_SPORT[sportFamily]?.let { return it }
        return SPLIT_SLOTS[splitType] ?: SPLIT_SLOTS.getValue("full_body")
    }
}
