// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

/**
 * Swimming builders, pool and open water — a port of
 * `backend/app/calculators/plan/swimming.py`, where the zones (offsets from
 * CSS), the sources and the pool/open-water split are explained.
 */
internal object Swimming {

    private const val DEFAULT_CSS = 120.0

    private val DRILLS = listOf(
        "Catch-up" to "catch-up: one arm waits in front until the other arrives — long, patient stroke",
        "Fingertip drag" to "fingertip drag: high relaxed elbow on the recovery",
        "6-kick switch" to "6-kick switch: six kicks on the side, then one stroke — rotation and balance",
        "Single arm" to "single arm, other arm at your side: breathe to the non-working side",
        "Fists" to "closed fists: feel the forearm do the catch",
        "Sculling" to "front sculling: feel the water with the hands before the pull",
    )

    private val OW_SKILLS = listOf(
        "Sighting" to "sight every 6 strokes: eyes forward just above the water, then breathe to the side",
        "Drafting" to "sit on a partner's feet or hip — drafting saves 10-20% of the effort",
        "Buoy turns" to "tight turns at a buoy: a short burst in, roll round it, settle out",
        "Bilateral" to "breathe every 3 strokes so either side works when the chop or sun decides",
        "Straight line" to "10 strokes eyes closed, then sight: find out which way you drift",
        "Start sprint" to "fast start from standing in the water, settle to race pace after 100 strokes",
    )

    private fun present(css: Double?) = css != null && css != 0.0

    private fun cssOrDefault(css: Double?): Double = if (present(css)) css!! else DEFAULT_CSS

    private fun mss(sec: Double): String {
        val s = sec.toLong()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    private fun paceNote(css: Double?, delta: Long, effort: String): String =
        if (present(css)) "@ ${mss(css!! + delta)}/100m" else effort

    private fun raceM(raceDistanceM: Double): Long =
        if (raceDistanceM == 0.0 || raceDistanceM > 25000) 1500L else raceDistanceM.toLong()

    private fun minutesFor(distM: Long, secPer100: Double): Long =
        maxOf(1L, (distM / 100.0 * secPer100 / 60).toLong())

    private fun warmup(distM: Long, css: Double?, note: String = "Easy mixed strokes"): Step = step(
        "type" to "warmup", "distance_m" to distM,
        "duration_min" to minutesFor(distM, cssOrDefault(css) + 15),
        "intensity" to "easy", "note" to "$distM m warm-up · $note",
    )

    private fun cooldown(distM: Long, css: Double?): Step = step(
        "type" to "cooldown", "distance_m" to distM,
        "duration_min" to minutesFor(distM, cssOrDefault(css) + 15),
        "intensity" to "easy", "note" to "$distM m easy cool-down",
    )

    private fun poolSet(
        reps: Long, dist: Long, rest: Long, intensity: String, secPer100: Double,
        label: String, note: String, stroke: String = "freestyle", equipment: String? = null,
    ): Step {
        val s = step(
            "type" to "interval_set", "reps" to reps, "distance_m" to dist, "rest_sec" to rest,
            "intensity" to intensity, "sec_per_km" to (secPer100 * 10).toLong(),
            "stroke" to stroke, "label" to label, "note" to note,
        )
        if (equipment != null) s["equipment"] = equipment
        return s
    }

    private fun fillReps(budgetM: Long, dist: Long, lo: Long, hi: Long): Long = maxOf(lo, minOf(hi, budgetM / dist))

    private fun budgetM(durationMin: Long, css: Double?, usedM: Long): Long {
        val total = ((durationMin * 60).toDouble() / (cssOrDefault(css) + 12) * 100).toLong()
        return maxOf(0L, total - usedM)
    }

    fun technique(durationMin: Long, css: Double?, variation: Long, openWater: Boolean): List<Step> {
        if (openWater) {
            val (aLabel, aNote) = OW_SKILLS[(variation % 6).toInt()]
            val (bLabel, bNote) = OW_SKILLS[((variation + 3) % 6).toInt()]
            val main = maxOf(10L, durationMin - 20)
            val each = 4L
            val reps = maxOf(2L, main / (each + 1) / 2)
            return listOf(
                step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy",
                    "note" to "Easy swim · settle breathing, let the goggles and wetsuit settle"),
                step("type" to "effort_set", "reps" to reps, "duration_min_each" to each, "rest_min" to 1L,
                    "intensity" to "easy", "label" to aLabel, "note" to "$reps× $each min $aNote · 1 min easy"),
                step("type" to "effort_set", "reps" to reps, "duration_min_each" to each, "rest_min" to 1L,
                    "intensity" to "easy", "label" to bLabel, "note" to "$reps× $each min $bNote · 1 min easy"),
                step("type" to "cooldown", "duration_min" to 10L, "intensity" to "easy",
                    "note" to "Easy swim back to the exit"),
            )
        }
        val c = cssOrDefault(css)
        val (dLabel, dNote) = DRILLS[(variation % 6).toInt()]
        val kReps = 6L
        val steps = mutableListOf(
            warmup(300, css),
            poolSet(8, 50, 15, "easy", c + 20, dLabel, "8×50 m drill · $dNote · 15 s rest", stroke = "drill"),
            poolSet(kReps, 50, 20, "easy", c + 30, "Kick",
                "$kReps×50 m kick with a board · from the hips, small fast kick · 20 s rest",
                stroke = "drill", equipment = "swim_kickboard"),
        )
        val budget = budgetM(durationMin, css, 300 + 400 + 300 + 200)
        val reps = fillReps(budget, 100, 2, 10)
        steps += poolSet(reps, 100, 15, "aerobic", c + 12, "Pull",
            "$reps×100 m pull buoy ${paceNote(css, 12, "at an easy aerobic effort")}" +
                " · long strokes, count them per length · 15 s rest",
            equipment = "swim_pull_buoy")
        steps += cooldown(200, css)
        return steps
    }

