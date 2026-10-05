// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.Py
import com.tracks.core.parse.PyMath

/**
 * The pure half of `backend/app/api/training_plan/injectors.py` and
 * `generation.py`: everything that turns the generator's endurance sessions
 * into the plan a user sees, minus the database reads that gather inputs.
 *
 * The database half stays with each platform — the server queries Postgres,
 * the phone reads its replica — and hands the results in as plain values, so
 * the decisions (which session becomes a field test, which strength day a
 * custom workout takes over, where a stretch flow goes) are made by one piece
 * of logic on both.
 */
object PlanAssembly {

    // ── Field tests ──────────────────────────────────────────────────────────

    /**
     * `_inject_field_tests`: replace the sessions nearest 10%, 45% and 75% of
     * the plan with an FTP / Pmax / FTP test. MTB and cycling only.
     */
    fun injectFieldTests(workouts: MutableList<MutableMap<String, Any?>>, eventSport: String?): MutableList<MutableMap<String, Any?>> {
        if (workouts.isEmpty()) return workouts
        val family = PlanBase.sportFamily((eventSport ?: "").lowercase())
        if (family != "mountain_biking" && family != "cycling") return workouts

        val planStart = workouts.first()["scheduled_date"] as CivilDate
        val raceDate = workouts.last()["scheduled_date"] as CivilDate
        val totalDays = (raceDate.epochDay - planStart.epochDay).takeIf { it != 0L } ?: 1L
        val targets = listOf(0.10, 0.45, 0.75).map { CivilDate.fromEpochDay(planStart.epochDay + (totalDays * it).toLong()) }
        val testTypes = listOf("ftp20", "pmax5", "ftp20")
        val sport = eventSport?.takeIf { it.isNotEmpty() } ?: "mountain_biking"

        val used = mutableSetOf<Int>()
        for ((target, testType) in targets.zip(testTypes)) {
            var bestIdx: Int? = null
            var bestDist = 999L
            for ((i, w) in workouts.withIndex()) {
                if (i in used) continue
                val type = w["workout_type"] as String
                if (type == "long" || type == "rest" || type == "race") continue
                if (type.startsWith("field_test")) continue
                val d = kotlin.math.abs((w["scheduled_date"] as CivilDate).epochDay - target.epochDay)
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }
            if (bestIdx == null || bestDist > 4) continue

            val steps = Mtb.fieldTest(testType)
            val duration = PlanBase.durationFromSteps(steps, null)
            val wtype = "field_test:$testType"
            workouts[bestIdx] = linkedMapOf(
                "scheduled_date" to workouts[bestIdx]["scheduled_date"],
                "sport" to sport,
                "workout_type" to wtype,
                "title" to PlanBase.workoutTitle(wtype, PlanBase.sportFamily(sport), null, duration),
                "description" to PlanBase.workoutDescription(steps),
                "duration_minutes" to duration,
                "distance_meters" to null,
                "steps" to steps,
            )
            used += bestIdx
        }
        return workouts
    }

    // ── Custom workouts ──────────────────────────────────────────────────────

    /**
     * A user's own strength workout that is eligible for the plan: its
     * identity, name, the muscles its exercises train (primary and secondary,
     * from the library), and its exercise list as the plan should carry it.
     */
    data class CustomWorkout(
        val id: Any,
        val name: String,
        val muscles: Set<String>,
        val exercises: List<Map<String, Any?>>,
    )

