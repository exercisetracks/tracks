// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.cycling

import com.tracks.core.parse.PyMath
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Road-cycling and mountain-biking helpers — ports of
 * backend/app/calculators/road_cycling.py and mtb.py, held to them by
 * spec/fixtures/cycling.json.
 *
 * The two server functions that read the active goal from the database
 * (`active_cycling_discipline`, `active_mtb_discipline`) are [activeDiscipline]
 * here, over a list of goals the caller already has: the phone holds its goals
 * as synced rows, not behind a query.
 */

/** The fields of a training goal the discipline lookup reads. */
data class GoalForDiscipline(
    val isActive: Boolean,
    val goalType: String?,
    val eventSport: String?,
    /** ISO date; null sorts last, as the server's `nulls_last()` does. */
    val eventDate: String?,
    val discipline: String?,
    val eventDistanceMeters: Double?,
)

internal fun normalizeSport(sport: String?): String = (sport ?: "").lowercase().replace(" ", "_")

/** Carbohydrate guidance by predicted race duration (Jeukendrup 2014). */
data class FuelingPlan(val carbsGPerH: Int, val reminderMin: Int, val notes: String)

object RoadCycling {
    val SPORTS = setOf("cycling", "road_biking", "gravel_cycling", "virtual_cycling", "indoor_cycling", "e_biking")
    val INDOOR_SPORTS = setOf("indoor_cycling", "virtual_cycling")
    val DISCIPLINES = listOf("road_race", "time_trial", "hill_climb", "criterium")
    const val DEFAULT_DISCIPLINE = "road_race"
    private const val INDOOR_TSS_MULTIPLIER = 1.10

    fun isRoadCycling(sport: String?) = normalizeSport(sport) in SPORTS
    fun isIndoorCycling(sport: String?) = normalizeSport(sport) in INDOOR_SPORTS

    fun inferDisciplineFromDistance(distanceM: Double?): String {
        if (distanceM == null || distanceM <= 0) return DEFAULT_DISCIPLINE
        val km = distanceM / 1000
        return when {
            km < 8 -> "criterium"
            km < 25 -> "time_trial"
            else -> "road_race"
        }
    }

    fun indoorTssMultiplier() = INDOOR_TSS_MULTIPLIER

    fun applyIndoorTssMultiplier(sport: String?, tss: Double?): Double? {
        if (tss == null || !isIndoorCycling(sport)) return tss
        return PyMath.round(tss * INDOOR_TSS_MULTIPLIER, 1)
    }

    fun activeDiscipline(goals: List<GoalForDiscipline>): String? =
        activeDiscipline(goals, SPORTS, ::inferDisciplineFromDistance)

    private val DRAFTING = mapOf("road_race" to 0.75, "criterium" to 0.70, "time_trial" to 1.00, "hill_climb" to 1.00)

    fun draftingFactor(discipline: String?): Double =
        DRAFTING[(discipline ?: DEFAULT_DISCIPLINE).lowercase()] ?: DRAFTING.getValue(DEFAULT_DISCIPLINE)

    private val HR_CEILING_PCT = mapOf("road_race" to 0.92, "criterium" to 1.00, "time_trial" to 1.02, "hill_climb" to 1.00)

    fun raceHrCeiling(discipline: String?, lthr: Int?): Int? = hrCeiling(discipline, lthr, HR_CEILING_PCT, DEFAULT_DISCIPLINE)

    fun fuelingPlan(predictedSeconds: Double?): FuelingPlan {
        val hours = (predictedSeconds ?: 0.0) / 3600
        return when {
            hours <= 1.0 -> FuelingPlan(30, 30, "Short race — top up before the start; one mid-race feed is plenty.")
            hours <= 2.0 -> FuelingPlan(60, 25, "Aim for 60 g/h from minute 30 — one gel/chew every ~20–25 min.")
            hours <= 4.0 -> FuelingPlan(80, 20, "80 g/h mixed carbs (glucose + fructose 2:1). Practice in training.")
            else -> FuelingPlan(90, 20, "Ultra fueling: 90 g/h, mixed sources, include real food after hour 3.")
        }
    }

    fun gradeMultiplier(grad: Double): Double {
        if (grad >= 0) {
            val pct = grad * 100
            return 1.0 + 0.055 * pct + 0.0015 * pct * pct
        }
        val pct = -grad * 100
        return 1.0 - 0.45 * (1.0 - exp(-pct / 6.0))
    }

    fun hillClimbTargetWPerKg(predictedSeconds: Double?): Double {
        val minutes = max(1.0, (predictedSeconds ?: 0.0) / 60)
        return when {
            minutes <= 3 -> 5.0
            minutes <= 5 -> 4.3
            minutes <= 12 -> 4.0
            minutes <= 20 -> 3.7
            minutes <= 40 -> 3.4
            minutes <= 60 -> 3.1
            minutes <= 120 -> 2.8
            minutes <= 240 -> 2.5
            else -> 2.2
        }
    }
}