    private val AEROBIC_SHAPES = listOf(400L to 30L, 200L to 20L, 300L to 25L, 500L to 30L, 100L to 10L, 250L to 20L)

    fun aerobic(durationMin: Long, css: Double?, variation: Long, openWater: Boolean): List<Step> {
        if (openWater) {
            val main = maxOf(15L, durationMin - 10)
            return listOf(
                step("type" to "warmup", "duration_min" to 5L, "intensity" to "easy", "note" to "Easy swim"),
                step("type" to "swim", "duration_min" to main, "intensity" to "aerobic",
                    "note" to "Continuous aerobic swim · comfortable, could keep going · sight every 8-10 strokes"),
                step("type" to "cooldown", "duration_min" to 5L, "intensity" to "easy", "note" to "Easy swim"),
            )
        }
        val c = cssOrDefault(css)
        val (dist, rest) = AEROBIC_SHAPES[(variation % 6).toInt()]
        val budget = budgetM(durationMin, css, 300 + 200)
        val reps = fillReps(budget, dist, 2, 30)
        return listOf(
            warmup(300, css),
            poolSet(reps, dist, rest, "aerobic", c + 12, "Aerobic $dist",
                "$reps×$dist m ${paceNote(css, 12, "at a steady aerobic effort")} · even splits · $rest s rest"),
            cooldown(200, css),
        )
    }

    private val CSS_LADDER = listOf(Triple(10L, 100L, 15L), Triple(6L, 200L, 20L), Triple(5L, 300L, 25L), Triple(4L, 400L, 30L))

