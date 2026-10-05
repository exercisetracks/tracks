// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.parse.PyRandom
import com.tracks.core.plan.PlanBase.pwrZone
import com.tracks.core.plan.PlanBase.targetNote

/**
 * Mountain-biking workout builders — a port of
 * `backend/app/calculators/plan/mtb.py` (Friel HR first, Coggan power when
 * FTP is known; sources cited there).
 */
internal object Mtb {

    private val INTERVAL_VAR = listOf(0L to 0L, +1L to -30L, 0L to +30L, -1L to 0L, +1L to +15L, -1L to -15L)
    private val MICRO_VAR = listOf(13L to 3L, 10L to 4L, 15L to 3L, 13L to 2L, 12L to 3L, 14L to 3L)
    private val MATCH_VAR = listOf(
        Triple(8L, 60L, 180L), Triple(10L, 45L, 150L), Triple(6L, 90L, 240L),
        Triple(8L, 30L, 120L), Triple(6L, 60L, 240L), Triple(10L, 30L, 120L),
    )
    private val ENDURANCE_VAR = longArrayOf(0, +10, -10, +5, +15, -5)

    private val SKILLS_DRILLS = listOf(
        "Cornering" to "Outside foot down, eyes on exit, lean bike not body. 8–12 reps each direction.",
        "Descending" to "Heels down, hips back over rear wheel, light grip, look 5 m ahead. Repeat a descent 4–6 times, varying lines.",
        "Climbing technique" to "Seat-stay climbs — slide forward, drive heels, hold a steady cadence (75–85 rpm). 4–6 short climbs.",
        "Line choice" to "Pick three lines on the same trail section, ride each 3×, compare flow and exit speed.",
        "Drops" to "Start small (20–30 cm), preload, lift front wheel, soft landing. 8–10 reps in progression.",
        "Jumps & pumps" to "Pump track or rolling bumps — generate speed without pedaling. Focus on the push-pull rhythm.",
        "Braking control" to "Threshold braking — slow to walking pace using mostly the front, no skid. 6–8 reps.",
        "Track stands" to "Balance drill at speed = 0. Builds slow-speed control. 30 s on, 30 s off, 10 sets.",
        "Ratchet pedaling" to "Quarter-pedal strokes through rocks. Pick a technical 50 m and ratchet repeatedly.",
        "Switchbacks" to "Tight uphill + downhill switchback turns. Late apex, weight outside pedal. 6–8 each way.",
    )

    private val SKILLS_WEIGHTS = mapOf(
        "xco" to longArrayOf(25, 10, 40, 10, 5, 0, 5, 0, 5, 0),
        "xcm" to longArrayOf(20, 15, 40, 15, 0, 0, 10, 0, 0, 0),
        "enduro" to longArrayOf(25, 25, 5, 5, 15, 10, 10, 0, 0, 5),
        "trail" to longArrayOf(15, 15, 15, 10, 10, 10, 10, 5, 5, 5),
    )

    private fun v(variation: Long) = variation.mod(6L).toInt()
    private fun mss(sec: Long) = "${sec.floorDiv(60L)}:${sec.mod(60L).toString().padStart(2, '0')}"

    /** `_mtb_pick_skills`: weighted draws without replacement, CPython's generator. */
    fun pickSkills(discipline: String, k: Int, seed: Long): List<Pair<String, String>> {
        val rng = PyRandom(seed)
        val weights = SKILLS_WEIGHTS[discipline] ?: SKILLS_WEIGHTS.getValue("trail")
        val poolIdx = weights.indices.filter { weights[it] > 0 }.toMutableList()
        val poolW = poolIdx.map { weights[it] }.toMutableList()
        val picked = mutableListOf<Int>()
        repeat(minOf(k, poolIdx.size)) {
            val choice = rng.choice(poolIdx, poolW)
            picked += choice
            val ci = poolIdx.indexOf(choice)
            poolIdx.removeAt(ci)
            poolW.removeAt(ci)
        }
        return picked.map { SKILLS_DRILLS[it] }
    }

    fun endurance(durationMin: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val dur = maxOf(45L, durationMin + ENDURANCE_VAR[v(variation)])
        val note = targetNote(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (easy, conversational)")
        return listOf(step("type" to "ride", "duration_min" to dur, "intensity" to "endurance",
            "note" to "Z2 aerobic ride · $note. Stay seated on climbs, smooth pedal stroke."))
    }

    fun tempo(durationMin: Long, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val tempo = maxOf(20L, durationMin - 15 - 10)
        val note = targetNote(0.89, 0.93, lthr, 0.76, 0.88, ftp, "5/10 (moderate-hard)")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 2× 30 s spin-ups to open the legs"),
            step("type" to "ride", "duration_min" to tempo, "intensity" to "tempo",
                "note" to "Z3 tempo · $note. Sustainable but you can't sing."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance",
                "note" to "Easy spin back home"),
        )
    }