/** One lap of an MTB race plan built from HR and time alone. */
data class MtbLap(
    val lap: Int,
    val distanceM: Long,
    val targetSecPerKm: Double,
    val targetPace: String,
    val gradient: Double,
    val gradeMultiplier: Double,
    val gradeAdjSec: Double,
    val gradeAdjPace: String,
    val cumulativeKm: Double,
    val hrCeiling: Int?,
)

/** A stretch of course with a constant gradient, as the race plan stores it. */
data class CourseSegment(val distanceM: Double, val gradient: Double = 0.0)

object Mtb {
    val SPORTS = setOf("mountain_biking", "trail_biking")
    val DISCIPLINES = listOf("xco", "xcm", "enduro", "trail")
    const val DEFAULT_DISCIPLINE = "trail"

    fun isMtb(sport: String?): Boolean = !sport.isNullOrEmpty() && normalizeSport(sport) in SPORTS

    fun inferDisciplineFromDistance(distanceM: Double?): String {
        if (distanceM == null || distanceM <= 0) return DEFAULT_DISCIPLINE
        return if (distanceM / 1000 < 25) "xco" else "xcm"
    }

    private val TSS_MULTIPLIER = mapOf("xco" to 1.00, "xcm" to 1.10, "enduro" to 1.20, "trail" to 1.10)

    fun tssMultiplier(discipline: String?): Double =
        if (discipline.isNullOrEmpty()) TSS_MULTIPLIER.getValue(DEFAULT_DISCIPLINE)
        else TSS_MULTIPLIER[discipline.lowercase()] ?: TSS_MULTIPLIER.getValue(DEFAULT_DISCIPLINE)

    fun activeDiscipline(goals: List<GoalForDiscipline>): String? =
        activeDiscipline(goals, SPORTS, ::inferDisciplineFromDistance)

    /** `apply_mtb_tss_multiplier`, with the active discipline looked up from [goals]. */
    fun applyTssMultiplier(sport: String?, tss: Double?, goals: List<GoalForDiscipline>): Double? {
        if (tss == null || !isMtb(sport)) return tss
        return PyMath.round(tss * tssMultiplier(activeDiscipline(goals)), 1)
    }

    private val HR_CEILING_PCT = mapOf("xco" to 0.97, "xcm" to 0.97, "enduro" to 1.00, "trail" to 0.95)

    fun raceHrCeiling(discipline: String?, lthr: Int?): Int? = hrCeiling(discipline, lthr, HR_CEILING_PCT, DEFAULT_DISCIPLINE)

    fun fmtPaceSecPerKm(secPerKm: Double): String {
        if (secPerKm <= 0) return "—"
        // Python's float `//`, not floor(x / 60) — they differ right at the boundary.
        var m = PyMath.divmod(secPerKm, 60.0).first.toLong()
        var s = PyMath.roundToLong(secPerKm - m * 60)
        if (s == 60L) { m += 1; s = 0 }
        return "$m:${s.toString().padStart(2, '0')}/km"
    }

    private val COURSE_TYPE_GRAD_AMPLITUDE = mapOf("flat" to 0.000, "rolling" to 0.025, "hilly" to 0.055, "mountainous" to 0.090)

    fun gradeMultiplier(grad: Double): Double {
        if (grad >= 0) {
            val pct = grad * 100
            return 1.0 + 0.035 * pct + 0.0009 * pct * pct
        }
        val pct = -grad * 100
        return 1.0 - 0.14 * (1.0 - exp(-pct / 3.5))
    }

    internal fun synthesizeLapGradients(nLaps: Int, courseType: String?): List<Double> {
        val amp = COURSE_TYPE_GRAD_AMPLITUDE[(courseType ?: "rolling").lowercase()] ?: 0.025
        if (amp == 0.0 || nLaps <= 0) return List(max(1, nLaps)) { 0.0 }
        return List(nLaps) { i -> amp * sin((i.toDouble() / max(nLaps - 1, 1)) * 2 * PI * 2.5 + PI / 4) }
    }

