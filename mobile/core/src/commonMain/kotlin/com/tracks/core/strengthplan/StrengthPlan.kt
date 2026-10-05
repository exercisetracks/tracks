// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.Py
import com.tracks.core.parse.PyMath
import com.tracks.core.spec.experienceMaxDifficulty
import com.tracks.core.spec.experienceStageFloor
import com.tracks.core.spec.startingWeightFactor

/** The goal fields the strength planner reads. */
data class StrengthGoal(
    /** 1 (maintenance) to 5 (strength athlete). */
    val strengthTier: Int,
    /** Overrides the tier's sessions per week when set and non-zero. */
    val strengthDaysPerWeek: Int? = null,
    val eventDate: CivilDate? = null,
)

/** A workout already on the calendar, which strength must work around. */
data class ExistingWorkout(val scheduledDate: CivilDate?, val workoutType: String?)

/**
 * The strength and mobility plan generator — a port of
 * `backend/app/calculators/strength_plan/generator.py`, the piece that turns a
 * goal into dated strength and weekly-mobility workouts.
 *
 * ## Inputs are the server's, not the phone's
 *
 * The server's caller (`api/training_plan/injectors.py`) gathers these from
 * the database: the exercise library with the user's custom exercises merged
 * in, per-exercise strength records, active injuries, and the device-aware
 * stretch pool. The phone will gather the same things from its own store; this
 * function only needs them in the same shape. The library in particular
 * comes from the server's exercise_library table today — moving it into
 * `spec/` so the phone ships it is a separate step.
 *
 * ## Output is dict-shaped on purpose
 *
 * Each workout is the map the server stores as a planned workout, steps
 * included, with numbers keeping their Python types (see [Dict]). Parity is
 * checked against the server's own output, whole, in
 * `spec/fixtures/strength_plan.json`.
 */
object StrengthPlan {
    private val HARD_TYPES = setOf(
        "intervals", "threshold", "tempo", "vo2max", "race", "long", "long_run", "fartlek",
        "race_pace", "short_quality", "aerobic",
    )
    private val QUALITY_TYPES = setOf("intervals", "threshold", "tempo", "vo2max")
    private val HARD_TYPES_MOB = setOf("intervals", "threshold", "long", "long_run", "tempo", "fartlek", "race_pace")
    private val SPLIT_LABELS = mapOf(
        "ppl_push" to "Push Day", "ppl_pull" to "Pull Day", "ppl_legs" to "Leg Day",
        "upper_a" to "Upper Body", "upper_b" to "Upper Body", "lower_a" to "Lower Body",
        "lower_b" to "Lower Body", "full_body" to "Full Body", "supp_lower" to "Lower Body",
        "supp_upper_core" to "Upper Body & Core",
    )

    private fun weekday(d: CivilDate): Int = (d.epochDay + 3).mod(7)   // 0 = Monday; 1970-01-01 was a Thursday
    private fun plus(d: CivilDate, days: Long): CivilDate = CivilDate.fromEpochDay(d.epochDay + days)

