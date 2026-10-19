// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

/**
 * What a recording says out loud, as words -- separate from [RunCues], which
 * only speaks them, so the wording can be tested without a TTS engine.
 *
 * ## A split says how that split went
 *
 * It used to say the total time at the mark: "Kilometre 2, 20 minutes" at the
 * end of a 2 km run of two 10-minute kilometres, which a runner hears as a
 * 20-minute kilometre. A split is now that stretch alone, in the figure its
 * sport is judged by: pace for anything on foot, speed for anything on wheels
 * or skis, time per 500 metres on the water. In the person's own units, so
 * an imperial runner hears miles and minutes per mile.
 */
object SpokenCues {

    /** How a sport is spoken of and split. */
    data class Sport(
        /** "run", "ride": what "Starting …" and "… complete" say. */
        val word: String,
        val measure: Measure,
        /** The split length in the person's unit: 1 km or mile on foot, 5 on a bike. */
        val splitUnits: Int,
    )

    enum class Measure { Pace, Speed, Per500 }

    /** The sport of a recording, from its family (PlanBase.sportFamily). */
    fun sport(family: String?): Sport = when (family) {
        "strength", "strength_training" -> Sport("strength workout", Measure.Pace, 1)
        "flexibility", "flexibility_training", "mobility" -> Sport("flexibility flow", Measure.Pace, 1)
        "yoga" -> Sport("yoga session", Measure.Pace, 1)
        "climbing", "bouldering" -> Sport("climbing session", Measure.Pace, 1)
        "cycling", "mountain_biking", "gravel_cycling" -> Sport("ride", Measure.Speed, 5)
        "nordic_skiing" -> Sport("ski", Measure.Speed, 1)
        "alpine_skiing" -> Sport("ski", Measure.Speed, 1)
        "rowing" -> Sport("row", Measure.Per500, 1)
        "kayaking", "paddling", "paddleboarding" -> Sport("paddle", Measure.Per500, 1)
        "hiking" -> Sport("hike", Measure.Pace, 1)
        "walking" -> Sport("walk", Measure.Pace, 1)
        "swimming" -> Sport("swim", Measure.Pace, 1)
        else -> Sport("run", Measure.Pace, 1)
    }

    /** Metres in one split for [sport] in the person's units. */
    fun splitMetres(sport: Sport, imperial: Boolean): Double =
        sport.splitUnits * (if (imperial) METRES_PER_MILE else 1000.0)

    /** "Starting run. Walk for 5 minutes." */
    fun start(sport: Sport, firstStep: String?): String =
        listOfNotNull("Starting ${sport.word}.", firstStep?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }?.let { "$it." })
            .joinToString(" ")

    /** "Run complete! Good work, Hawk." -- the praise varies, by [pick]. */
    fun finish(sport: Sport, name: String?, pick: Int): String {
        val praise = PRAISE[Math.floorMod(pick, PRAISE.size)]
        val first = name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotEmpty() }
        return "${sport.word.replaceFirstChar { it.uppercase() }} complete! " +
            if (first != null) "$praise, $first." else "$praise."
    }

    /**
     * The split just crossed: [index] is its number (1-based) in split units,
     * [splitMs] how long that split alone took.
     */
    fun split(sport: Sport, index: Int, splitMs: Long, imperial: Boolean): String {
        val unit = if (imperial) "Mile" else "Kilometre"
        val mark = index * sport.splitUnits
        val metres = splitMetres(sport, imperial)
        val seconds = splitMs / 1000.0
        return when (sport.measure) {
            Measure.Pace -> {
                val perUnit = (splitMs / sport.splitUnits)
                "$unit $mark. Pace ${spokenClock(perUnit)}."
            }
            Measure.Speed -> {
                val perHour = if (seconds > 0) metres / seconds * 3.6 / (if (imperial) 1.609344 else 1.0) else 0.0
                val speedUnit = if (imperial) "miles per hour" else "kilometres per hour"
                "$unit $mark. Average ${"%.1f".format(perHour).removeSuffix(".0")} $speedUnit."
            }
            Measure.Per500 -> {
                val per500 = if (metres > 0) (splitMs * 500.0 / metres).toLong() else 0L
                "$unit $mark. ${spokenClock(per500)} per 500 metres."
            }
        }
    }

    const val METRES_PER_MILE = 1609.344

    /** "Good work" and its cousins, so the end of every session is not the same sentence. */
    val PRAISE = listOf(
        "Good work", "Great job", "Awesome", "Nicely done", "Well done", "Fantastic effort",
        "Strong finish", "Brilliant", "Way to go", "That's how it's done", "Excellent work", "Superb",
    )
}
