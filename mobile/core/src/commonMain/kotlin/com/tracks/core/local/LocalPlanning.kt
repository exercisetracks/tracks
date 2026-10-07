// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.plan.PlanBase
import com.tracks.core.plan.PlanInjection
import com.tracks.core.plan.PlanStart
import com.tracks.core.plan.StrengthInputs
import com.tracks.core.strengthplan.ActiveInjury
import com.tracks.core.strengthplan.GarminAnimations
import com.tracks.core.strengthplan.PlanLibrary
import com.tracks.core.strengthplan.StrengthGoal
import com.tracks.core.strengthplan.StretchCandidate
import com.tracks.core.strengthplan.StretchPlanning
import com.tracks.core.strengthplan.dyn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import com.tracks.core.api.TrainingGoal
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.plan.HistoryActivity
import com.tracks.core.plan.MovedWorkouts
import kotlinx.serialization.json.JsonNull
import com.tracks.core.plan.FitnessPlan
import com.tracks.core.plan.PlanAssembly
import com.tracks.core.plan.PlanGenerator
import com.tracks.core.plan.PlanGoal
import com.tracks.core.plan.PlanPhases
import com.tracks.core.replica.Hlc
import com.tracks.core.replica.Uids
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The training plan, built and rearranged on the phone.
 *
 * ## Why this is not a server call any more
 *
 * Generating a plan was the last piece of planning that needed a Tracks
 * server. The endurance generator is ported (`com.tracks.core.plan`, held to
 * the Python by spec/fixtures), so this does what `POST
 * /goals/{id}/plan/generate` does, from this phone's own data — and a phone
 * that has never seen a server can plan for a race.
 *
 * ## What it writes, and why that converges
 *
 * The plan row's uid comes from its goal's (`plan:{goal_uid}` in
 * spec/sync.yaml), so the phone and the server name the same plan the same
 * way. Each (re)generation stamps a fresh `generation`; the old generation's
 * unfinished workouts from today on are deleted here (the days before today,
 * and completed workouts, stay and join the new generation unchanged), and any another device still holds are dead by
 * the contract's read rule once this generation reaches it. Two phones
 * regenerating offline therefore end with one set of workouts, not two.
 * Workouts added by hand have no plan and are never touched; workouts the
 * user moved stay on their day and join the new generation, refreshed from
 * their counterpart session ([MovedWorkouts], the same rule as the server).
 *
 * ## Why the phone's plan and the server's agree
 *
 * Either one's rebuild replaces the other's on the next sync, so the two must
 * build the same plan from the same data — or every sync rewrites one
 * calendar with the other. The generators are held to each other by
 * spec/fixtures; what each reads besides the data is pinned too: the
 * strength and stretch picks are salted with the goal's uid (not the clock),
 * strength blocks count from the goal's first stamp ([planStart], not the
 * server's `created_at`), thresholds are derived afresh on both, and the
 * server reads injuries with the session's key as this phone always can.
 * What still differs is when each rebuilds: a plan built on another day
 * reads another week's training, and that is meant.
 */
