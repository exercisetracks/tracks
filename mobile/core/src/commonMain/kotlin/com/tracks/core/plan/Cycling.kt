// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.plan.PlanBase.pwrZone
import com.tracks.core.plan.PlanBase.targetNote

/**
 * Road-cycling workout builders — a port of
 * `backend/app/calculators/plan/cycling.py` (Friel HR zones with a Coggan
 * power overlay when FTP is known; sources cited there).
 */
internal object Cycling {

    private val SS_VAR = listOf(2L to 20L, 3L to 15L, 4L to 12L, 2L to 25L, 3L to 20L, 4L to 10L)
    private val THR_VAR = listOf(
        Triple(2L, 20L, 5L), Triple(3L, 15L, 5L), Triple(4L, 10L, 3L),
        Triple(5L, 8L, 2L), Triple(3L, 20L, 4L), Triple(2L, 25L, 6L),
    )
    private val VO2_VAR = listOf(5L to 5L, 8L to 3L, 4L to 6L, 6L to 4L, 5L to 4L, 7L to 3L)
    private val ANAEROBIC_VAR = listOf(
        Triple(6L, 180L, 180L), Triple(10L, 30L, 30L), Triple(8L, 20L, 10L),
        Triple(8L, 60L, 240L), Triple(6L, 120L, 180L), Triple(12L, 30L, 30L),
    )
    private val SPRINT_VAR = listOf(
        Triple(5L, 10L, 290L), Triple(6L, 30L, 270L), Triple(8L, 15L, 180L),
        Triple(4L, 60L, 360L), Triple(10L, 10L, 120L), Triple(6L, 20L, 240L),
    )
    private val ENDURANCE_VAR = longArrayOf(0, +10, -10, +5, +15, -5)
    private val MICRO_VAR = listOf(13L to 3L, 10L to 4L, 15L to 3L, 13L to 2L, 12L to 3L, 14L to 3L)

    private fun v(variation: Long) = variation.mod(6L).toInt()

    /** `m:ss`, as the Python's `f"{s // 60}:{s % 60:02d}"` on a non-negative int. */
    private fun mss(sec: Long) = "${sec.floorDiv(60L)}:${sec.mod(60L).toString().padStart(2, '0')}"