    fun css(buildIdx: Long, css: Double?, variation: Long, openWater: Boolean): List<Step> {
        if (openWater) {
            val reps = minOf(5L, 3 + buildIdx / 2)
            val each = minOf(10L, 6 + buildIdx / 2)
            return listOf(
                step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy",
                    "note" to "Easy swim, 4× 20 strokes building"),
                step("type" to "effort_set", "reps" to reps, "duration_min_each" to each, "rest_min" to 1L,
                    "intensity" to "threshold", "label" to "CSS effort",
                    "note" to "$reps× $each min at CSS effort (hard but even) · sight every 6 strokes · 1 min easy"),
                step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy swim"),
            )
        }
        val c = cssOrDefault(css)
        val idx = minOf(CSS_LADDER.size - 1L, buildIdx / 2).toInt()
        var (reps, dist, rest) = CSS_LADDER[idx]
        if (variation % 2 == 1L && idx > 0) {
            reps *= 2; dist /= 2; rest = maxOf(10L, rest - 5)
        }
        return listOf(
            warmup(400, css, "200 easy, 4×50 build, 100 easy"),
            poolSet(reps, dist, rest, "threshold", c, "CSS $dist",
                "$reps×$dist m ${paceNote(css, 0, "at CSS (threshold) effort")}" +
                    " · hold every repeat within 2 s · $rest s rest"),
            cooldown(200, css),
        )
    }

    private val VO2_LADDER = listOf(
        listOf(16L, 50L, 20L, 5L), listOf(10L, 100L, 30L, 4L), listOf(8L, 100L, 25L, 4L), listOf(5L, 200L, 45L, 3L),
    )

    fun vo2(buildIdx: Long, css: Double?, openWater: Boolean): List<Step> {
        if (openWater) {
            val reps = minOf(10L, 6 + buildIdx / 2)
            return listOf(
                step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy swim"),
                step("type" to "effort_set", "reps" to reps, "duration_min_each" to 2L, "rest_min" to 1L,
                    "intensity" to "vo2", "label" to "Surge",
                    "note" to "$reps× 2 min hard (as at a race start or buoy) · 1 min easy but keep swimming"),
                step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy swim"),
            )
        }
        val c = cssOrDefault(css)
        val (reps, dist, rest, delta) = VO2_LADDER[minOf(VO2_LADDER.size - 1L, buildIdx / 2).toInt()]
        return listOf(
            warmup(400, css, "200 easy, 4×50 build, 100 easy"),
            poolSet(reps, dist, rest, "vo2", c - delta, "VO2 $dist",
                "$reps×$dist m ${paceNote(css, -delta, "fast — faster than CSS, not a sprint")} · $rest s rest"),
            cooldown(300, css),
        )
    }

    fun racePace(raceDistanceM: Double, css: Double?, openWater: Boolean): List<Step> {
        val race = raceM(raceDistanceM)
        if (openWater) {
            val main = minOf(30L, maxOf(12L, race / 100))
            return listOf(
                step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy",
                    "note" to "Easy swim, 3× 20 strokes fast"),
                step("type" to "effort_set", "reps" to 1L, "duration_min_each" to 2L, "intensity" to "vo2",
                    "label" to "Start", "note" to "2 min start sprint · then settle without stopping"),
                step("type" to "swim", "duration_min" to main, "intensity" to "race_pace",
                    "note" to "$main min at race pace · surge for 20 strokes every 5 min as at a buoy · sight every 6 strokes"),
                step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy swim"),
            )
        }
        val c = cssOrDefault(css)
        val dist = if (race <= 800) 100L else if (race <= 2000) 200L else 400L
        val total = minOf(race, 3000L)
        val reps = maxOf(3L, total / dist)
        return listOf(
            warmup(400, css, "200 easy, 4×50 build, 100 easy"),
            poolSet(reps, dist, 15, "race_pace", c - 2, "Race $dist",
                "$reps×$dist m at goal race pace ${paceNote(css, -2, "")}".trimEnd() +
                    " · the race distance broken up · 15 s rest"),
            cooldown(200, css),
        )
    }

    fun long(durationMin: Long, phase: String, css: Double?, openWater: Boolean): List<Step> {
        if (openWater) {
            return listOf(step("type" to "swim", "duration_min" to durationMin, "intensity" to "aerobic",
                "note" to "Long continuous open-water swim · steady aerobic effort · sight every 8" +
                    " strokes · practise feeding if the race is over an hour"))
        }
        val c = cssOrDefault(css)
        val total = maxOf(800L, budgetM(durationMin, css, 400) / 100 * 100)
        val body = if (phase == "base") {
            val dist = total / 4 / 50 * 50
            poolSet(4, dist, 20, "aerobic", c + 12, "Long $dist",
                "4×$dist m ${paceNote(css, 12, "at a steady aerobic effort")} · form over speed · 20 s rest")
        } else {
            poolSet(1, total, 0, "aerobic", c + 10, "Long $total",
                "$total m continuous ${paceNote(css, 10, "at a steady aerobic effort")} · no stopping at the walls")
        }
        return listOf(warmup(200, css), body, cooldown(200, css))
    }

    fun shortQuality(css: Double?, openWater: Boolean): List<Step> {
        if (openWater) {
            return listOf(
                step("type" to "warmup", "duration_min" to 10L, "intensity" to "easy", "note" to "Easy swim"),
                step("type" to "effort_set", "reps" to 6L, "duration_sec_each" to 30L, "rest_sec" to 60L,
                    "intensity" to "fast", "label" to "Fast", "note" to "6× 30 s fast · 1 min easy"),
                step("type" to "effort_set", "reps" to 3L, "duration_min_each" to 3L, "rest_min" to 1L,
                    "intensity" to "race_pace", "label" to "Race pace", "note" to "3× 3 min at race pace · 1 min easy"),
                step("type" to "cooldown", "duration_min" to 8L, "intensity" to "easy", "note" to "Easy swim"),
            )
        }
        val c = cssOrDefault(css)
        return listOf(
            warmup(400, css, "200 easy, 4×50 build, 100 easy"),
            poolSet(8, 50, 40, "fast", c - 12, "Fast 50", "8×50 m fast · smooth, not thrashing · 40 s rest"),
            poolSet(4, 100, 20, "race_pace", c - 2, "Race 100",
                "4×100 m at race pace ${paceNote(css, -2, "")}".trimEnd() + " · 20 s rest"),
            cooldown(300, css),
        )
    }
}

/**
 * The generic builders, for sports with no builders of their own — a port of
 * `backend/app/calculators/plan/generic.py`.
 */
internal object Generic {

    fun easy(durationMin: Long, sport: String): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "easy",
            "note" to "Easy $sport · Zone 2 · conversational pace"),
    )

    fun aerobic(durationMin: Long, sport: String): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "aerobic",
            "note" to "Aerobic $sport · Zone 3 · steady moderate effort"),
    )

    fun quality(durationMin: Long, sport: String): List<Step> {
        val main = maxOf(10L, durationMin - 12 - 8)
        return listOf(
            step("type" to "activity", "duration_min" to 12L, "intensity" to "easy", "note" to "Warm-up"),
            step("type" to "activity", "duration_min" to main, "intensity" to "threshold",
                "note" to "Quality $sport · Zone 4 · comfortably hard"),
            step("type" to "activity", "duration_min" to 8L, "intensity" to "easy", "note" to "Cool-down"),
        )
    }

    fun long(durationMin: Long, sport: String): List<Step> = listOf(
        step("type" to "activity", "duration_min" to durationMin, "intensity" to "easy",
            "note" to "Long $sport · easy steady effort · build aerobic base"),
    )
}
