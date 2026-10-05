// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.plan.PlanBase.hrZone

/**
 * Cross-country / ski-mountaineering builders — a port of the first half of
 * `backend/app/calculators/plan/skiing.py`, where the training model and
 * sources are.
 */
internal object NordicSkiing {

    private val SKIMO = setOf("backcountry_skiing", "ski_mountaineering", "skimo")

    fun isSkimo(sport: String): Boolean = sport.lowercase().replace(" ", "_") in SKIMO

    private fun mode(skimo: Boolean) = if (skimo) "skinning uphill" else "on snow or roller skis"

    private fun feet(vert: Long, imperial: Boolean) =
        if (imperial) "${(vert * 3.28084).toLong() / 50 * 50} ft" else "$vert m"

    private val XC_FOCUS = listOf(
        "classic technique", "skate technique", "double poling on the flats",
        "classic technique on the climbs", "skate: V2 on the flats, V1 on the climbs",
        "mixed: whatever the terrain asks",
    )

    fun endurance(durationMin: Long, sport: String, variation: Long, lthr: Long?): List<Step> {
        val note = if (isSkimo(sport)) {
            "Easy skinning · ${hrZone(0.75, 0.85, lthr)} · steady rhythm, short" +
                " steps on the steep bits · or hike uphill with poles off-season"
        } else {
            "Easy distance ${mode(false)} · ${XC_FOCUS[(variation % 6).toInt()]} · ${hrZone(0.75, 0.85, lthr)}"
        }
        return listOf(step("type" to "activity", "duration_min" to durationMin, "intensity" to "endurance", "note" to note))
    }

    private val XC_DRILLS = listOf(
        "no-poles skating: balance over the glide ski",
        "one-skate (V2) on gentle terrain: commit to each ski",
        "double poling only: hinge from the hips, not the arms",
        "diagonal stride without poles: kick and glide timing",
    )
    private val SKIMO_DRILLS = listOf(
        "transitions: skins off, boots and bindings to ski mode, and back — against the clock",
        "kick turns on a steep slope, both directions",
        "efficient skinning: slide, don't lift; heel risers only when you need them",
        "bootpack sections with skis on the pack",
    )

    fun technique(durationMin: Long, sport: String, variation: Long): List<Step> {
        val drill = (if (isSkimo(sport)) SKIMO_DRILLS else XC_DRILLS)[(variation % 4).toInt()]
        val main = maxOf(15L, minOf(45L, durationMin - 20))
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy skiing"),
            step("type" to "activity", "duration_min" to main, "intensity" to "easy",
                "note" to "Technique · $drill · easy effort; stop before form fades"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy skiing"),
        )
    }

    fun poleHike(durationMin: Long, buildIdx: Long, imperial: Boolean, lthr: Long?): List<Step> {
        val climb = maxOf(30L, durationMin)
        val vert = climb * (400 + 25 * minOf(buildIdx, 6L)) / 60 / 50 * 50
        return listOf(step("type" to "activity", "duration_min" to climb, "intensity" to "endurance",
            "note" to "Ski-walking uphill with poles · ~${feet(vert, imperial)} of climbing · long strides," +
                " push through the poles · ${hrZone(0.75, 0.85, lthr)}"))
    }