    /**
     * Per-lap pacing for an MTB race plan with no FTP. Returns the laps and the
     * sum of their times, which the grade penalties make differ slightly from
     * [totalSec].
     */
    fun hrOnlyLaps(
        distanceM: Double,
        totalSec: Double,
        lapKm: Double = 1.0,
        splitSpread: Double = 0.0,
        discipline: String? = null,
        lthr: Int? = null,
        courseSegments: List<CourseSegment>? = null,
        courseType: String? = null,
    ): Pair<List<MtbLap>, Double> {
        if (distanceM <= 0 || totalSec <= 0 || lapKm <= 0) return emptyList<MtbLap>() to 0.0
        val maxSpread = 0.08
        val nFull = (distanceM / (lapKm * 1000)).toLong().toInt()
        val lastM = distanceM - nFull * lapKm * 1000
        var nLaps = nFull + if (lastM > 10) 1 else 0
        if (nLaps == 0) nLaps = 1
        val lapDists = List(nLaps) { i -> if (i == nFull && lastM > 10) lastM else lapKm * 1000 }

        var lapGrads = MutableList(nLaps) { 0.0 }.toList()
        if (!courseSegments.isNullOrEmpty()) {
            val grads = MutableList(nLaps) { 0.0 }
            var segCursor = 0
            var segConsumed = 0.0
            for ((lapI, lapDist) in lapDists.withIndex()) {
                var lapGain = 0.0
                var remaining = lapDist
                while (remaining > 0 && segCursor < courseSegments.size) {
                    val seg = courseSegments[segCursor]
                    val avail = seg.distanceM - segConsumed
                    val take = min(remaining, avail)
                    lapGain += seg.gradient * take
                    remaining -= take
                    segConsumed += take
                    if (segConsumed >= seg.distanceM - 0.001) {
                        segCursor += 1
                        segConsumed = 0.0
                    }
                }
                grads[lapI] = if (lapDist > 0) lapGain / lapDist else 0.0
            }
            lapGrads = grads
        } else if (courseType != null && courseType.lowercase() != "flat") {
            lapGrads = synthesizeLapGradients(nLaps, courseType)
        }

        val mid = (nLaps - 1) / 2.0
        val slope = -splitSpread * maxSpread * 2.0 / max(nLaps - 1, 1)
        val ramp = List(nLaps) { i -> 1.0 + slope * (i - mid) }
        val hrCeiling = raceHrCeiling(discipline, lthr)
        val lapMults = lapGrads.map(::gradeMultiplier)
        // Python's sum() is compensated (3.12); PyMath.sum matches it.
        val weightedDistM = PyMath.sum(List(nLaps) { i -> lapDists[i] * lapMults[i] * ramp[i] })
        val baseFlatPace = if (weightedDistM > 0) totalSec / (weightedDistM / 1000) else 0.0

        val laps = ArrayList<MtbLap>(nLaps)
        var cumKm = 0.0
        var totalAcc = 0.0
        for (i in 0 until nLaps) {
            val target = max(60.0, baseFlatPace * lapMults[i] * ramp[i])
            val flat = baseFlatPace * ramp[i]
            val lapSec = lapDists[i] / 1000 * target
            totalAcc += lapSec
            cumKm += lapDists[i] / 1000
            laps += MtbLap(
                lap = i + 1,
                distanceM = PyMath.roundToLong(lapDists[i]),
                targetSecPerKm = PyMath.round(target, 1),
                targetPace = fmtPaceSecPerKm(target),
                gradient = PyMath.round(lapGrads[i], 4),
                gradeMultiplier = PyMath.round(lapMults[i], 4),
                gradeAdjSec = PyMath.round(flat, 1),
                gradeAdjPace = fmtPaceSecPerKm(flat),
                cumulativeKm = PyMath.round(cumKm, 2),
                hrCeiling = hrCeiling,
            )
        }
        return laps to totalAcc
    }

    fun fuelingPlan(predictedSeconds: Double?): FuelingPlan {
        val hours = (predictedSeconds ?: 0.0) / 3600
        return when {
            hours <= 1.0 -> FuelingPlan(30, 30, "Short race — top up before the start; one mid-race feed is plenty.")
            hours <= 2.0 -> FuelingPlan(60, 25, "Aim for 60 g/h from minute 30 — one gel / chew every ~20–25 min.")
            hours <= 4.0 -> FuelingPlan(80, 20, "80 g/h mixed carbs (glucose + fructose 2:1). Practice this in training.")
            else -> FuelingPlan(90, 20, "Ultra fueling: 90 g/h, mixed sources, include some real food after hour 3.")
        }
    }
}

private fun hrCeiling(discipline: String?, lthr: Int?, pcts: Map<String, Double>, default: String): Int? {
    if (lthr == null || lthr <= 0) return null
    val pct = pcts[(discipline ?: default).lowercase()] ?: pcts.getValue(default)
    return PyMath.roundToLong(lthr * pct).toInt()
}

/**
 * The server's goal query: active event goals in [sports], earliest event
 * first (no date last); the goal's own discipline, else one inferred from its
 * distance. Ties on date keep list order, where Postgres would leave it to
 * chance — callers pass goals in a stable order.
 */
private fun activeDiscipline(
    goals: List<GoalForDiscipline>,
    sports: Set<String>,
    infer: (Double?) -> String,
): String? {
    val goal = goals
        .filter { it.isActive && it.goalType == "event" && it.eventSport in sports }
        .sortedWith(compareBy(nullsLast()) { it.eventDate })
        .firstOrNull() ?: return null
    return goal.discipline?.takeIf { it.isNotEmpty() }?.lowercase() ?: infer(goal.eventDistanceMeters)
}
