// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.parse.PyMath
import com.tracks.core.plan.PlanBase.fmtDistM
import com.tracks.core.plan.PlanBase.fmtPace
import com.tracks.core.plan.PlanBase.walkCooldown
import com.tracks.core.plan.PlanBase.walkWarmup

/**
 * Running workout builders — a port of `backend/app/calculators/plan/running.py`.
 * The research each prescription rests on is cited there, next to the Python
 * this mirrors line for line.
 */
internal object Running {

    private val EASY_T1_VAR = longArrayOf(0, +5, -5, +3, +8, -3)
    private val EASY_T2_VAR = listOf(2L to 60L, 2L to 45L, 3L to 60L, 2L to 75L, 3L to 45L, 2L to 90L)
    private val EASY_T3_VAR = listOf(0L to 0L, +1L to -15L, 0L to +15L, -1L to 0L, +1L to +10L, -1L to -10L)
    private val FARTLEK_VAR = listOf(3L to 2L, 2L to 2L, 3L to 1L, 4L to 2L, 2L to 1L, 4L to 3L)
    private val INTERVAL_VAR = listOf(0L to 0L, +1L to -15L, 0L to +15L, -1L to 0L, +1L to +10L, -1L to -10L)
    private val TEMPO_VAR = listOf(15L to 10L, 12L to 10L, 15L to 12L, 10L to 10L, 12L to 12L, 10L to 12L)
    private val HILL_SPRINT_VAR = listOf(
        Triple(10L, 10L, 90L), Triple(12L, 8L, 60L), Triple(8L, 15L, 90L),
        Triple(10L, 12L, 60L), Triple(6L, 20L, 120L), Triple(12L, 10L, 60L),
    )

    private fun v(variation: Long) = variation.mod(6L).toInt()
    private fun recovery(paces: Map<String, Double>) = paces["recovery"] ?: paces.getValue("easy")

    /** `_run_with_breaks`: continuous, split, or run/walk reps by capacity. */
    fun withBreaks(
        durationMin: Long, paces: Map<String, Double>, capacityKm: Double,
        variation: Long = 0, structuralMin: Long? = null, imperial: Boolean = false,
    ): List<Step> {
        val pace = paces.getValue("easy")
        val ep = fmtPace(pace, imperial)
        val checkMin = structuralMin ?: durationMin
        val sessionKm = checkMin / 60.0 * (3600 / pace)
        val actualKm = durationMin / 60.0 * (3600 / pace)
        val ratio = sessionKm / capacityKm
        val i = v(variation)

        if (ratio <= 0.85) {
            val varMin = maxOf(15L, durationMin + EASY_T1_VAR[i])
            return listOf(
                walkWarmup(),
                step("type" to "run", "duration_min" to varMin, "pace" to "easy",
                    "note" to "Conversational pace · $ep (Zone 2)"),
                walkCooldown(),
            )
        }

        if (ratio <= 1.5) {
            val (varReps, varRest) = EASY_T2_VAR[i]
            // As long as the runner can run continuously, not a flat 2 km (running.py).
            val segCap = maxOf(2000L, PyMath.roundToLong(capacityKm * 850 / 100) * 100)
            val segM = maxOf(400L, minOf(segCap, PyMath.roundToLong(actualKm * 1000 / varReps / 100) * 100))
            return listOf(
                walkWarmup(),
                step(
                    "type" to "interval_set", "reps" to varReps, "distance_m" to segM,
                    "rest_sec" to varRest, "pace" to "easy",
                    "note" to "$varReps× ${fmtDistM(segM, imperial)} at $ep / $varRest s walk",
                ),
                walkCooldown(),
            )
        }

        val baseDistM = maxOf(400L, minOf(2000L, PyMath.roundToLong(capacityKm * 4) * 100))
        val deficitRatio = (sessionKm - capacityKm) / sessionKm
        val baseWalkSec = maxOf(30L, minOf(120L, PyMath.roundToLong(90 * deficitRatio / 30) * 30))
        val runSec = baseDistM / 1000.0 * pace
        val baseReps = maxOf(3L, (durationMin * 60 / (runSec + baseWalkSec)).toLong())
        val (dReps, dRest) = EASY_T3_VAR[i]
        val varReps = maxOf(2L, baseReps + dReps)
        val varRest = maxOf(30L, minOf(120L, baseWalkSec + dRest))
        val varDistM = maxOf(200L, minOf(2000L, PyMath.roundToLong((baseReps * baseDistM).toDouble() / varReps / 100) * 100))
        return listOf(
            walkWarmup(),
            step(
                "type" to "interval_set", "reps" to varReps, "distance_m" to varDistM,
                "rest_sec" to varRest, "pace" to "easy",
                "note" to "$varReps× ${fmtDistM(varDistM, imperial)} at $ep / $varRest s walk",
            ),
            walkCooldown(),
        )
    }

