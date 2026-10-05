// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.parse.PyMath

/**
 * The rolling plan for a Fitness goal — a port of
 * `backend/app/calculators/plan/generator/fitness.py`, held to it by the
 * `fitness` section of spec/fixtures/plan.json.
 *
 * The goal is a rate, CTL points per week (−2 … +6), not a date. Each week's
 * load is what moves CTL by that much — CTL is a 42-day average, so seven
 * days at L from C end at `L + (C − L)·k⁷`, and `L = C + r / (1 − k⁷)` —
 * and the week itself comes from [PlanGenerator.generateWeek], the event
 * plan's own week builder. Four weeks from this Monday; the fourth week of
 * every cycle, counted from the goal's first stamp, is light. The Python
 * docstring has the reasoning for each choice; this file keeps its arithmetic
 * order so the two agree to the last workout.
 */
object FitnessPlan {

    const val WEEKS = 4L
    const val RAMP_MIN = -2.0
    // The slider's top (backend schemas/coaching.py CtlRamp has the
    // measurements and why it is +6).
    const val RAMP_MAX = 6.0

    /** k⁷ by repeated multiplication, as the server does, so no pow() last-bit drift. */
    private val CTL_WEEK_DECAY: Double = run {
        var k = 1.0
        repeat(7) { k *= TrainingLoad.CTL_DECAY }
        k
    }
    private val CTL_WEEK_SHARE = 1 - CTL_WEEK_DECAY
    private const val RECOVERY_LOAD = 0.75
    private const val FORM_FLOOR = -30.0
    private const val BUILD_PHASE_RAMP = 3.0
    private const val NO_RACE_M = 10000.0

    data class WeekTarget(
        val weekStart: CivilDate,
        val recovery: Boolean,
        /** What the week asks CTL to change by; null for the light week. */
        val ramp: Double?,
        val tss: Double,
        val ctlEnd: Double,
    )

    private fun monday(d: CivilDate) = CivilDate.fromEpochDay(d.epochDay - (d.epochDay + 3).mod(7L))

    /** `fitness_week_targets`. */
    fun weekTargets(ctl: Double, atl: Double, ramp: Double?, today: CivilDate, anchor: CivilDate?): List<WeekTarget> {
        val r = PlanBase.pyMax(RAMP_MIN, PlanBase.pyMin(RAMP_MAX, ramp ?: 0.0))
        val start0 = monday(today)
        val anchorMonday = monday(anchor ?: today)
        var c = ctl
        val out = mutableListOf<WeekTarget>()
        for (w in 0 until WEEKS) {
            val start = CivilDate.fromEpochDay(start0.epochDay + w * 7)
            val recovery = (start.epochDay - anchorMonday.epochDay).floorDiv(7L).mod(WEEKS) == WEEKS - 1
            var weekRamp = r
            if (w == 0L && c - atl < FORM_FLOOR) weekRamp = PlanBase.pyMin(weekRamp, 0.0)
            var daily = if (recovery) c * RECOVERY_LOAD else c + weekRamp / CTL_WEEK_SHARE
            daily = PlanBase.pyMax(daily, 0.0)
            val cEnd = daily + (c - daily) * CTL_WEEK_DECAY
            out += WeekTarget(
                weekStart = start,
                recovery = recovery,
                ramp = if (recovery) null else weekRamp,
                tss = PyMath.round(daily * 7, 1),
                ctlEnd = PyMath.round(cEnd, 1),
            )
            c = cEnd
        }
        return out
    }

    /** `fitness_horizon_end`: the Sunday of the plan's fourth week. */
    fun horizonEnd(today: CivilDate): CivilDate = CivilDate.fromEpochDay(monday(today).epochDay + WEEKS * 7 - 1)

    private val TRIATHLON_SPORTS = listOf("swimming", "cycling", "running")

    /** `goal_sports`: the endurance sports a fitness goal trains, one per family. */
    fun goalSports(goal: PlanGoal): List<String> {
        var picked = (goal.fitnessSports ?: emptyList()).filter { it.isNotEmpty() }.map { it.lowercase() }
        if (picked.isEmpty()) picked = listOf((goal.eventSport?.takeIf { it.isNotEmpty() } ?: "running").lowercase())
        val out = mutableListOf<String>()
        val families = mutableListOf<String>()
        for (s in picked) {
            val expanded = if (PlanBase.sportFamily(s) == "triathlon") TRIATHLON_SPORTS else listOf(s)
            for (e in expanded) {
                val fam = PlanBase.sportFamily(e)
                if (fam == "strength" || fam in families) continue
                families += fam
                out += e
            }
        }
        return out.ifEmpty { listOf("running") }
    }

