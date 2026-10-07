// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

/**
 * A planned workout's colour: hue from what kind of session it is, shade from
 * how hard. The tables are generated from spec/workout_colors.yaml (see its
 * header for why); this is the resolver, the same rules in the same order as
 * the web's frontend/src/lib/workoutColors.js.
 */
object WorkoutColors {

    private val stretchingSport = Regex("flexib|stretch|mobility|yoga")

    private fun baseType(workoutType: String?) = workoutType.orEmpty().substringBefore(':')

    /** `race`, `stretching`, `strength`, or a sport type from the taxonomy. */
    fun family(workoutType: String?, sport: String?, subSport: String? = null): String {
        val type = baseType(workoutType)
        if (type == "race") return "race"
        val s = sport.orEmpty().lowercase()
        if (type in WorkoutColorsData.stretchingTypes || stretchingSport.containsMatchIn(s)) return "stretching"
        if (type == "strength") return "strength"
        val t = sportType(s, subSport)
        return if (t in WorkoutColorsData.familyHue) t else "other"
    }

    /** 1 easy, 2 steady or long, 3 hard; anything unlisted is 2. */
    fun level(workoutType: String?): Int = WorkoutColorsData.levelOf[baseType(workoutType)] ?: 2

    /** The palette hue, or null for a race (drawn inverted, not in a hue). */
    fun hue(workoutType: String?, sport: String?, subSport: String? = null): String? =
        family(workoutType, sport, subSport).let { if (it == "race") null else WorkoutColorsData.familyHue.getValue(it) }

    /** The palette step's ARGB for [hue] at Tailwind [step] (100..900). */
    fun argb(hue: String, step: Int): Long = WorkoutColorsData.palette.getValue(hue)[step / 100 - 1]

    fun shade(level: Int): Shade = WorkoutColorsData.shades[(level - 1).coerceIn(0, 2)]
}