    fun bounding(buildIdx: Long, variation: Long, lthr: Long?): List<Step> {
        val reps = minOf(10L, 6 + buildIdx + variation % 2)
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "easy",
                "note" to "Easy jog, then 4× 20 s ski-walking building"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to 1L, "rest_min" to 2L,
                "intensity" to "vo2", "label" to "Bound",
                "note" to "$reps× 1 min uphill ski-bounding with poles · springy, arms driving ·" +
                    " ${hrZone(0.97, 1.03, lthr)} by the end · walk down to recover"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy jog"),
        )
    }

    private val XC_VO2 = listOf(5L to 3L, 5L to 4L, 6L to 4L, 5L to 5L, 4L to 6L)

    fun intervals(buildIdx: Long, sport: String, lthr: Long?): List<Step> {
        val (reps, each) = XC_VO2[minOf(XC_VO2.size - 1L, buildIdx / 2).toInt()]
        val how = if (isSkimo(sport)) "skinning or bootpacking uphill" else "uphill, on snow, roller skis or ski-walking"
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "easy", "note" to "Easy, 3× 30 s building"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to each, "rest_min" to 3L,
                "intensity" to "vo2", "label" to "Uphill",
                "note" to "$reps× $each min $how · ${hrZone(1.00, 1.05, lthr)} · hard, even pacing · 3 min easy down"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy"),
        )
    }

    private val XC_THRESHOLD = listOf(3L to 8L, 3L to 10L, 4L to 10L, 3L to 12L, 3L to 15L)

    fun threshold(buildIdx: Long, sport: String, lthr: Long?): List<Step> {
        val (reps, each) = XC_THRESHOLD[minOf(XC_THRESHOLD.size - 1L, buildIdx / 2).toInt()]
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "easy", "note" to "Easy"),
            step("type" to "effort_set", "reps" to reps, "duration_min_each" to each, "rest_min" to 3L,
                "intensity" to "threshold", "label" to "Threshold",
                "note" to "$reps× $each min at threshold ${mode(isSkimo(sport))} ·" +
                    " ${hrZone(0.94, 1.00, lthr)} · comfortably hard · 3 min easy"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy"),
        )
    }

    fun racePace(sport: String, lthr: Long?): List<Step> {
        if (isSkimo(sport)) {
            return listOf(
                step("type" to "warmup", "duration_min" to 15L, "intensity" to "easy", "note" to "Easy skinning"),
                step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 12L, "rest_min" to 4L,
                    "intensity" to "race_pace", "label" to "Race climb",
                    "note" to "3× 12 min race-pace climb · ${hrZone(0.95, 1.02, lthr)} · a full transition" +
                        " at the top, ski down as the recovery"),
                step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy"),
            )
        }
        return listOf(
            step("type" to "warmup", "duration_min" to 15L, "intensity" to "easy", "note" to "Easy, 3× 20 s fast"),
            step("type" to "effort_set", "reps" to 5L, "duration_min_each" to 5L, "rest_min" to 3L,
                "intensity" to "race_pace", "label" to "Race pace",
                "note" to "5× 5 min at race pace on race-like terrain · the first 30 s of each fast, like a" +
                    " start · ${hrZone(0.95, 1.02, lthr)} · 3 min easy"),
            step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy"),
        )
    }

    fun long(durationMin: Long, sport: String, imperial: Boolean): List<Step> {
        val note = if (isSkimo(sport)) {
            val vert = durationMin * 400 / 60 / 50 * 50
            "Long day skinning (or hiking with poles off-season) · ~${feet(vert, imperial)} of climbing ·" +
                " easy all day: eat and drink every hour"
        } else {
            "Long easy distance on snow or roller skis · both techniques · fuel every 45 min"
        }
        return listOf(step("type" to "activity", "duration_min" to durationMin, "intensity" to "endurance", "note" to note))
    }

    fun shortQuality(): List<Step> = listOf(
        step("type" to "warmup", "duration_min" to 15L, "intensity" to "easy", "note" to "Easy"),
        step("type" to "effort_set", "reps" to 6L, "duration_sec_each" to 15L, "rest_sec" to 105L,
            "intensity" to "sprint", "label" to "Sprint", "note" to "6× 15 s sprint · full recovery"),
        step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 3L, "rest_min" to 2L,
            "intensity" to "race_pace", "label" to "Race pace", "note" to "3× 3 min at race pace · 2 min easy"),
        step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy"),
    )
}

/**
 * Alpine dry-land builders — a port of the second half of
 * `backend/app/calculators/plan/skiing.py`.
 */
internal object AlpineSkiing {

    private val AEROBIC = listOf("easy run", "easy ride", "uphill hike", "easy ride", "easy run", "rowing or elliptical")

    private const val STRENGTH_NOTE = "Heavy leg strength comes from your strength sessions — turn on Include strength."