    /** `generate_fitness_plan`: four weeks of workouts from the current CTL/ATL. */
    fun generate(
        goal: PlanGoal,
        paceBests: List<Pair<Long, Double>>,
        today: CivilDate,
        ctl: Double,
        atl: Double,
        anchor: CivilDate? = null,
        ftp: Double? = null,
        daysPerWeek: Long? = null,
        baseEffectiveWeeks: Double? = null,
        imperial: Boolean = false,
        thresholdHr: Double? = null,
        history: List<HistoryActivity> = emptyList(),
        /** How often the goal's first sport is done (a [PlanStart] level); used when [activityFrequencies] is null. */
        activityFrequency: String? = null,
        /** The settings map, sport family → [PlanStart] level, looked up per sport. Read only with no load history. */
        activityFrequencies: Map<String, Any?>? = null,
        /** Today's running fitness ([RunningFitness]), as the event plan reads it. */
        runningFitness: RunningFitness.Estimate? = null,
    ): PlanGenerator.Generated {
        val sports = goalSports(goal)
        val dpw = goal.daysPerWeek?.takeIf { it != 0L } ?: daysPerWeek?.takeIf { it != 0L } ?: 4L
        val (mtbDiscipline, cyclingDiscipline) = PlanGenerator.goalDisciplines(goal.mtbDiscipline, goal.cyclingDiscipline)
        val lthr = thresholdHr?.takeIf { it != 0.0 }?.toLong()
        val families = sports.map { PlanBase.sportFamily(it) }

        val vdots = if ("running" in families) PlanGenerator.runningVdots(runningFitness, paceBests) else null
        val vdot = vdots?.plan
        val css = if ("swimming" in families) PlanBase.cssPaceSecPer100m(paceBests) else null
        val paces = if (vdots != null) PlanBase.vdotToPaces(vdot?.takeIf { it != 0.0 } ?: vdots.pace) else null
        var ewBase = PlanGenerator.effectiveWeeksBase("running", vdots?.capacity, baseEffectiveWeeks)
        // Continuous-run capacity implied by the load (fitness.py says why).
        val loadKm = if (paces != null) {
            ctl * 7 / PlanLoad.tssPerHour(PlanLoad.EASY_IF) * PlanGenerator.easyPaceKmh("running", paces)
        } else 0.0
        // No load history at all: each sport starts from its own answer, the
        // load from the sum of their CTLs, shared in those proportions (fitness.py).
        var ctl = ctl
        var atl = atl
        val noHistory = ctl < 1.0
        val levels: List<String?> = if (noHistory) {
            families.mapIndexed { i, fam ->
                if (activityFrequencies != null) PlanStart.frequencyFor(activityFrequencies, fam)
                else if (i == 0) activityFrequency else null
            }
        } else List(families.size) { null }
        val starts: List<PlanStart.Start?> = levels.map { if (noHistory) PlanStart.noHistoryStart(it) else null }
        val startCtls = starts.map { it?.ctl ?: 0.0 }
        var startCtl = 0.0
        for (c in startCtls) startCtl += c
        if (starts.any { it != null }) {
            ctl = PlanBase.pyMax(ctl, startCtl)
            atl = PlanBase.pyMax(atl, startCtl)
        }
        // Only running reads the capacity model; loadKm stays the measured load's.
        val runStart = if ("running" in families) starts[families.indexOf("running")] else null
        if (runStart != null && vdots?.capacity == null) ewBase = PlanBase.pyMax(ewBase, runStart.effectiveWeeks)

        fun ctxFor(sport: String, w: Long): PlanGenerator.SportCtx {
            val fam = PlanBase.sportFamily(sport)
            return PlanGenerator.SportCtx(
                family = fam, sport = sport,
                paces = if (fam == "running") paces else null, ftp = ftp,
                css = if (fam == "swimming") css else null, lthr = lthr,
                capacityKm = if (fam == "running") {
                    PlanGenerator.runningCapacityKm(ewBase + w * (dpw / 5.0), loadKm)
                } else null,
                mtbDiscipline = mtbDiscipline, cyclingDiscipline = cyclingDiscipline, imperial = imperial,
            )
        }

        val shares = when {
            sports.size == 1 -> listOf(1.0)
            startCtl > 0 -> startCtls.map { it / startCtl }
            else -> MultiSport.fitnessShares(families, history, today)
        }
        val workouts = mutableListOf<MutableMap<String, Any?>>()
        val counters = LinkedHashMap<String, Long>()
        val multiCounters = LinkedHashMap<String, MutableMap<String, Long>>()
        var buildIdx = 0L
        for ((wi, t) in weekTargets(ctl, atl, goal.ctlRampPerWeek, today, anchor).withIndex()) {
            val w = wi.toLong()
            val building = !t.recovery && t.ramp!! >= BUILD_PHASE_RAMP
            val phase = if (building) "build" else "base"
            if (sports.size > 1) {
                workouts += MultiSport.fitnessWeek(
                    t.weekStart, phase, t.tss, sports.map { ctxFor(it, w) }, shares, dpw, w, buildIdx,
                    multiCounters, today, NO_RACE_M,
                    intensities = starts.map { PlanStart.startIntensity(it, w) },
                    quality = levels.map { PlanStart.startQuality(it, w, noHistory) },
                )
            } else {
                workouts += PlanGenerator.generateWeek(
                    weekStart = t.weekStart, weekEnd = CivilDate.fromEpochDay(t.weekStart.epochDay + 6),
                    phase = phase, weekTss = t.tss, ctx = ctxFor(sports[0], w), buildIdx = buildIdx,
                    raceDistance = NO_RACE_M, isRaceWeek = false, daysPerWeek = dpw,
                    occurrenceCounters = counters, today = today,
                    intensity = PlanStart.startIntensity(starts[0], w), minLongMin = null,
                    weekNum = w, trimDays = true, quality = PlanStart.startQuality(levels[0], w, noHistory),
                )
            }
            buildIdx = if (building) buildIdx + 1 else 0L
        }
        return PlanGenerator.Generated(vdot, workouts)
    }
}
