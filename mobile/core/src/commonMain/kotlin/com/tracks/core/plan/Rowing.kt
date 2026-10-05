// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

/**
 * Rowing builders — a port of `backend/app/calculators/plan/rowing.py`, where
 * the UT2/UT1/AT/TR/AN bands, and why pieces carry a stroke-rate target, are
 * explained.
 */
internal object Rowing {

    private const val DEFAULT_2K_SEC_PER_KM = 240L

    private fun rate(lo: Int, hi: Int) = "rate $lo-$hi"

    private fun piece(
        reps: Long, minutes: Long, restMin: Long, intensity: String, label: String,
        spm: Pair<Long, Long>, note: String,
    ): Step = step(
        "type" to "effort_set", "reps" to reps, "duration_min_each" to minutes, "rest_min" to restMin,
        "intensity" to intensity, "label" to label, "spm_low" to spm.first, "spm_high" to spm.second,
        "note" to note,
    )

    private fun warm(minutes: Long = 10): Step = step(
        "type" to "warmup", "duration_min" to minutes, "intensity" to "easy",
        "note" to "Easy rowing, rate 18 · 3× 10 strokes building pressure",
    )

    private fun cool(minutes: Long = 8): Step = step(
        "type" to "cooldown", "duration_min" to minutes, "intensity" to "easy", "note" to "Easy paddle, rate 18",
    )

    fun ut2(durationMin: Long, variation: Long): List<Step> {
        val total = maxOf(30L, durationMin)
        val shape = variation % 3
        if (shape == 0L) {
            return listOf(step(
                "type" to "activity", "duration_min" to total, "intensity" to "ut2",
                "spm_low" to 18L, "spm_high" to 20L,
                "note" to "$total min UT2 · ${rate(18, 20)} · split ~20 s/500 m slower than 2k pace" +
                    " · long, relaxed strokes; you can talk",
            ))
        }
        val reps = shape + 1
        val each = maxOf(10L, (total - 2 * shape) / reps)
        return listOf(piece(reps, each, 2, "ut2", "UT2", 18L to 20L,
            "$reps× $each min UT2 · ${rate(18, 20)} · 2 min easy paddle between · you can talk"))
    }

    fun ut1(durationMin: Long): List<Step> {
        val each = maxOf(8L, (durationMin - 24) / 3)
        return listOf(
            warm(),
            piece(3, each, 3, "ut1", "UT1", 20L to 22L,
                "3× $each min UT1 · ${rate(20, 22)} · split ~14 s/500 m slower than 2k pace · 3 min easy between"),
            cool(),
        )
    }

    private val AT_LADDER = listOf(3L to 8L, 4L to 8L, 3L to 10L, 4L to 10L, 3L to 12L, 2L to 20L)

    fun threshold(buildIdx: Long, variation: Long): List<Step> {
        val (reps, each) = AT_LADDER[minOf(AT_LADDER.size - 1L, buildIdx + variation % 2).toInt()]
        return listOf(
            warm(),
            piece(reps, each, 3, "threshold", "AT", 22L to 24L,
                "$reps× $each min AT · ${rate(22, 24)} · split ~8 s/500 m slower than 2k pace" +
                    " · hard but controlled · 3 min easy between"),
            cool(),
        )
    }

    private val TR_LADDER = listOf(
        Triple(6L, 500L, 120L), Triple(8L, 500L, 120L), Triple(5L, 750L, 150L),
        Triple(4L, 1000L, 180L), Triple(3L, 1000L, 150L),
    )

    fun racePace(buildIdx: Long): List<Step> {
        val (reps, dist, rest) = TR_LADDER[minOf(TR_LADDER.size - 1L, buildIdx / 2).toInt()]
        val half = if (rest % 60 == 0L) "" else " 30 s"
        return listOf(
            warm(12),
            step(
                "type" to "interval_set", "reps" to reps, "distance_m" to dist, "rest_sec" to rest,
                "intensity" to "race_pace", "sec_per_km" to DEFAULT_2K_SEC_PER_KM, "label" to "2k pace $dist",
                "spm_low" to 28L, "spm_high" to 32L,
                "note" to "$reps× $dist m at 2k race pace · ${rate(28, 32)} · ${rest / 60} min$half easy between",
            ),
            cool(),
        )
    }

    private val SPRINTS = listOf(Triple(10L, 60L, 120L), Triple(8L, 45L, 135L), Triple(12L, 30L, 90L))

    fun sprint(variation: Long): List<Step> {
        val (reps, on, off) = SPRINTS[(variation % 3).toInt()]
        return listOf(
            warm(12),
            step(
                "type" to "effort_set", "reps" to reps, "duration_sec_each" to on, "rest_sec" to off,
                "intensity" to "sprint", "label" to "AN", "spm_low" to 32L, "spm_high" to 38L,
                "note" to "$reps× $on s AN · rate 32+ · faster than 2k pace · $off s easy between",
            ),
            cool(),
        )
    }

    private val TECHNIQUE_DRILLS = listOf(
        "legs-only, then legs-and-body, then full stroke — sequence the drive",
        "pause at the finish, hands away — clean release and body swing",
        "pause at half-slide — control the recovery, don't rush the catch",
        "feet out of the straps — hang off the handle, no yanking at the finish",
    )

    fun technique(durationMin: Long, variation: Long): List<Step> {
        val drill = TECHNIQUE_DRILLS[(variation % 4).toInt()]
        val each = 5L
        val reps = maxOf(3L, minOf(6L, (durationMin - 18) / (each + 1)))
        return listOf(
            warm(),
            piece(reps, each, 1, "easy", "Drill", 16L to 20L,
                "$reps× $each min · $drill · rate 16-20 · 1 min easy between"),
            cool(),
        )
    }

    fun long(durationMin: Long): List<Step> {
        if (durationMin <= 60) {
            return listOf(step(
                "type" to "activity", "duration_min" to durationMin, "intensity" to "ut2",
                "spm_low" to 18L, "spm_high" to 20L,
                "note" to "$durationMin min UT2 · ${rate(18, 20)} · steady, you can talk",
            ))
        }
        val each = durationMin / 2
        return listOf(piece(2, each, 3, "ut2", "Long UT2", 18L to 20L,
            "2× $each min UT2 · ${rate(18, 20)} · 3 min off the erg between: stand, walk, stretch the hips"))
    }

    fun shortQuality(): List<Step> = listOf(
        warm(12),
        step(
            "type" to "effort_set", "reps" to 5L, "duration_sec_each" to 20L, "rest_sec" to 100L,
            "intensity" to "sprint", "label" to "Start", "spm_low" to 36L, "spm_high" to 42L,
            "note" to "5× race start (20 s: 5 short, 10 long, then settle) · 100 s easy between",
        ),
        step(
            "type" to "interval_set", "reps" to 3L, "distance_m" to 500L, "rest_sec" to 180L,
            "intensity" to "race_pace", "sec_per_km" to DEFAULT_2K_SEC_PER_KM, "label" to "2k pace 500",
            "spm_low" to 30L, "spm_high" to 34L,
            "note" to "3× 500 m at 2k race pace · rate 30-34 · 3 min easy between",
        ),
        cool(),
    )
}