    /** `_run_recovery`: below VT1, capped at 40 min. */
    fun recoveryRun(durationMin: Long, paces: Map<String, Double>, variation: Long = 0, imperial: Boolean = false): List<Step> {
        val rp = fmtPace(recovery(paces), imperial)
        val runMin = maxOf(20L, minOf(40L, durationMin + EASY_T1_VAR[v(variation)]))
        return listOf(
            walkWarmup(),
            step("type" to "run", "duration_min" to runMin, "pace" to "recovery",
                "note" to "Recovery jog · $rp · below VT1 · fully conversational"),
            walkCooldown(),
        )
    }

    /** `_run_easy`. */
    fun easy(
        durationMin: Long, paces: Map<String, Double>, capacityKm: Double? = null,
        variation: Long = 0, structuralMin: Long? = null, imperial: Boolean = false,
    ): List<Step> {
        if (capacityKm != null) {
            return withBreaks(durationMin, paces, capacityKm, variation, structuralMin, imperial)
        }
        val p = fmtPace(paces.getValue("easy"), imperial)
        return listOf(
            walkWarmup(),
            step("type" to "run", "duration_min" to durationMin, "pace" to "easy",
                "note" to "Conversational pace · $p (Zone 2)"),
            walkCooldown(),
        )
    }

    /** `_MP_BLOCK_MIN_RACE_M`: the shortest race whose long run has a marathon-pace block. */
    private const val MP_BLOCK_MIN_RACE_M = 21000.0

    /**
     * `_run_long`: marathon-pace block in build/peak of a half marathon or
     * longer (a 5K/10K long run stays easy — running.py says why), run/walk
     * past capacity.
     */
    fun long(
        totalMin: Long, phase: String, paces: Map<String, Double>, capacityKm: Double? = null,
        structuralMin: Long? = null, imperial: Boolean = false, raceDistanceM: Double = 42195.0,
    ): List<Step> {
        val mp = fmtPace(paces.getValue("marathon"), imperial)
        val ep = fmtPace(paces.getValue("easy"), imperial)

        if (capacityKm != null) {
            val pace = paces.getValue("easy")
            val checkMin = structuralMin ?: totalMin
            val sessionKm = checkMin / 60.0 * (3600 / pace)
            if (sessionKm > capacityKm * 1.05) {
                return withBreaks(totalMin, paces, capacityKm, structuralMin = structuralMin, imperial = imperial)
            }
        }

        if ((phase == "build" || phase == "peak") && totalMin >= 70 && raceDistanceM >= MP_BLOCK_MIN_RACE_M) {
            val mpMin = minOf(30L, (totalMin * 0.28).toLong())
            val warmup = ((totalMin - mpMin) * 0.75).toLong()
            val cooldown = totalMin - mpMin - warmup
            return listOf(
                walkWarmup(),
                step("type" to "run", "duration_min" to warmup, "pace" to "easy", "note" to "Easy warm-up · $ep"),
                step("type" to "run", "duration_min" to mpMin, "pace" to "marathon", "note" to "Marathon goal pace · $mp"),
                step("type" to "run", "duration_min" to cooldown, "pace" to "easy", "note" to "Easy cool-down · $ep"),
                walkCooldown(),
            )
        }
        return listOf(
            walkWarmup(),
            step("type" to "run", "duration_min" to totalMin, "pace" to "easy",
                "note" to "Easy conversational pace · $ep"),
            walkCooldown(),
        )
    }