    fun aerobic(durationMin: Long, variation: Long, lthr: Long?): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "endurance",
            "note" to "Aerobic base · ${AEROBIC[(variation % 6).toInt()]} · ${hrZone(0.75, 0.85, lthr)} · conversational"),
    )

    fun eccentric(buildIdx: Long, variation: Long): List<Step> {
        val rounds = minOf(5L, 3 + maxOf(buildIdx / 2, variation / 3))
        val hold = 45 + 15 * minOf(buildIdx, 5L)
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy",
                "note" to "Easy cardio, leg swings, 10 bodyweight squats"),
            step("type" to "effort_set", "reps" to rounds, "duration_sec_each" to 60L, "rest_sec" to 30L,
                "intensity" to "eccentric", "label" to "Slow squat",
                "note" to "$rounds× 60 s squats lowering for 4 s, up in 1 s · goblet weight if easy · 30 s rest"),
            step("type" to "effort_set", "reps" to rounds, "duration_sec_each" to 60L, "rest_sec" to 30L,
                "intensity" to "eccentric", "label" to "Step-down",
                "note" to "$rounds× 60 s single-leg step-downs from a box, 3 s lowering, alternating legs · 30 s rest"),
            step("type" to "effort_set", "reps" to rounds, "duration_sec_each" to hold,
                "rest_sec" to 45L, "intensity" to "eccentric", "label" to "Ski hold",
                "note" to "$rounds× wall sit or tuck hold in a skiing stance, $hold s · 45 s rest"),
            step("type" to "cooldown", "duration_min" to 5L, "intensity" to "easy", "note" to STRENGTH_NOTE),
        )
    }

    private val PLYO = listOf(
        "Skater hops" to "skater hops: lateral bound, stick the landing on one leg",
        "Box jumps" to "box jumps: land softly, step down",
        "Lateral hops" to "lateral hops over a line or low hurdle, both feet",
        "Tuck jumps" to "tuck jumps: knees up, quiet landing",
        "Single-leg hops" to "single-leg forward hops, stick each landing",
        "Zig-zag bounds" to "zig-zag bounds down a line, as through gates",
    )

    fun plyometrics(buildIdx: Long, variation: Long): List<Step> {
        val (aLabel, aNote) = PLYO[(variation % 6).toInt()]
        val (bLabel, bNote) = PLYO[((variation + 2) % 6).toInt()]
        val sets = minOf(7L, 4 + buildIdx / 2)
        return listOf(
            step("type" to "warmup", "duration_min" to 12L, "intensity" to "easy",
                "note" to "Easy cardio, dynamic leg swings, 3× 10 low pogo hops"),
            step("type" to "effort_set", "reps" to sets, "duration_sec_each" to 20L, "rest_sec" to 60L,
                "intensity" to "plyometric", "label" to aLabel, "note" to "$sets× 20 s $aNote · 60 s rest"),
            step("type" to "effort_set", "reps" to sets, "duration_sec_each" to 20L, "rest_sec" to 60L,
                "intensity" to "plyometric", "label" to bLabel, "note" to "$sets× 20 s $bNote · 60 s rest"),
            step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy cardio and stretch"),
        )
    }

    private val AGILITY = listOf(
        "Ladder" to "agility ladder: in-in-out-out, lateral shuffles, crossovers",
        "Cones" to "cone slalom: quick feet round a tight line of cones",
        "Reaction" to "reactive shuffles: a partner or app calls the direction",
        "Balance" to "single-leg balance on a cushion or board, eyes closed for the last 10 s",
    )

    fun agility(durationMin: Long, variation: Long): List<Step> {
        val (aLabel, aNote) = AGILITY[(variation % 4).toInt()]
        val (bLabel, bNote) = AGILITY[((variation + 3) % 4).toInt()]
        // Truncating division where Python floors: they differ only below
        // zero, and the max() replaces those anyway.
        val reps = maxOf(4L, (durationMin - 18) / 3)
        return listOf(
            step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy cardio, leg swings"),
            step("type" to "effort_set", "reps" to reps, "duration_sec_each" to 30L, "rest_sec" to 30L,
                "intensity" to "agility", "label" to aLabel, "note" to "$reps× 30 s $aNote · 30 s rest"),
            step("type" to "effort_set", "reps" to reps, "duration_sec_each" to 30L, "rest_sec" to 30L,
                "intensity" to "agility", "label" to bLabel, "note" to "$reps× 30 s $bNote · 30 s rest"),
            step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy cardio"),
        )
    }

    fun skiIntervals(buildIdx: Long, lthr: Long?): List<Step> {
        val reps = minOf(10L, 6 + buildIdx / 2)
        val each = minOf(90L, 60 + 10 * (buildIdx / 2))
        return listOf(
            step("type" to "warmup", "duration_min" to 12L, "intensity" to "easy", "note" to "Easy cardio, 3× 15 s skater hops"),
            step("type" to "effort_set", "reps" to reps, "duration_sec_each" to each, "rest_sec" to each * 2,
                "intensity" to "vo2", "label" to "Ski run",
                "note" to "$reps× $each s ski-run circuit: skater hops, lateral box step-overs, tuck" +
                    " hold · ${hrZone(0.95, 1.05, lthr)} by the end · ${each * 2} s rest"),
            step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy cardio"),
        )
    }

    fun long(durationMin: Long): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "endurance",
            "note" to "Long aerobic day · hike or ride · steady, the length of a ski day's skiing"),
    )
}
