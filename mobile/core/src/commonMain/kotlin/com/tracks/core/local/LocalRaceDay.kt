// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.TrainingGoal
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.parse.PyMath
import com.tracks.core.plan.PlanBase
import com.tracks.core.plan.PlanLoad
import com.tracks.core.plan.RaceDay
import com.tracks.core.replica.SyncedRow
import com.tracks.core.strengthplan.dyn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A running race's race-day fitness and form from this phone's own data —
 * what the server's race plan reads through `race_helpers._get_running_metrics`
 * and `_race_form` (with `training_plan.helpers._plan_sessions`,
 * `_plan_adherence` and `_planned_load`), the arithmetic being
 * [RaceDay]'s. The same rows on both: the plan's own sessions, every activity
 * of the plan generators' history, and the dashboard's load.
 *
 * None of it needs heart rate. Adherence is minutes, the race-day VDOT is
 * today's estimate plus the plan's gain (and today's estimate works from
 * pace and volume alone, [com.tracks.core.plan.RunningFitness]), and a run
 * with no heart rate has an estimated load on the same scale as one with —
 * so a phone that records with GPS only predicts its race the way a watch
 * does.
 */
class LocalRaceDay(private val sources: LocalSources, private val library: LocalLibrary = sources.library) {

    /** `_get_running_metrics`: today's VDOT (measured only), race day's, and what scaled it. */
    data class Fitness(
        val vdotToday: Double?,
        val vdot: Double?,
        /** Null with no training plan for the goal: then race day is today. */
        val adherence: Double?,
        val runIndices: Pair<Double, Double>?,
    )

    /** `_race_form`: today's TSB and race morning's; null with no load history. */
    data class Form(val today: Double?, val raceDay: Double?)

    /** The goal's plan row's uid, the plan's sessions' `plan_uid`. */
    suspend fun planUid(goal: TrainingGoal): String? = withContext(Dispatchers.IO) {
        goal.uid?.let { g -> sources.replica.rows("plan").firstOrNull { it.str("goal_uid") == g && !it.isTombstone }?.uid }
    }

    private suspend fun planRows(planUid: String): List<SyncedRow> = withContext(Dispatchers.IO) {
        sources.replica.rows("planned_workout").filter { it.str("plan_uid") == planUid && !it.isTombstone }
    }

    /** `_plan_sessions`: the plan's sessions up to and including [today]. */
    suspend fun sessions(planUid: String, today: CivilDate): List<RaceDay.Session> = planRows(planUid).mapNotNull { r ->
        val day = r.str("scheduled_date")?.let(::civilOrNull) ?: return@mapNotNull null
        if (day > today) return@mapNotNull null
        RaceDay.Session(
            day, r.str("sport"), r.str("workout_type"), r.number("duration_minutes"),
            complete = r.flag("is_complete"), recorded = r.str("completed_activity_uid") != null,
        )
    }

    /** `_plan_adherence`: the plan's history against every activity in the window. */
    suspend fun adherence(planUid: String, today: CivilDate): Double {
        val done = withContext(Dispatchers.IO) {
            // The plan generators' history (LocalPlanning.history); the
            // window is RaceDay's to apply.
            library.metricActivities(sources.accountZone())
                .filter { it.date.epochDay >= today.epochDay - RaceDay.ADHERENCE_DAYS }
                .map { RaceDay.Done(it.date, it.sport, (it.durationSeconds?.toDouble() ?: 0.0) / 60) }
        }
        return RaceDay.adherence(sessions(planUid, today), done, today)
    }

    /** `_get_running_metrics`, for a running or triathlon goal. */
    suspend fun fitness(goal: TrainingGoal, today: CivilDate): Fitness {
        val evidence = LocalRunningEvidence(sources, library)
        val estimate = evidence.fitness(today)
        val vdotToday = if (estimate.measured) estimate.vdot else null
        val plan = planUid(goal)
        val adherence = plan?.let { adherence(it, today) }
        val event = goal.eventDate?.let(::civilOrNull)
        val vdot = if (vdotToday != null && adherence != null && event != null) {
            RaceDay.raceDayVdot(vdotToday, today, event, adherence)
        } else vdotToday
        return Fitness(vdotToday, vdot, adherence, evidence.trainingIndices(today))
    }

    /** `_race_form`: today's form, and race morning's after the plan's remaining sessions. */
    suspend fun form(goal: TrainingGoal, today: CivilDate, vdotToday: Double?): Form {
        val tss = LocalMetrics(library, sources).tssByDate(sources.importThresholds().thresholdHr)
        val tsbToday = if (tss.isEmpty()) null
        else TrainingLoad.ctlAtlToday(tss, today).let { (ctl, atl, _) -> PyMath.round(ctl - atl, 1) }
        val plan = planUid(goal)
        val event = goal.eventDate?.let(::civilOrNull)
        if (plan == null || event == null || event <= today) return Form(tsbToday, tsbToday)
        // The sessions' load at today's paces, as the server reads it.
        val paces = vdotToday?.takeIf { it != 0.0 }?.let(PlanBase::vdotToPaces)
        val planned = RaceDay.plannedLoadByDay(planRows(plan).mapNotNull { r ->
            val day = r.str("scheduled_date")?.let(::civilOrNull) ?: return@mapNotNull null
            if (day <= today || day >= event || r.flag("is_complete")) return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val steps = (r.fields["steps"]?.let { dyn(it) } as? List<Map<String, Any?>>).orEmpty()
            day to PlanLoad.workoutTss(steps, paces)
        })
        return Form(tsbToday, RaceDay.raceDayTsb(tss, planned, today, event))
    }

    private fun SyncedRow.flag(field: String): Boolean = (fields[field] as? JsonPrimitive)?.booleanOrNull == true
    private fun SyncedRow.number(field: String): Double? =
        (fields[field] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.doubleOrNull
}