    internal fun generate(
        goal: StrengthGoal,
        sportFamily: String,
        today: CivilDate,
        existingWorkouts: List<ExistingWorkout>,
        equipment: List<String>,
        library: Map<String, Dict>,
        strengthRecords: Map<String, Dict>,
        injuries: List<ActiveInjury>,
        sessionsCount: Int = 0,
        units: String = "metric",
        preferred: Set<String>? = null,
        excluded: Set<String>? = null,
        sessionMaxMinutes: Int? = null,
        regenSalt: String = "",
        confirmedNo: Set<String>? = null,
        stretchCandidates: List<StretchCandidate>? = null,
        experience: String? = null,
        anchorDate: CivilDate? = null,
    ): List<MutableMap<String, Any?>> {
        val tier = goal.strengthTier
        // A race already run is no race — see generator.py. Otherwise every day
        // of the 12-week fallback is "on or after the race" and nothing is made.
        val raceDate = goal.eventDate?.takeIf { it > today }
        val expMaxDifficulty = experienceMaxDifficulty(experience)
        val expSessionFloor = experienceStageFloor(experience)
        val expWeightFactor = startingWeightFactor(experience)
        val endurance = Periodization.isEnduranceFamily(sportFamily)

        val explicitDays = goal.strengthDaysPerWeek?.takeIf { it != 0 }
        val sessionsPerWeek = explicitDays ?: when {
            tier == 5 -> 5
            tier == 4 -> 4
            tier == 3 -> 3
            tier == 2 -> 2
            else -> 1
        }

        val existingByDate = LinkedHashMap<CivilDate, MutableList<String>>()
        for (w in existingWorkouts) {
            val d = w.scheduledDate ?: continue
            existingByDate.getOrPut(d) { ArrayList() } += (w.workoutType ?: "")
        }

        val planEnd = if (raceDate != null && raceDate > today) raceDate else plus(today, 12 * 7)
        val planMonday = plus(today, -weekday(today).toLong())
        // math.ceil(days / 7) for a non-negative span, in integers.
        val totalWeeks = maxOf(1L, -((-(planEnd.epochDay - planMonday.epochDay)).floorDiv(7L))).toInt()
        val anchor = anchorDate ?: today
        val anchorMonday = plus(anchor, -weekday(anchor).toLong())

        val splitSequence = when {
            tier >= 5 -> listOf("ppl_push", "ppl_pull", "ppl_legs", "ppl_push", "ppl_pull").take(sessionsPerWeek)
            tier == 4 || tier == 3 -> listOf("upper_a", "lower_a", "upper_b", "lower_b", "full_body").take(sessionsPerWeek)
            tier == 2 -> listOf("supp_lower", "supp_upper_core")
            else -> listOf("full_body")
        }
        val preferredDays = when {
            sessionsPerWeek >= 4 -> listOf(0, 1, 3, 4, 5)
            sessionsPerWeek == 3 -> listOf(0, 2, 4)
            sessionsPerWeek == 2 -> listOf(0, 3)
            else -> listOf(2)
        }

        var sessionCounter = 0
        var splitIdx = 0
        val workouts = ArrayList<MutableMap<String, Any?>>()
        val maxEx = if (tier <= 2) 3 else if (tier == 3) 4 else 5

        for (weekNum in 0 until totalWeeks) {
            val weekStart = plus(planMonday, weekNum * 7L)
            val weekIndex = (weekStart.epochDay - anchorMonday.epochDay).floorDiv(7L).toInt()
            val blockNum = weekIndex.floorDiv(4)
            val weekInBlock = weekIndex.mod(4)

            val overallSessions = maxOf(sessionsCount + sessionCounter, expSessionFloor)
            val stage = when {
                overallSessions < 20 -> "linear"
                overallSessions < 100 -> "weekly_undulating"
                else -> "dup"
            }
            val isDeload = weekInBlock == 3 || (stage == "dup" && weekIndex.mod(8) == 7)

            var placed = 0
            val weekUsed = HashSet<String>()

            for (dayOffset in 0 until 7) {
                if (placed >= sessionsPerWeek) break
                val sessionDate = plus(weekStart, dayOffset.toLong())
                if (sessionDate < today) continue
                if (raceDate != null && sessionDate >= raceDate) continue

                val dayTypes = existingByDate[sessionDate].orEmpty()
                if (dayTypes.any { it in HARD_TYPES }) continue
                if (weekday(sessionDate) !in preferredDays && placed < sessionsPerWeek) {
                    val stillPossible = (dayOffset + 1 until 7).any { d ->
                        val day = plus(weekStart, d.toLong())
                        weekday(day) in preferredDays && day >= today &&
                            existingByDate[day].orEmpty().none { it in QUALITY_TYPES }
                    }
                    if (stillPossible) continue
                }

                val splitKey = if (splitSequence.isNotEmpty()) splitSequence[splitIdx % splitSequence.size] else "full_body"
                splitIdx++

                val archetype = Archetypes.select(splitKey, sportFamily, tier, stage, blockNum)
                val supersetOf = HashMap<String, Long>()
                archetype?.supersets?.forEachIndexed { gi, pair -> for (slot in pair) supersetOf[slot] = gi.toLong() }

                val slotPicks = Exercises.select(
                    sportFamily = sportFamily, tier = tier, splitType = splitKey, equipment = equipment,
                    library = library, injuries = injuries, maxExercises = maxEx,
                    preferred = preferred.orEmpty(), excluded = excluded.orEmpty(), weekNum = weekNum,
                    regenSalt = regenSalt, confirmedNo = confirmedNo.orEmpty(), usedThisWeek = weekUsed,
                    maxDifficulty = expMaxDifficulty, blockNum = blockNum,
                )
                if (slotPicks.isEmpty()) continue
                val exercises = slotPicks.map { it.second }
                weekUsed += exercises

                val steps = ArrayList<MutableMap<String, Any?>>()
                var descSets: Any? = null
                var descReps: Any? = null
                var descRpe: Any? = null
                for ((slotKey, exName) in slotPicks) {
                    val ex = library[exName].orEmpty()
                    val pattern = ex.get("movement_pattern", "push")
                    var restS: Any = Periodization.restSeconds(pattern, tier)
                    val scheme = if (archetype != null && slotKey != null) archetype.schemes[slotKey] else null

                    val rec = strengthRecords[exName]
                    val exStage = if (truthy(rec)) rec!!.get("progression_stage", stage) as String? else stage
                    val dupIdx = if (exStage == "dup") sessionCounter % 3 else 0
                    val (pReps, pRpe, pPct) = Periodization.prescription(
                        exStage, if (exStage == "weekly_undulating") weekInBlock else dupIdx, endurance,
                    )
                    var setsCount: Any = Periodization.TIER_SETS[tier] ?: 3L
                    var reps: Any = pReps
                    var rpeTarget: Any = pRpe
                    var pct1rm: Any = pPct
                    var tempo: Any? = null
                    if (truthy(scheme)) {
                        setsCount = scheme!!.get("sets", setsCount)!!
                        reps = scheme.get("reps", reps)!!
                        rpeTarget = scheme.get("rpe", rpeTarget)!!
                        pct1rm = scheme.get("pct_1rm", pct1rm)!!
                        restS = scheme.get("rest_seconds", restS)!!
                        tempo = scheme["tempo"]
                    }
                    if (isDeload) {
                        setsCount = pyMax(1L, pyFloorDiv(setsCount, 2))
                        pct1rm = PyMath.round(num(pct1rm) * 0.85, 3)
                        rpeTarget = pyMin(rpeTarget, 6L)
                    }
                    if (descSets == null) { descSets = setsCount; descReps = reps; descRpe = rpeTarget }

                    val injModifier = Injuries.loadModifier(ex, injuries)
                    @Suppress("UNCHECKED_CAST")
                    val exEquip = ex.get("equipment", listOf("bodyweight")) as List<String?>?
                    var weight: Double = when {
                        rec != null && truthy(rec) && truthy(rec["estimated_1rm_kg"]) -> {
                            val w = Loads.trainingWeight(num(rec["estimated_1rm_kg"]) * injModifier, num(pct1rm))
                            if (w < 0.1) 0.0 else w
                        }
                        rec != null && truthy(rec) && truthy(rec["last_weight_kg"]) ->
                            Loads.trainingWeight(num(rec["last_weight_kg"]) * injModifier, num(pct1rm))
                        else -> Loads.conservativeStartingWeight(pattern, exEquip) * (injModifier * expWeightFactor)
                    }
                    if (weight > 0) weight = Loads.roundWeightForEquipment(weight, exEquip?.filterNotNull(), units)

                    @Suppress("UNCHECKED_CAST")
                    val cues = ((ex["cues"] as List<Any?>?)?.takeIf { it.isNotEmpty() } ?: emptyList()).take(3)
                    val step = linkedMapOf<String, Any?>(
                        "type" to "strength_exercise",
                        "name" to exName,
                        "garmin_category" to ex["garmin_category"],
                        "garmin_subtype" to ex["garmin_subtype"],
                        "sets" to setsCount,
                        "reps" to reps,
                        "weight_kg" to weight,
                        "target_rpe" to rpeTarget,
                        "rest_seconds" to restS,
                        "primary_muscles" to ex.get("primary_muscles", emptyList<Any?>()),
                        "movement_pattern" to pattern,
                        "cues" to cues,
                        "phase" to "main",
                    )
                    if (truthy(tempo)) step["tempo"] = tempo
                    if (slotKey != null && slotKey in supersetOf) step["superset_group"] = supersetOf[slotKey]
                    steps += step
                }

                if ((sportFamily == "running" || sportFamily == "mountain_biking" || sportFamily == "alpine_skiing") &&
                    splitKey in Slots.LEG_SPLITS && !isDeload
                ) {
                    val plyo = if ("barbell" in equipment || "dumbbell" in equipment) "Box Jump" else "Squat Jump"
                    val plyoEx = library[plyo]
                    if (plyoEx != null && Injuries.isSafe(plyoEx, injuries)) {
                        @Suppress("UNCHECKED_CAST")
                        val cues = ((plyoEx["cues"] as List<Any?>?)?.takeIf { it.isNotEmpty() } ?: emptyList()).take(3)
                        steps += linkedMapOf(
                            "type" to "strength_exercise",
                            "name" to plyo,
                            "garmin_category" to plyoEx["garmin_category"],
                            "garmin_subtype" to plyoEx["garmin_subtype"],
                            "sets" to 3L,
                            "reps" to 6L,
                            "weight_kg" to 0.0,
                            "target_rpe" to 8L,
                            "rest_seconds" to 90L,
                            "primary_muscles" to plyoEx.get("primary_muscles", emptyList<Any?>()),
                            "movement_pattern" to "plyometric",
                            "cues" to cues,
                            "phase" to "finisher",
                        )
                    }
                }

                // No stretches: a strength session is lifts only, and stretching
                // is its own workout. See generator.py.

                val deloadTag = if (isDeload) " — Deload" else ""
                val baseDesc = description(exercises, descSets!!, descReps!!, descRpe!!, isDeload, stage, sportFamily, weekInBlock)
                val title: String
                val desc: String
                if (archetype != null) {
                    title = "${archetype.name}$deloadTag"
                    val intent = archetype.intent.ifEmpty { archetype.tagline }
                    desc = "$intent $baseDesc".trim { it.isWhitespace() }
                } else {
                    title = "${SPLIT_LABELS[splitKey] ?: "Strength"} Workout$deloadTag"
                    desc = baseDesc
                }
                val duration = Periodization.sessionDurationMinutes(exercises, library, descSets, descReps, tier, sessionMaxMinutes)

                val workout = linkedMapOf<String, Any?>(
                    "scheduled_date" to sessionDate,
                    "sport" to "strength_training",
                    "workout_type" to "strength",
                    "title" to title,
                    "description" to desc,
                    "duration_minutes" to duration,
                    "distance_meters" to null,
                    "steps" to steps,
                )
                if (archetype != null && !archetype.cooldownTheme.isNullOrEmpty()) workout["cooldown_theme"] = archetype.cooldownTheme
                workouts += workout
                sessionCounter++
                placed++
            }

            if (placed > 0 && !stretchCandidates.isNullOrEmpty()) {
                val weekEnd = plus(weekStart, 7)
                val strengthDates = workouts
                    .filter { it["workout_type"] == "strength" }
                    .map { it["scheduled_date"] as CivilDate }
                    .filter { it >= weekStart && it < weekEnd }
                    .toSet()
                val mobilityDate = findMobilityDay(weekStart, existingByDate, today, raceDate, strengthDates)
                val mobSteps = Mobility.steps(sportFamily, stretchCandidates, weekNum, regenSalt)
                if (mobilityDate != null && mobSteps.isNotEmpty()) {
                    workouts += linkedMapOf(
                        "scheduled_date" to mobilityDate,
                        "sport" to "strength_training",
                        "workout_type" to "mobility",
                        "title" to "Mobility & Recovery",
                        "description" to Mobility.description(sportFamily),
                        "duration_minutes" to 25L,
                        "distance_meters" to null,
                        "steps" to mobSteps,
                    )
                }
            }
        }
        return workouts
    }