    fun sweetSpot(durationMin: Long, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val ss = maxOf(15L, durationMin - 15 - 10)
        val note = targetNote(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10 (comfortably hard)")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min build to threshold"),
            step("type" to "ride", "duration_min" to ss, "intensity" to "sweet_spot",
                "note" to "Sweet spot · $note. Pick a steady climb if possible."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun threshold(buildIdx: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (baseReps, dur, rest) = when {
            buildIdx <= 2 -> Triple(2L, 15L, 5L)
            buildIdx <= 4 -> Triple(2L, 20L, 5L)
            else -> Triple(3L, 15L, 4L)
        }
        val (dReps, dRestSec) = INTERVAL_VAR[v(variation)]
        val reps = maxOf(2L, baseReps + dReps)
        // Floor division: -30 s of variation takes a whole minute off, as in Python.
        val restMin = maxOf(3L, rest + dRestSec.floorDiv(60L))
        val note = targetNote(1.00, 1.02, lthr, 0.95, 1.00, ftp, "7/10 (1-hour race effort)")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min ramping to threshold"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to restMin,
                "intensity" to "threshold",
                "note" to "$reps× $dur min at LT · $note · $restMin min easy between"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun intervals(buildIdx: Long, lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (baseReps, dur) = when {
            buildIdx <= 1 -> 4L to 3L
            buildIdx <= 3 -> 4L to 4L
            buildIdx <= 5 -> 5L to 4L
            else -> 5L to 5L
        }
        val (dReps, _) = INTERVAL_VAR[v(variation)]
        val reps = maxOf(3L, baseReps + dReps)
        val rest = dur
        val note = targetNote(1.03, 1.06, lthr, 1.05, 1.20, ftp, "8/10 (VO2max, hard)")
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 4× 30 s spin-ups + 1× 2 min build to threshold"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to dur, "rest_min" to rest,
                "intensity" to "vo2",
                "note" to "$reps× $dur min at VO2max · $note · $rest min easy between"),
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
                    "3 min easy spin between sets. (Rønnestad 30/15s protocol — VO2max & MTB surge specificity.)"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun overUnders(lthr: Long? = null, ftp: Double? = null): List<Step> {
        val over = targetNote(1.03, 1.05, lthr, 1.02, 1.08, ftp, "8/10")
        val under = targetNote(0.93, 0.96, lthr, 0.88, 0.92, ftp, "6/10")
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min ramping to threshold"),
            step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 8L, "rest_min" to 5L,
                "intensity" to "over_under",
                "note" to "3× 8 min over-unders · alternate 30 s OVER ($over) / 30 s UNDER ($under). " +
                    "5 min easy between sets."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun matchbook(lthr: Long? = null, ftp: Double? = null, variation: Long = 0): List<Step> {
        val (reps, workSec, restSec) = MATCH_VAR[v(variation)]
        val target = pwrZone(1.20, 1.50, ftp) ?: "all-out — Z6 anaerobic"
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 5× 30 s spin-ups + 1× 2 min build to VO2"),
            step("type" to "effort_set", "reps" to reps, "duration_sec_each" to workSec, "rest_sec" to restSec,
                "intensity" to "anaerobic",
                "note" to "$reps× $workSec s all-out · target $target · ${mss(restSec)} full recovery. " +
                    "These are matchbook efforts — go to depletion, then refill."),
            step("type" to "cooldown", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin until HR settles"),
        )
    }

    fun standingStarts(lthr: Long? = null, ftp: Double? = null): List<Step> {
        val target = pwrZone(1.50, 3.00, ftp) ?: "absolute maximum power · 9.5/10 RPE"
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 20 s spin-ups + 2× 5 s seated sprints"),
            step("type" to "effort_set", "reps" to 10L, "duration_sec_each" to 10L, "rest_sec" to 110L,
                "intensity" to "neuromuscular",
                "note" to "10× 10 s standing-start sprints · target $target. " +
                    "Roll at 10 km/h, attack hard, stand for first 5 s. ~2 min full recovery between."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun descentRepeats(durationMin: Long, @Suppress("UNUSED_PARAMETER") lthr: Long? = null): List<Step> = listOf(
        step("type" to "warmup", "duration_min" to 15L, "intensity" to "endurance",
            "note" to "Easy spin + 3× 30 s spin-ups + 1× 2 min sweet spot to open the legs"),
        step("type" to "ride", "duration_min" to maxOf(40L, durationMin - 25), "intensity" to "descent_repeats",
            "note" to "Pick a descent of 2–5 min and ride it 4–8 times. Race-pace on the way down — " +
                "focus on line choice, brake control, body position. Climb back at Z2 transfer effort " +
                "(stay below LT — these are not climb intervals)."),
        step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
    )

    fun long(totalMin: Long, phase: String, lthr: Long? = null, ftp: Double? = null): List<Step> {
        val z2 = targetNote(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (conversational)")
        if ((phase == "build" || phase == "peak") && totalMin >= 120) {
            val ssMin = minOf(45L, (totalMin * 0.25).toLong())
            val warmup = ((totalMin - ssMin) * 0.6).toLong()
            val cooldown = totalMin - ssMin - warmup
            val ss = targetNote(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10")
            return listOf(
                step("type" to "ride", "duration_min" to warmup, "intensity" to "endurance",
                    "note" to "Easy aerobic warm-up · $z2. Fuel from minute 30."),
                step("type" to "ride", "duration_min" to ssMin, "intensity" to "sweet_spot",
                    "note" to "Sweet-spot block · $ss. Pick a sustained climb if possible."),
                step("type" to "ride", "duration_min" to cooldown, "intensity" to "endurance",
                    "note" to "Aerobic return to easy spin · $z2"),
            )
        }
        return listOf(step("type" to "ride", "duration_min" to totalMin, "intensity" to "endurance",
            "note" to "Long aerobic ride · $z2. Fuel 60 g/h carbs from minute 45. " +
                "Practice race-day equipment and nutrition strategy."))
    }

    fun racePace(lthr: Long? = null, ftp: Double? = null, discipline: String = "xcm"): List<Step> {
        if (discipline == "xco") {
            return listOf(
                step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                    "note" to "Easy spin + 2× 1 min build"),
                step("type" to "effort_set", "reps" to 4L, "duration_min_each" to 6L, "rest_min" to 3L,
                    "intensity" to "race_pace",
                    "note" to "4× 6 min at XCO race intensity · ${targetNote(0.98, 1.04, lthr, 0.95, 1.05, ftp, "8/10")}"),
                step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
            )
        }
        if (discipline == "enduro") return descentRepeats(75, lthr)
        return listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance", "note" to "Easy spin"),
            step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 12L, "rest_min" to 5L,
                "intensity" to "race_pace",
                "note" to "3× 12 min at XCM race effort · ${targetNote(0.92, 0.97, lthr, 0.85, 0.95, ftp, "7/10")}"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    /**
     * `_mtb_skills`. The seed is `variation + Σ ord(c) % 1000` on both sides —
     * a stable stand-in for the `hash(discipline)` the server used to use,
     * which Python randomises per process.
     */
    fun skills(durationMin: Long, discipline: String = "trail", variation: Long = 0): List<Step> {
        val seed = variation + discipline.sumOf { it.code.toLong() } % 1000
        val drills = pickSkills(discipline, 3, seed)
        val notes = drills.joinToString("; ") { (label, desc) -> "($label) $desc" }
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy",
                "note" to "Easy spin + body check (saddle height, tire pressure, drivetrain)"),
            step("type" to "activity", "duration_min" to maxOf(20L, durationMin - 15), "intensity" to "skills",
                "note" to "Skills focus — $notes"),
            step("type" to "cooldown", "duration_min" to 5L, "intensity" to "easy", "note" to "Easy spin"),
        )
    }

    /** `_mtb_field_test`. */
    fun fieldTest(testType: String, @Suppress("UNUSED_PARAMETER") lthr: Long? = null): List<Step> = when (testType) {
        "ftp20" -> listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min ramping + 1× 5 min at threshold to open"),
            step("type" to "ride", "duration_min" to 5L, "intensity" to "easy",
                "note" to "5 min easy spin between primer and test"),
            step("type" to "ride", "duration_min" to 20L, "intensity" to "test",
                "note" to "20 MIN ALL-OUT TIME TRIAL · pace evenly · FTP = 95% of average power."),
            step("type" to "cooldown", "duration_min" to 15L, "intensity" to "endurance",
                "note" to "Easy spin until HR settles"),
        )
        "pmax5" -> listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 1 min ramping + 1× 2 min at threshold"),
            step("type" to "effort_set", "reps" to 2L, "duration_min_each" to 5L, "rest_min" to 10L,
                "intensity" to "test",
                "note" to "2× 5 MIN ALL-OUT · pace evenly · best average ≈ MAP / 5-min power."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
        "rsa" -> listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 5× 30 s spin-ups + 2× 10 s sprints"),
            step("type" to "effort_set", "reps" to 10L, "duration_sec_each" to 6L, "rest_sec" to 30L,
                "intensity" to "test",
                "note" to "10× 6 s ALL-OUT sprints · 30 s easy between · RSA test (power decrement)."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
        else -> listOf(
            step("type" to "warmup", "duration_min" to 20L, "intensity" to "endurance",
                "note" to "Easy spin + 3× 30 s spin-ups + 1× 2 min at threshold"),
            step("type" to "effort_set", "reps" to 1L, "duration_min_each" to 1L, "rest_min" to 0L,
                "intensity" to "test",
                "note" to "1 MIN ALL-OUT TIME TRIAL · estimates W' / anaerobic capacity."),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "endurance", "note" to "Easy spin"),
        )
    }

    fun recovery(durationMin: Long): List<Step> {
        val dur = maxOf(20L, minOf(45L, durationMin))
        return listOf(step("type" to "ride", "duration_min" to dur, "intensity" to "recovery",
            "note" to "Recovery spin · Z1 only · light gear, fast cadence, legs stay loose. Pick the smoothest trail available."))
    }
}