class LocalPlanning(
    private val sources: LocalSources,
    private val library: LocalLibrary,
) {

    /** Why a plan could not be built — each is something the person can fix. */
    sealed class Refusal(val message: String) {
        data object NotAnEvent : Refusal("Training plans are built for Race / Event and Fitness goals.")
        data object NoDate : Refusal("Give the goal an event date first.")
        data object Past : Refusal("The event date has passed.")
    }

    data class Built(val workouts: Int, val vdot: Double?)

    /**
     * Build (or rebuild) the plan for [goal]. Returns the refusal when the
     * goal cannot have one, exactly where the server answers 400.
     */
    suspend fun regenerate(goal: TrainingGoal, today: CivilDate, nowMs: Long): Result<Built> {
        val fitness = goal.goalType == "fitness"
        if (goal.goalType != "event" && !fitness) return Result.failure(Refused(Refusal.NotAnEvent))
        val eventDate = goal.eventDate?.let(::civilOrNull)
        if (!fitness) {
            if (eventDate == null) return Result.failure(Refused(Refusal.NoDate))
            if (eventDate <= today) return Result.failure(Refused(Refusal.Past))
        }
        val goalUid = goal.uid ?: return Result.failure(Refused(Refusal.NoDate))

        val thresholds = sources.importThresholds()
        val planGoal = PlanGoal(
            eventDate = eventDate,
            eventSport = goal.eventSport,
            eventDistanceMeters = goal.eventDistanceMeters,
            daysPerWeek = goal.daysPerWeek?.toLong(),
            planIntensity = goal.planIntensity,
            mtbDiscipline = goal.mtbDiscipline,
            cyclingDiscipline = goal.cyclingDiscipline,
            ctlRampPerWeek = goal.ctlRampPerWeek,
            fitnessSports = goal.fitnessSports,
        )
        // The fitness fingerprint, replayed from matched workouts as the
        // server's matcher would have advanced it (LocalMatchEffects).
        val baseEffectiveWeeks = LocalMatchEffects(sources, library)
            .fingerprint(PlanBase.sportFamily((goal.eventSport ?: "running").lowercase())).effectiveWeeks
        val imperial = sources.setting("units") == "imperial"
        // Only read when there is no history of the sport (PlanStart).
        val frequencies = sources.activityFrequency()
        val frequency = PlanStart.frequencyFor(
            frequencies, PlanBase.sportFamily((goal.eventSport ?: "running").lowercase()),
        )
        // The VDOT every running pace comes from — the same estimate the race
        // predictions read (LocalRunningEvidence), as on the server.
        val runningFitness = LocalRunningEvidence(sources, library).fitness(today)
        val generated = if (fitness) {
            // Load as the dashboard shows it (LocalMetrics), so the plan builds
            // from the fitness number the person sees — as the server does.
            val (ctl, atl) = LocalMetrics(library, sources).ctlAtl(today, thresholds.thresholdHr)
            FitnessPlan.generate(
                goal = planGoal, paceBests = paceBests(), today = today, ctl = ctl, atl = atl,
                anchor = planStart(goal, today).takeIf { sources.row("goal", goal.id) != null },
                ftp = thresholds.ftp, daysPerWeek = goal.daysPerWeek?.toLong(),
                baseEffectiveWeeks = baseEffectiveWeeks, imperial = imperial,
                thresholdHr = thresholds.thresholdHr,
                // A multi-sport goal shares its week by the recent hours in each sport.
                history = history(today),
                // Each sport looks up its own answer (FitnessPlan).
                activityFrequency = frequency, activityFrequencies = frequencies,
                runningFitness = runningFitness,
            )
        } else {
            PlanGenerator.generateTrainingPlan(
                goal = planGoal,
                history = history(today),
                paceBests = paceBests(),
                today = today,
                ftp = thresholds.ftp,
                daysPerWeek = goal.daysPerWeek?.toLong(),
                imperial = imperial,
                thresholdHr = thresholds.thresholdHr,
                baseEffectiveWeeks = baseEffectiveWeeks,
                activityFrequency = frequency,
                runningFitness = runningFitness,
            )
        }
        var workouts = generated.workouts.toMutableList()
        if (goal.scheduleTests == true) workouts = PlanAssembly.injectFieldTests(workouts, goal.eventSport)
        // Then what the server injects, in its order: strength and mobility
        // sessions (with the user's own workouts), then a stretch flow after
        // each training day. Salted with the goal's uid, as the server is
        // (generation.py), so a plan rebuilt here and one rebuilt there pick
        // the same exercises and stretches. It was the clock, and the two
        // never agreed; the picks still rotate by week and by day.
        workouts = PlanInjection.inject(
            workouts,
            strength = if (goal.includeStrength == true) strengthInputs(goal, today) else null,
            stretchCandidates = stretchCandidates(),
            today = today,
            regenSalt = goalUid,
        )
        if (fitness) {
            // Strength for a goal with no date spans twelve weeks; a rolling
            // plan is four weeks of everything (generation.py does the same).
            val end = FitnessPlan.horizonEnd(today)
            workouts = workouts.filter { (it["scheduled_date"] as CivilDate) <= end }.toMutableList()
        }
        val assembled = PlanAssembly.stamp(generated.vdot, workouts, Uids.v7(nowMs))

        val planUid = Uids.forEntity("plan", mapOf("goal_uid" to goalUid), nowMs)
        val mine = sources.replica.rows("planned_workout").filter { it.str("plan_uid") == planUid && !it.isTombstone }
        val moved = mine.filter { it.bool("moved_by_user") }
        // What a rebuild replaces is the unfinished plan from today on. The
        // days before today are the plan's history — planned, done or missed —
        // and stay exactly as they were; wiping them made every edit after a
        // plan's first day erase the record of it so far. From today on a
        // completed workout stays too, claiming its counterpart on its own day
        // (MovedWorkouts.keepCompleted). Both join the new generation only so
        // no replica reaps them as a superseded plan's leftovers. Moved ones
        // are carried below. The server's `_replace_workouts` is the same rule.
        val staying = mine.filter { r ->
            !r.bool("moved_by_user") && (
                r.bool("is_complete") || r.str("scheduled_date")?.let(::civilOrNull)?.let { it < today } == true
            )
        }
        // Old generation first: its workouts are the plan's children, and
        // deleting them after the new ones exist would need telling apart.
        // Workouts the user moved stay on their day (MovedWorkouts).
        for (row in mine) {
            if (!row.bool("moved_by_user") && row !in staying) sources.replica.delete("planned_workout", row.uid)
        }
        for (r in staying) {
            sources.setValues("planned_workout", sources.idOf(r.uid), mapOf("generation" to assembled.generation))
        }
        val kept = MovedWorkouts.keep(
            assembled.workouts,
            moved.mapNotNull { r ->
                val day = r.str("scheduled_date")?.let(::civilOrNull) ?: return@mapNotNull null
                MovedWorkouts.Moved(r.uid, day, r.str("sport"), r.str("workout_type"), r.bool("is_complete"))
            },
            today,
        )
        val remaining = MovedWorkouts.keepCompleted(
            kept.remaining,
            staying.filter { it.bool("is_complete") }.mapNotNull { r ->
                val day = r.str("scheduled_date")?.let(::civilOrNull) ?: return@mapNotNull null
                MovedWorkouts.Completed(r.uid, day, r.str("sport"), r.str("workout_type"))
            },
            today,
        )
        for (r in moved) {
            // Into the new generation — left on the old one, every replica
            // would reap it as a superseded plan's leftovers.
            val values = linkedMapOf<String, Any?>("generation" to assembled.generation)
            kept.refresh[r.uid]?.let { content ->
                val fresh = content + ("steps" to (content["steps"] ?: emptyList<Any?>()))
                // Only what changed: re-stamping an unchanged field would beat
                // a real edit made elsewhere.
                val changed = fresh.filter { (f, v) -> LocalJson.of(v) != (r.fields[f] ?: JsonNull) }
                if (changed.isNotEmpty()) {
                    values += changed
                    // The file on the watch is the old session; send it again.
                    if (r.str("watch_filename") != null && r.str("watch_deleted_at") == null) {
                        values += mapOf("watch_filename" to null, "watch_uploaded_at" to null)
                    }
                }
            }
            sources.setValues("planned_workout", sources.idOf(r.uid), values)
        }
        sources.createValues(
            "plan",
            mapOf(
                "goal_uid" to goalUid,
                "sport" to (goal.eventSport ?: "running"),
                "vdot" to assembled.vdot,
                "generation" to assembled.generation,
            ),
            keyValues = mapOf("goal_uid" to goalUid),
        )
        for (w in remaining) {
            sources.createValues(
                "planned_workout",
                mapOf(
                    "plan_uid" to planUid,
                    "generation" to assembled.generation,
                    "scheduled_date" to w["scheduled_date"],
                    "sport" to w["sport"],
                    "workout_type" to w["workout_type"],
                    "title" to w["title"],
                    "description" to w["description"],
                    "duration_minutes" to w["duration_minutes"],
                    "distance_meters" to w["distance_meters"],
                    "steps" to (w["steps"] ?: emptyList<Any?>()),
                    "is_complete" to false,
                    "origin" to "generated",
                ),
            )
        }
        sources.changed()
        return Result.success(Built(remaining.size + moved.size + staying.size, assembled.vdot))
    }

    /**
     * Rebuild the active goal's plan, if it has one to rebuild: a fitness
     * goal, or a race still ahead. What every edit that leaves the plan stale
     * calls (com.tracks.core.plan.PlanStaleness) — there is no Regenerate
     * button. Null when there is no such goal, which is not a failure.
     */
    suspend fun refreshActive(today: CivilDate, nowMs: Long): Result<Built>? {
        val goal = sources.goals().firstOrNull { it.isActive } ?: return null
        if (!isPlannable(goal, today)) return null
        return regenerate(goal, today, nowMs)
    }

    /** A goal whose plan is live: fitness (rolling), or a race still ahead — the server's `_plannable`. */
    fun isPlannable(goal: TrainingGoal, today: CivilDate): Boolean = when (goal.goalType) {
        "fitness" -> true
        "event" -> goal.eventDate?.let(::civilOrNull)?.let { it > today } == true
        else -> false
    }

    /**
     * Make [goalId] the active goal. The others are switched off too, as the
     * server does — the read rule would pick this one anyway, since its stamp
     * is newest, but a goal list showing two "active" badges on a device that
     * has not merged yet would be wrong to look at.
     */
    suspend fun activate(goalId: Int, goals: List<TrainingGoal>) {
        for (g in goals) {
            if (g.id != goalId && g.isActive) sources.setValues("goal", g.id, mapOf("is_active" to false))
        }
        sources.setValues("goal", goalId, mapOf("is_active" to true))
    }

    /**
     * Move a workout to another day. Only the date and the "moved" mark are
     * stamped now — so a rename made elsewhere while this phone was offline
     * survives the move, and a move made elsewhere later wins over this one.
     * The mark is what keeps it on this day through a regeneration
     * (MovedWorkouts); it is set for hand-added workouts too, harmlessly,
     * since regeneration never touches those anyway.
     */
    suspend fun reschedule(workoutId: Int, date: CivilDate) {
        sources.setValues("planned_workout", workoutId, mapOf("scheduled_date" to date.isoformat(), "moved_by_user" to true))
    }

    /**
     * Remove a goal and the plan built for it. The plan is deleted first, and
     * its generated workouts go with it (they are its children); workouts
     * added by hand belong to no plan and stay.
     */
    suspend fun deleteGoal(goal: TrainingGoal, nowMs: Long) {
        goal.uid?.let { goalUid ->
            val planUid = Uids.forEntity("plan", mapOf("goal_uid" to goalUid), nowMs)
            if (sources.replica.row("plan", planUid)?.isTombstone == false) sources.deleteUid("plan", planUid)
        }
        sources.delete("goal", goal.id)
    }

    // ── What the injection reads, gathered from the replica ─────────────────

    private suspend fun prefs(entity: String): Map<String, String?> =
        sources.replica.rows(entity).associate { (it.str("exercise_name") ?: "") to it.str("preference") }

    /**
     * The stretch pool, as `build_stretch_candidates` builds it: the bundled
     * library plus custom stretches, through the animation gate, with this
     * user's own animation confirmations (see [GarminAnimations.state]).
     */
    private suspend fun stretchCandidates(): List<StretchCandidate> {
        val p = prefs("stretch_preference")
        val confirmations = sources.replica.rows("animation_confirmation").associate {
            (it.str("garmin_category") to it.str("garmin_subtype")?.toLongOrNull()) to
                (it.fields["animates"] as? JsonPrimitive)?.booleanOrNull
        }
        val customs = sources.replica.rows("custom_stretch").map { PlanLibrary.stretchRow(dyn(JsonObject(it.fields)) as Map<String, Any?>) }
        return StretchPlanning.buildCandidates(
            PlanLibrary.stretches, customs,
            { cat, sub -> confirmations[cat to sub] },
            p.filterValues { it == "preferred" }.keys, p.filterValues { it == "excluded" }.keys,
        )
    }

    /** Everything `_inject_strength_workouts` reads, from this phone's own rows. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun strengthInputs(goal: TrainingGoal, today: CivilDate): StrengthInputs {
        val p = prefs("exercise_preference")
        // Customs join the library tagged, as the server merges them.
        val library = LinkedHashMap(PlanLibrary.exercises)
        for (row in sources.replica.rows("custom_exercise")) {
            val c = dyn(JsonObject(row.fields)) as Map<String, Any?>
            val name = c["name"] as? String ?: continue
            library[name] = linkedMapOf(
                "name" to name,
                "primary_muscles" to (c["primary_muscles"] ?: emptyList<String>()),
                "secondary_muscles" to (c["secondary_muscles"] ?: emptyList<String>()),
                "equipment" to ((c["equipment"] as List<*>?)?.takeIf { it.isNotEmpty() } ?: listOf("bodyweight")),
                "movement_pattern" to (c["movement_pattern"] ?: "push"),
                "is_compound" to c["is_compound"],
                "difficulty" to c["difficulty"],
                "garmin_category" to c["garmin_category"],
                "garmin_subtype" to c["garmin_subtype"],
                "has_animation" to (c["has_animation"] == true),
                "_is_custom" to true,
            )
        }
        val standings = LocalTraining(sources).standings()
        val records = standings.associate {
            it.exerciseName to linkedMapOf<String, Any?>(
                "estimated_1rm_kg" to it.estimated1rmKg, "last_weight_kg" to it.lastWeightKg,
                "last_reps" to it.lastReps?.toLong(), "last_session_volume_kg" to it.lastSessionVolumeKg,
                "sessions_completed" to it.sessionsCompleted.toLong(), "progression_stage" to it.progressionStage,
            )
        }
        val injuries = sources.replica.rows("injury").filter { r ->
            val end = r.str("end_date")?.let(::civilOrNull)
            end == null || end >= today
        }.map { ActiveInjury(it.str("body_part"), it.int("severity") ?: 1) }

        val recentCutoff = CivilDate.fromEpochDay(today.epochDay - 14)
        val recentlyUsed: Set<Any> = sources.replica.rows("planned_workout").mapNotNull { r ->
            val d = r.str("scheduled_date")?.let(::civilOrNull) ?: return@mapNotNull null
            r.str("custom_workout_uid")?.takeIf { d >= recentCutoff }
        }.toSet()
        val customs = LocalTraining(sources).savedWorkouts().filter { it.includeInPlan }.map { w ->
            val uid = sources.uidOf(w.id) ?: w.id.toString()
            PlanAssembly.CustomWorkout(
                id = uid,
                name = w.name,
                muscles = w.exercises.flatMapTo(HashSet()) { e ->
                    val lib = library[e.exerciseName] ?: return@flatMapTo emptyList()
                    (lib["primary_muscles"] as List<String>) + (lib["secondary_muscles"] as List<String>)
                },
                exercises = w.exercises.sortedBy { it.orderIndex }.map { e ->
                    linkedMapOf<String, Any?>(
                        // The keys the strength FIT encoder reads, as on the server.
                        "type" to (if (e.isRest) "rest" else "strength_exercise"),
                        "name" to e.exerciseName, "sets" to e.targetSets.toLong(),
                        "reps" to e.targetReps.toLong(),
                        "rir_target" to e.rirTarget.toLong(), "rest_seconds" to e.restSeconds.toLong(),
                        "weight_method" to e.weightMethod, "weight_value" to e.weightValue,
                    )
                },
            )
        }

        return StrengthInputs(
            goal = StrengthGoal(
                strengthTier = goal.strengthTier ?: 3,
                strengthDaysPerWeek = goal.strengthDaysPerWeek,
                eventDate = goal.eventDate?.let(::civilOrNull),
            ),
            eventSport = goal.eventSport,
            equipment = sources.replica.rows("settings").firstOrNull()
                ?.let { (it.fields["equipment_available"] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content } }
                ?.takeIf { it.isNotEmpty() } ?: listOf("bodyweight", "dumbbell"),
            library = library,
            strengthRecords = records,
            injuries = injuries,
            sessionsCount = standings.maxOfOrNull { it.sessionsCompleted } ?: 0,
            units = sources.setting("units") ?: "metric",
            preferred = p.filterValues { it == "preferred" }.keys,
            excluded = p.filterValues { it == "excluded" }.keys,
            sessionMaxMinutes = goal.strengthSessionMinutes,
            experience = sources.setting("strength_experience"),
            anchorDate = planStart(goal, today),
            customs = customs,
            recentlyUsed = recentlyUsed,
        )
    }

    /** Where the goal's phase arc begins: the goal row's earliest stamp. */
    suspend fun planStart(goal: TrainingGoal, today: CivilDate): CivilDate {
        val row = sources.row("goal", goal.id) ?: return today
        val first = row.clock.values.mapNotNull { Hlc.parseOrNull(it)?.wallMs }.minOrNull() ?: return today
        return CivilDate.fromEpochDay(first / 86_400_000L)
    }

    suspend fun phase(goal: TrainingGoal, today: CivilDate): PlanPhases.Info? {
        if (goal.goalType != "event") return null
        val event = goal.eventDate?.let(::civilOrNull) ?: return null
        return PlanPhases.info(event, planStart(goal, today), today)
    }

    /** `_get_activity_history`: the last 90 days, merged trips excluded (they have no file). */
    private suspend fun history(today: CivilDate): List<HistoryActivity> {
        val cutoff = today.epochDay - 90
        return library.metricActivities(sources.accountZone())
            .filter { it.date.epochDay >= cutoff }
            .map { HistoryActivity(it.sport, it.date, it.distanceMeters, it.durationSeconds?.toDouble()) }
    }

    /**
     * `_get_pace_bests`: the fastest speed at each distance across every run,
     * from each activity's `pace_curve` — the same parser output the server
     * stores as `pace_bests` rows.
     */
    internal suspend fun paceBests(): List<Pair<Long, Double>> {
        val best = sortedMapOf<Long, Double>()
        for (a in library.metricActivities(sources.accountZone())) {
            if (a.sport !in RUNNING) continue
            val uid = library.uidOf(a.id.toInt()) ?: continue
            val curve = library.detailJson(uid)?.get("pace_curve") as? JsonObject ?: continue
            for ((k, v) in curve) {
                val dist = k.toDoubleOrNull()?.toLong() ?: continue
                val speed = (v as? JsonPrimitive)?.doubleOrNull ?: continue
                if (speed > (best[dist] ?: Double.NEGATIVE_INFINITY)) best[dist] = speed
            }
        }
        return best.map { it.key to it.value }
    }

    class Refused(val refusal: Refusal) : Exception(refusal.message)

    private companion object {
        val RUNNING = setOf("running", "trail_running", "treadmill_running")
    }
}

private fun com.tracks.core.replica.SyncedRow.bool(field: String): Boolean =
    (fields[field] as? JsonPrimitive)?.booleanOrNull == true

internal fun civilOrNull(iso: String): CivilDate? = runCatching {
    CivilDate(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt(), iso.substring(8, 10).toInt())
}.getOrNull()