    /** `_run_tempo`. */
    fun tempo(totalMin: Long, paces: Map<String, Double>, variation: Long = 0, imperial: Boolean = false): List<Step> {
        val rp = fmtPace(recovery(paces), imperial)
        val tp = fmtPace(paces.getValue("threshold"), imperial)
        val (warmup, cooldown) = TEMPO_VAR[v(variation)]
        val tempo = maxOf(15L, totalMin - warmup - cooldown)
        return listOf(
            walkWarmup(),
            step("type" to "warmup", "duration_min" to warmup, "pace" to "recovery", "note" to "Easy jog · $rp"),
            step("type" to "run", "duration_min" to tempo, "pace" to "threshold", "note" to "Comfortably hard · $tp (threshold)"),
            step("type" to "cooldown", "duration_min" to cooldown, "pace" to "recovery", "note" to "Easy jog · $rp"),
            walkCooldown(),
        )
    }

    /** `_run_intervals`: 400 → 800 → 1000 → 1500 m ladder over the build. */
    fun intervals(buildIdx: Long, paces: Map<String, Double>, variation: Long = 0, imperial: Boolean = false): List<Step> {
        val ip = fmtPace(paces.getValue("interval"), imperial)
        val tp = fmtPace(paces.getValue("threshold"), imperial)
        val rp = fmtPace(recovery(paces), imperial)
        val (baseReps, dist, baseRest, zone) = when {
            buildIdx <= 1 -> Quad(6L, 400L, 90L, "interval")
            buildIdx <= 3 -> Quad(6L, 800L, 90L, "interval")
            buildIdx <= 5 -> Quad(5L, 1000L, 90L, "threshold")
            else -> Quad(4L, 1500L, 120L, "interval")
        }
        val paceStr = if (zone == "interval") ip else tp
        val (dReps, dRest) = INTERVAL_VAR[v(variation)]
        val reps = maxOf(2L, baseReps + dReps)
        val rest = maxOf(30L, baseRest + dRest)
        val desc = "$reps× ${fmtDistM(dist, imperial)} at $paceStr / $rest s rest"
        return listOf(
            walkWarmup(),
            step("type" to "warmup", "duration_min" to 15L, "pace" to "recovery", "note" to "Easy jog · $rp"),
            step("type" to "interval_set", "reps" to reps, "distance_m" to dist,
                "rest_sec" to rest, "pace" to zone, "note" to desc),
            step("type" to "cooldown", "duration_min" to 10L, "pace" to "recovery", "note" to "Easy jog · $rp"),
            walkCooldown(),
        )
    }

    /** `_run_race_pace`: pace and structure by race distance. */
    fun racePace(paces: Map<String, Double>, raceDistanceM: Double, imperial: Boolean = false): List<Step> {
        val ep = fmtPace(paces.getValue("easy"), imperial)
        val (reps, dist, zone, note) = when {
            raceDistanceM > 80000 -> {
                val pp = fmtPace(paces.getValue("easy"), imperial)
                Quad(3L, 2000L, "easy", "${fmtDistM(2000, imperial)} at ultra effort · $pp (easy, sustained)")
            }
            raceDistanceM > 42200 -> {
                val pp = fmtPace(paces.getValue("marathon"), imperial)
                Quad(3L, 2000L, "marathon", "${fmtDistM(2000, imperial)} at ultra race effort · $pp")
            }
            raceDistanceM >= 21000 -> {
                val pp = fmtPace(paces.getValue("marathon"), imperial)
                Quad(4L, 2000L, "marathon", "${fmtDistM(2000, imperial)} at marathon goal pace · $pp")
            }
            else -> {
                val pp = fmtPace(paces.getValue("threshold"), imperial)
                Quad(5L, 1000L, "threshold", "${fmtDistM(1000, imperial)} at race pace · $pp")
            }
        }
        return listOf(
            walkWarmup(),
            step("type" to "warmup", "duration_min" to 15L, "pace" to "easy", "note" to "Easy jog · $ep"),
            step("type" to "interval_set", "reps" to reps, "distance_m" to dist, "rest_sec" to 120L,
                "pace" to zone, "note" to note),
            step("type" to "cooldown", "duration_min" to 10L, "pace" to "easy", "note" to "Easy jog · $ep"),
            walkCooldown(),
        )
    }