    private fun findMobilityDay(
        weekStart: CivilDate,
        existingByDate: Map<CivilDate, List<String>>,
        today: CivilDate,
        raceDate: CivilDate?,
        exclude: Set<CivilDate>,
    ): CivilDate? {
        fun usable(d: CivilDate) = d >= today && (raceDate == null || d < raceDate) && d !in exclude
        for (offset in 0 until 7) {
            val d = plus(weekStart, offset.toLong())
            if (!usable(d)) continue
            if (existingByDate[plus(d, -1)].orEmpty().any { it in HARD_TYPES_MOB }) return d
        }
        for (offset in listOf(2, 4, 5, 6, 1, 3, 0)) {
            val d = plus(weekStart, offset.toLong())
            if (!usable(d)) continue
            if (existingByDate[d].isNullOrEmpty()) return d
        }
        for (offset in 0 until 7) {
            val d = plus(weekStart, offset.toLong())
            if (usable(d)) return d
        }
        return null
    }

    private fun description(
        exercises: List<String>,
        sets: Any,
        reps: Any,
        rpe: Any,
        isDeload: Boolean,
        stage: String,
        sportFamily: String,
        weekInBlock: Int?,
    ): String {
        // A dict lookup in the Python, so 6 and 6.0 find the same entry.
        val rpeDesc = num(rpe).let { r ->
            when (r) { 5.0 -> "very easy"; 6.0 -> "easy"; 7.0 -> "moderate"; 8.0 -> "hard"; 9.0 -> "very hard"; else -> "moderate" }
        }
        val weekNote = if (weekInBlock != null && !isDeload)
            "Week ${weekInBlock + 1} of 4 — same key lifts as last week, add load. " else ""
        val stageNote = when (stage) {
            "linear" -> "Adding weight each session as you build strength."
            "weekly_undulating" -> "Volume and intensity vary week to week for optimal adaptation."
            "dup" -> "Daily rep-range variation maximizes neural and muscular adaptation."
            else -> ""
        }
        val sportNote = when (sportFamily) {
            "running" -> "Targeted to improve running economy and injury resilience."
            "cycling" -> "Squat and single-leg work proven to improve cycling power and efficiency."
            "climbing" -> "Pulling strength and finger endurance for climbing performance."
            "paddling" -> "Horizontal pulling and rotational power for paddle efficiency."
            "mountain_biking" -> "Heavy compound movements to improve sprint power and trail control."
            "hiking" -> "Single-leg strength and hip stability for demanding terrain."
            "alpine_skiing" -> "Heavy squats and single-leg work for the forces of a turn; the plan's" +
                " eccentric and plyometric days build on them."
            "nordic_skiing" -> "Hinge, single-leg and pulling strength for poling power and a stable kick."
            else -> ""
        }
        var exList = exercises.take(3).joinToString(", ")
        if (exercises.size > 3) exList += ", +${exercises.size - 3} more"
        val s = Py.str(sets)
        val r = Py.str(reps)
        if (isDeload) {
            return "Deload week — $s×$r at $rpeDesc effort. " +
                "Keep same weights, cut volume in half. Key lifts: $exList. " +
                "Recovery session to absorb the last 3 weeks of training."
        }
        return "$weekNote$s×$r at Perceived Exertion ${Py.str(rpe)} ($rpeDesc). $stageNote " +
            "$sportNote Focus: $exList."
    }
}
