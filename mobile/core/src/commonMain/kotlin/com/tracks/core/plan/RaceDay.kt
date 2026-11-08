// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.parse.PyMath
import kotlin.math.ceil

/**
 * A running race on race day — `calculators/plan/race_day.py`, held to it by
 * spec/fixtures/race_day.json (RaceDayFixtureTest).
 *
 * Today's VDOT plus the plan's remaining weekly gain at the rate the last
 * four weeks delivered it ([adherence]), and race morning's form after the
 * plan's remaining sessions. The race plan, the goal card and the guided
 * race read it, and the training plan's pace progression is the same
 * [vdotStep] × [adherence], so the race week paces the race the race plan
 * predicts. The reasoning — why a forecast, the transfer of each sport to
 * running, why the remaining load is the plan as written — is written once,
 * in the Python module's docstring and docs/running-pace-research.md; this
 * is the same arithmetic in the same order.
 */
object RaceDay {

    /** One of the plan's sessions, as `adherence` reads it. */
    data class Session(
        val date: CivilDate,
        val sport: String?,
        val workoutType: String?,
        val minutes: Double?,
        val complete: Boolean,
        /** A completed session that has the activity that completed it. */
        val recorded: Boolean,
    )

    /** One activity: its local day, sport and length. */
    data class Done(val date: CivilDate, val sport: String?, val minutes: Double?)

    const val WEEKLY_GAIN = 0.10
    const val MAX_GAIN = 1.5
    const val ADHERENCE_DAYS = 28
    const val PRIOR_MINUTES = 120.0
    const val MANUAL_CREDIT = 0.5
    /** `TRANSFER`: what a minute of each sport family is worth to running. */
    val TRANSFER: Map<String, Double> = mapOf(
        "running" to 1.0,
        "triathlon" to 0.6,
        "cycling" to 0.5, "mountain_biking" to 0.5, "nordic_skiing" to 0.5,
        "rowing" to 0.4,
        "swimming" to 0.35,
        "hiking" to 0.3, "paddling" to 0.3,
        "alpine_skiing" to 0.1, "strength" to 0.1, "generic" to 0.1,
        "climbing" to 0.05,
    )
    private val NOT_TRAINING = setOf("race")
    private const val FRESHNESS_PER_TSB = 0.001
    private const val FRESHNESS_CAP = 0.03

    /** `plan_weeks`: weeks from this Monday to the race week, inclusive. */
    fun planWeeks(today: CivilDate, raceDate: CivilDate): Long {
        val monday = today.epochDay - (today.epochDay + 3).mod(7L)
        return maxOf(1L, ceil((raceDate.epochDay - monday) / 7.0).toLong())
    }

    /** `vdot_step`: VDOT gained a week over a plan of [totalWeeks]. */
    fun vdotStep(totalWeeks: Long): Double = PlanBase.pyMin(WEEKLY_GAIN, MAX_GAIN / maxOf(totalWeeks, 1L))

    /** `race_day_vdot`: today's VDOT carried to the race week as the plan paces it. */
    fun raceDayVdot(vdot: Double, today: CivilDate, raceDate: CivilDate, adherence: Double = 1.0): Double {
        if (raceDate <= today) return vdot
        val weeks = planWeeks(today, raceDate)
        return vdot + (weeks - 1) * (vdotStep(weeks) * adherence)
    }

    /** `transfer`. */
    fun transfer(sport: String?): Double =
        TRANSFER[PlanBase.sportFamily((sport ?: "").lowercase())] ?: TRANSFER.getValue("generic")

    /** `adherence`: how much of the plan the last [ADHERENCE_DAYS] delivered, 0–1. */
    fun adherence(planned: List<Session>, done: List<Done>, today: CivilDate): Double {
        var start = today.epochDay - (ADHERENCE_DAYS - 1)
        val begun = planned.minOfOrNull { it.date.epochDay }
        if (begun != null && begun > start) start = begun
        val plannedMin = ArrayList<Double>()
        val doneMin = ArrayList<Double>()
        for (p in planned) {
            val d = p.date.epochDay
            if (d < start || d > today.epochDay || p.workoutType in NOT_TRAINING) continue
            if (d == today.epochDay && !p.complete) continue
            val minutes = (p.minutes ?: 0.0) * transfer(p.sport)
            plannedMin += minutes
            if (p.complete && !p.recorded) doneMin += minutes * MANUAL_CREDIT
        }
        for (a in done) {
            val d = a.date.epochDay
            if (d in start..today.epochDay) doneMin += (a.minutes ?: 0.0) * transfer(a.sport)
        }
        return PlanBase.pyMin(1.0, (sum(doneMin) + PRIOR_MINUTES) / (sum(plannedMin) + PRIOR_MINUTES))
    }

    /** `_sum`: in sorted order, so the replica's row order cannot move a bit. */
    private fun sum(values: List<Double>): Double {
        var total = 0.0
        for (v in values.sorted()) total += v
        return total
    }

    /** `planned_load_by_day`: each day's load from its sessions' (day, TSS). */
    fun plannedLoadByDay(sessions: List<Pair<CivilDate, Double>>): Map<Long, Double> {
        val byDay = LinkedHashMap<Long, ArrayList<Double>>()
        for ((d, tss) in sessions) byDay.getOrPut(d.epochDay) { ArrayList() } += tss
        return byDay.mapValues { (_, v) -> sum(v) }
    }

    /** `freshness_factor`: the race plan's time multiplier for a TSB. */
    fun freshnessFactor(tsb: Double?): Double {
        if (tsb == null) return 1.0
        return 1.0 - PlanBase.pyMax(-FRESHNESS_CAP, PlanBase.pyMin(FRESHNESS_CAP, tsb * FRESHNESS_PER_TSB))
    }

    /**
     * `race_day_tsb`: race morning's form — the load so far ([tssByDate], by
     * epoch day, as TrainingLoad.tssByDate gives it), then the plan's
     * remaining load ([plannedTss]) through the eve of the race. Null with no
     * load history; on race day, today's TSB.
     */
    fun raceDayTsb(tssByDate: Map<Long, Double>, plannedTss: Map<Long, Double>, today: CivilDate, raceDate: CivilDate): Double? {
        if (tssByDate.isEmpty()) return null
        val series = sortedMapOf<Long, Double>()
        for ((d, t) in tssByDate) if (d <= today.epochDay) series[d] = t
        series.putIfAbsent(today.epochDay, 0.0)
        val eve = maxOf(today.epochDay, raceDate.epochDay - 1)
        for ((d, t) in plannedTss) if (d > today.epochDay && d <= eve) series[d] = (series[d] ?: 0.0) + t
        series.putIfAbsent(eve, 0.0)
        val last = TrainingLoad.calculateCtlAtlTsb(series.map { (d, t) -> CivilDate.fromEpochDay(d) to t }).last()
        return PyMath.round(last.ctl - last.atl, 1)
    }
}