    /** `_run_fartlek`. */
    fun fartlek(durationMin: Long, paces: Map<String, Double>, variation: Long = 0, imperial: Boolean = false): List<Step> {
        val rp = fmtPace(recovery(paces), imperial)
        val tp = fmtPace(paces.getValue("threshold"), imperial)
        val main = maxOf(20L, durationMin - 15)
        val (hardMin, easyMin) = FARTLEK_VAR[v(variation)]
        val block = hardMin + easyMin
        val reps = maxOf(2L, main.floorDiv(block))
        return listOf(
            walkWarmup(),
            step("type" to "warmup", "duration_min" to 10L, "pace" to "recovery", "note" to "Easy jog · $rp"),
            step(
                "type" to "fartlek", "duration_min" to reps * block,
                "hard_min" to hardMin, "easy_min" to easyMin, "reps" to reps,
                "pace" to "threshold",
                "note" to "$hardMin min hard ($tp) / $easyMin min easy — $reps rounds",
            ),
            step("type" to "cooldown", "duration_min" to 5L, "pace" to "recovery", "note" to "Easy jog · $rp"),
            walkCooldown(),
        )
    }

    /** `_run_short_quality`: strides. */
    fun shortQuality(paces: Map<String, Double>, imperial: Boolean = false): List<Step> {
        val ep = fmtPace(paces.getValue("easy"), imperial)
        val rp = fmtPace(paces.getValue("repetition"), imperial)
        return listOf(
            walkWarmup(),
            step("type" to "run", "duration_min" to 30L, "pace" to "easy", "note" to "Easy jog · $ep"),
            step("type" to "interval_set", "reps" to 6L, "distance_m" to 100L, "rest_sec" to 60L,
                "pace" to "repetition", "note" to "${fmtDistM(100, imperial)} stride · $rp · full recovery between"),
            walkCooldown(),
        )
    }

    /** `_run_hill_sprints`. */
    fun hillSprints(paces: Map<String, Double>, variation: Long = 0, imperial: Boolean = false): List<Step> {
        val ep = fmtPace(paces.getValue("easy"), imperial)
        val (reps, workSec, restSec) = HILL_SPRINT_VAR[v(variation)]
        return listOf(
            walkWarmup(),
            step("type" to "warmup", "duration_min" to 15L, "pace" to "easy",
                "note" to "Easy jog to a moderate hill (~6-10% grade) · $ep"),
            step(
                "type" to "effort_set", "reps" to reps, "duration_sec_each" to workSec, "rest_sec" to restSec,
                "intensity" to "anaerobic",
                "note" to "$reps× ${workSec}s max-effort hill sprint · walk/jog back recovery. " +
                    "Focus on high knees and powerful arm drive.",
            ),
            step("type" to "cooldown", "duration_min" to 10L, "pace" to "easy", "note" to "Easy jog back · $ep"),
            walkCooldown(),
        )
    }

    /** `_run_brick`: the run straight off a triathlon brick's ride. */
    fun brick(durationMin: Long, phase: String, paces: Map<String, Double>, raceDistanceM: Double,
              imperial: Boolean = false): List<Step> {
        var dur = maxOf(10L, minOf(30L, durationMin.floorDiv(2L)))
        if (phase == "taper") dur = minOf(dur, 15L)
        val ep = fmtPace(paces.getValue("easy"), imperial)
        val settle = 5L
        val main = maxOf(5L, dur - settle)
        val zone: String
        val note: String
        if (phase == "build" || phase == "peak") {
            zone = if (raceDistanceM <= 10000) "threshold" else "marathon"
            note = "Race-run pace · ${fmtPace(paces.getValue(zone), imperial)} — hold it through heavy legs"
        } else {
            zone = "easy"
            note = "Settle into easy running · $ep"
        }
        return listOf(
            step("type" to "run", "duration_min" to settle, "pace" to "easy",
                "note" to "Off the bike: quick short steps, find your cadence · $ep"),
            step("type" to "run", "duration_min" to main, "pace" to zone, "note" to note),
            walkCooldown(),
        )
    }
}

/** A four-way destructurable tuple, for the Python `a, b, c, d = ...` ladders. */
internal data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