    /**
     * The custom-workout pass of `_inject_strength_workouts`: each custom
     * workout not scheduled in the last 14 days takes over the generated
     * strength session it overlaps most (at least two muscles), then the plan
     * is re-sorted by date. [recentlyUsed] holds the ids scheduled since then.
     */
    fun attachCustomWorkouts(
        existing: List<MutableMap<String, Any?>>,
        strength: List<MutableMap<String, Any?>>,
        customs: List<CustomWorkout>,
        recentlyUsed: Set<Any>,
    ): MutableList<MutableMap<String, Any?>> {
        for (cw in customs) {
            if (cw.id in recentlyUsed) continue
            if (cw.muscles.isEmpty()) continue
            var bestSd: MutableMap<String, Any?>? = null
            var bestOverlap = 0
            for (sd in strength) {
                if (Py.truthy(sd["custom_workout_id"])) continue
                val sdMuscles = strList(sd["primary_muscles"]).toSet() + strList(sd["secondary_muscles"])
                val overlap = (cw.muscles intersect sdMuscles).size
                if (overlap > bestOverlap) { bestOverlap = overlap; bestSd = sd }
            }
            if (bestSd != null && bestOverlap >= 2) {
                bestSd["type"] = "custom_strength"
                bestSd["custom_workout_id"] = cw.id
                bestSd["custom_workout_name"] = cw.name
                bestSd["custom_exercises"] = cw.exercises
            }
        }
        // Python's sort is stable, and so is Kotlin's.
        return (existing + strength).sortedBy { (it["scheduled_date"] as CivilDate).epochDay }.toMutableList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun strList(v: Any?): List<String> = (v as List<String>?) ?: emptyList()

    // ── Stretch flows ────────────────────────────────────────────────────────

    /** What `_inject_stretch_flows` asks the stretch planner for one day. */
    data class FlowRequest(
        val sport: String,
        val varietyKey: String,
        val isStrength: Boolean,
        val primaryMuscles: List<String>?,
        val cooldownTheme: Any?,
    )

    /** The stretch planner's answer: steps, and optionally a title and tagline. */
    data class FlowMeta(val steps: List<Map<String, Any?>>, val title: String?, val tagline: String?)

    /**
     * `_inject_stretch_flows`: one post-workout stretch flow on every training
     * day that has no mobility session yet. [flowFor] is the stretch planner
     * (it needs the library, the device and the user's preferences, which the
     * platform supplies); pass null when there are no candidate stretches at
     * all, as the server returns early then.
     */
    fun injectStretchFlows(
        workouts: MutableList<MutableMap<String, Any?>>,
        flowFor: ((FlowRequest) -> FlowMeta)?,
    ): MutableList<MutableMap<String, Any?>> {
        if (flowFor == null) return workouts
        val existingDates = workouts
            .filter { it["workout_type"] == "mobility" || it["workout_type"] == "flexibility" }
            .map { it["scheduled_date"] as CivilDate }
            .toMutableSet()
        val skip = setOf("rest", "race", "mobility", "flexibility")

        val newFlows = mutableListOf<MutableMap<String, Any?>>()
        for (w in workouts) {
            if (w["workout_type"] in skip) continue
            val d = w["scheduled_date"] as CivilDate
            if (d in existingDates) continue

            val sport = if (w.containsKey("sport")) w["sport"] else "running"
            val isStrength = w["workout_type"] == "strength" || w["workout_type"] == "custom_strength" ||
                w["sport"] == "strength_training"
            val trained = if (isStrength) {
                @Suppress("UNCHECKED_CAST")
                val steps = (if (w.containsKey("steps")) w["steps"] else emptyList<Map<String, Any?>>()) as List<Map<String, Any?>>
                steps.flatMap { strList(it["primary_muscles"]) }.toSortedSet().toList()
            } else null

            val meta = flowFor(FlowRequest(Py.str(sport).lowercase(), d.isoformat(), isStrength, trained, w["cooldown_theme"]))
            val steps = meta.steps
            if (steps.size < 2) continue

            var seconds = 0L
            for (s in steps) {
                val dur = if (s.containsKey("duration_seconds")) Py.int(s["duration_seconds"]) else 60L
                val sets = if (s.containsKey("sets")) Py.int(s["sets"]) else 1L
                seconds += dur * sets * (if (Py.truthy(s["each_side"])) 2 else 1)
            }
            val duration = seconds.floorDiv(60L) + 2
            val title = if (!meta.title.isNullOrEmpty()) "${meta.title} — $duration min" else "Post-Workout Stretch — $duration min"
            val description = meta.tagline?.takeIf { it.isNotEmpty() }
                ?: "Recovery stretch targeting muscles used in today's workout."

            newFlows += linkedMapOf(
                "scheduled_date" to d, "sport" to "flexibility_training", "workout_type" to "flexibility",
                "title" to title, "description" to description, "duration_minutes" to duration,
                "distance_meters" to null, "steps" to steps,
            )
            existingDates += d
        }
        workouts += newFlows
        return workouts.sortedBy { (it["scheduled_date"] as CivilDate).epochDay }.toMutableList()
    }

    // ── The fitness fingerprint ──────────────────────────────────────────────

    /** What the matcher knows of a completed activity. */
    data class DoneActivity(
        val sport: String?,
        val distanceMeters: Double?,
        val durationSeconds: Long?,
    )

    /** What the matcher knows of the planned workout it was matched to. */
    data class PlannedTarget(
        val workoutType: String,
        val distanceMeters: Double?,
        val durationMinutes: Long?,
    )

    /**
     * `_compute_completion_pct`: how much of the planned workout the activity
     * covered, 0–1, rounded to three places. MTB counts time only — trail
     * distance says little about effort.
     */
    fun completionPct(workout: PlannedTarget, activity: DoneActivity): Double {
        val dur = workout.durationMinutes?.takeIf { it != 0L }
        val actSec = activity.durationSeconds?.takeIf { it != 0L }
        if (PlanBase.sportFamily(activity.sport ?: "") == "mountain_biking") {
            if (dur != null && actSec != null) return PyMath.round(PlanBase.pyMin(1.0, (actSec / 60.0) / dur), 3)
            return 0.0
        }
        val scores = mutableListOf<Double>()
        val wd = workout.distanceMeters?.takeIf { it != 0.0 }
        val ad = activity.distanceMeters?.takeIf { it != 0.0 }
        if (wd != null && ad != null) scores += PlanBase.pyMin(1.0, ad / wd)
        if (dur != null && actSec != null) scores += PlanBase.pyMin(1.0, (actSec / 60.0) / dur)
        return if (scores.isNotEmpty()) PyMath.round(PyMath.sum(scores) / scores.size, 3) else 0.0
    }

    /** One sport family's fingerprint: training age in effective weeks, best VDOT, sessions. */
    data class Fingerprint(
        val effectiveWeeks: Double = 0.0,
        val vdot: Double? = null,
        val sessionsCompleted: Long = 0,
    )

    private val QUALITY = setOf("intervals", "tempo", "race_pace", "fartlek", "sweet_spot")

    /**
     * `_update_fingerprint`, as a fold: the fingerprint after one more matched
     * activity. The server keeps the running result in a table; a phone can
     * equally replay every match from the start, and gets the same answer.
     */
    fun advanceFingerprint(
        fp: Fingerprint, family: String, activity: DoneActivity, workout: PlannedTarget, completionPct: Double,
    ): Fingerprint {
        var ew = fp.effectiveWeeks
        if (completionPct >= 0.5) {
            val bonus = if (workout.workoutType in QUALITY) 0.15 else 0.0
            val advance = PlanBase.pyMin(1.0, completionPct) * (1.0 + bonus)
            ew = PyMath.round(ew + advance, 3)
        }
        var vdot = fp.vdot
        val dist = activity.distanceMeters?.takeIf { it != 0.0 }
        val sec = activity.durationSeconds?.takeIf { it != 0L }
        if (family == "running" && dist != null && sec != null) {
            val v = PlanBase.calculateVdot(dist, sec.toDouble())
            if (v > 20) vdot = PlanBase.pyMax(vdot?.takeIf { it != 0.0 } ?: 0.0, PyMath.round(v, 2))
        }
        return Fingerprint(ew, vdot, fp.sessionsCompleted + 1)
    }

    // ── Assembling a plan ────────────────────────────────────────────────────

    /**
     * A generated plan as it is stored: its VDOT (running only, to a tenth),
     * the fresh `generation` it was built under (spec/sync.yaml, "plan"), and
     * its workouts, each already carrying that generation.
     */
    data class Assembled(
        val vdot: Double?,
        val generation: String,
        val workouts: List<MutableMap<String, Any?>>,
    )

    /**
     * Stamp a finished workout list with a new generation — the step every
     * (re)generation ends with, on any device. A workout of an older generation
     * of the same plan is dead once this lands; see ReadRules.deadWorkouts.
     *
     * [generation] is a fresh UUIDv7 (`Uids.v7(now)`), supplied by the caller
     * because it owns the clock.
     */
    fun stamp(vdot: Double?, workouts: List<MutableMap<String, Any?>>, generation: String): Assembled {
        for (w in workouts) w["generation"] = generation
        return Assembled(vdot?.takeIf { it != 0.0 }?.let { PyMath.round(it, 1) }, generation, workouts)
    }
}