    fun endurance(durationMin: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val dur = maxOf(45L, durationMin + ENDURANCE_VAR[v(variation)])
        val note = targetNote(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (conversational)")
        return listOf(step("type" to "ride", "duration_min" to dur, "intensity" to "endurance",
            "note" to "Z2 endurance · $note. Smooth pedalling, hold a conversation."))
    }

    fun easySpin(durationMin: Long, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val dur = maxOf(20L, minOf(60L, durationMin))
        val note = targetNote(0.50, 0.81, lthr, 0.40, 0.55, ftp, "2/10 (legs only)")
        return listOf(step("type" to "ride", "duration_min" to dur, "intensity" to "recovery",
            "note" to "Recovery spin · $note. Light gear, fast cadence, no surges."))
    }

    fun tempo(durationMin: Long, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val warmup = 15L
        val cooldown = 10L
        val tempo = maxOf(20L, durationMin - warmup - cooldown)
        val note = targetNote(0.89, 0.93, lthr, 0.76, 0.88, ftp, "5/10 (moderate-hard)")
        return listOf(
            step("type" to "warmup", "duration_min" to warmup, "intensity" to "endurance",
                "note" to "Easy spin + 2× 30 s spin-ups to open the legs"),
            step("type" to "ride", "duration_min" to tempo, "intensity" to "tempo",
                "note" to "Z3 tempo · $note. Sustainable; you can't sing."),
            step("type" to "cooldown", "duration_min" to cooldown, "intensity" to "endurance",
                "note" to "Easy spin back home"),
        )
    }

    fun sweetSpot(durationMin: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (reps, dur) = SS_VAR[v(variation)]
        val rest = 5L
        val note = targetNote(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10 (comfortably hard)")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min build to threshold"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to rest,
                "intensity" to "sweet_spot",
                "note" to "$reps× $dur min sweet spot · $note · $rest min easy between"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun threshold(buildIdx: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        var (reps, dur, rest) = THR_VAR[v(variation)]
        if (buildIdx >= 4 && reps < 4) reps += 1
        val note = targetNote(1.00, 1.02, lthr, 0.95, 1.00, ftp, "7/10 (1-hour race effort)")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min ramping to threshold"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to rest,
                "intensity" to "threshold",
                "note" to "$reps× $dur min at LT · $note · $rest min easy between"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun vo2(buildIdx: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        var (reps, dur) = VO2_VAR[v(variation)]
        if (buildIdx >= 5 && reps < 6) reps += 1
        val note = targetNote(1.03, 1.06, lthr, 1.05, 1.20, ftp, "8/10 (VO2max, hard)")
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 4× 30 s spin-ups + 1× 2 min build to threshold"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to dur,
                "intensity" to "vo2",
                "note" to "$reps× $dur min at VO2max · $note · $dur min easy between"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun microBursts(lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (repsPerSet, sets) = MICRO_VAR[v(variation)]
        val target = pwrZone(1.05, 1.30, ftp) ?: "all-out aerobic effort"
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min build to threshold"),
            step("type" to "effort_set", "reps" to sets, "duration_sec_each" to repsPerSet * 45, "rest_sec" to 180L,
                "intensity" to "micro_bursts",
                "note" to "$sets× ($repsPerSet× 30 s on / 15 s off) · target $target on the 'on' segments. " +
                    "3 min easy spin between sets. (Rønnestad 30/15 — superior VO2max stimulus.)"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun overUnders(lthr: Long? = null, ftp: Double? = null): List<Step> {
        val over = targetNote(1.03, 1.05, lthr, 1.02, 1.08, ftp, "8/10")
        val under = targetNote(0.93, 0.96, lthr, 0.88, 0.92, ftp, "6/10")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min ramping to threshold"),
            step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 10L, "rest_min" to 5L,
                "intensity" to "over_under",
                "note" to "3× 10 min over-unders · alternate 2 min OVER ($over) / 1 min UNDER ($under). " +
                    "5 min easy between sets."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun anaerobic(lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (reps, workSec, restSec) = ANAEROBIC_VAR[v(variation)]
        val target = pwrZone(1.20, 1.50, ftp) ?: "Z6 anaerobic — all out"
        val label = when (workSec) {
            20L -> "Tabata 20/10"
            30L -> "30/30 × $reps"
            else -> "$reps× ${workSec}s"
        }
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 5× 30 s spin-ups + 1× 2 min build to VO2"),
            step("type" to "effort_set", "reps" to reps, "duration_sec_each" to workSec, "rest_sec" to restSec,
                "intensity" to "anaerobic",
                "note" to "$label · target $target on each rep · " +
                    "${mss(restSec)} between. Race-specific anaerobic capacity."),
            step("type" to "cooldown", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin until HR settles"),
        )
    }

    fun sprint(lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (reps, workSec, restSec) = SPRINT_VAR[v(variation)]
        val target = pwrZone(1.50, 3.00, ftp) ?: "absolute maximum power · 9.5/10 RPE"
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 20 s spin-ups + 2× 5 s seated sprints to prime"),
            step("type" to "effort_set", "reps" to reps, "duration_sec_each" to workSec, "rest_sec" to restSec,
                "intensity" to "neuromuscular",
                "note" to "$reps× $workSec s sprints · target $target. " +
                    "Full recovery (${mss(restSec)}) — every rep should be maximal."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun sustainedClimb(durationMin: Long, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val (reps, dur, rest) = if (durationMin < 70) Triple(2L, 15L, 5L) else Triple(3L, 10L, 4L)
        val note = targetNote(0.97, 1.00, lthr, 0.93, 0.98, ftp, "7.5/10 (steady climbing)")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min progressive build"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to rest,
                "intensity" to "threshold",
                "note" to "$reps× $dur min sustained climb · $note. Seated, smooth cadence " +
                    "70-85 rpm. Find a 5%+ climb or simulate on the trainer."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun ttPace(durationMin: Long, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val (reps, dur, rest) = if (durationMin < 70) Triple(1L, 20L, 0L) else Triple(2L, 20L, 6L)
        val note = targetNote(1.00, 1.04, lthr, 0.95, 1.05, ftp, "8/10 (TT effort)")
        val intro = if (reps == 1L) "Single 20-min TT-pace effort" else "$reps× $dur min at TT pace"
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min build + 1× 5 min at threshold to open"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to rest,
                "intensity" to "race_pace",
                "note" to "$intro · $note. Stay in aero position the entire effort — " +
                    "position cost is part of the workout."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun long(durationMin: Long, phase: String, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val z2 = targetNote(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (conversational)")
        if ((phase == "build" || phase == "peak") && durationMin >= 120) {
            val ssMin = minOf(45L, (durationMin * 0.25).toLong())
            val warmup = ((durationMin - ssMin) * 0.6).toLong()
            val cooldown = durationMin - ssMin - warmup
            val ss = targetNote(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10")
            return listOf(
                step("type" to "ride", "duration_min" to warmup, "intensity" to "endurance",
                    "note" to "Z2 warm-up · $z2. Fuel from minute 30."),
                step("type" to "ride", "duration_min" to ssMin, "intensity" to "sweet_spot",
                    "note" to "Sweet-spot block · $ss. Pick a sustained climb if possible."),
                step("type" to "ride", "duration_min" to cooldown, "intensity" to "endurance",
                    "note" to "Z2 return · $z2"),
            )
        }
        return listOf(step("type" to "ride", "duration_min" to durationMin, "intensity" to "endurance",
            "note" to "Long Z2 ride · $z2. Fuel 60 g/h carbs from minute 45. " +
                "Sustained ride > 2 h drives mitochondrial adaptation (San Millán 2018)."))
    }

    fun racePace(lthr: Long? = null, ftp: Double? = null, discipline: String = "road_race"): List<Step> {
        if (discipline == "criterium") {
            return listOf(
                step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                    "note" to "Easy spin + 3× 1 min build"),
                step("type" to "effort_set", "reps" to 8L, "duration_sec_each" to 30L, "rest_sec" to 90L,
                    "intensity" to "anaerobic",
                    "note" to "8× 30 s race-pace surges · ${targetNote(1.03, 1.06, lthr, 1.10, 1.30, ftp, "8.5/10")}"),
                step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
            )
        }
        if (discipline == "time_trial") return ttPace(70, lthr, ftp)
        if (discipline == "hill_climb") return sustainedClimb(70, lthr, ftp)
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 2× 1 min build"),
            step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 12L, "rest_min" to 5L,
                "intensity" to "race_pace",
                "note" to "3× 12 min at road-race effort · ${targetNote(0.96, 1.02, lthr, 0.90, 1.00, ftp, "7.5/10")}"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun shortQuality(lthr: Long? = null, ftp: Double? = null): List<Step> = sprint(lthr, ftp, 0)
}
