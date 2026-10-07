// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// GENERATED FILE — DO NOT EDIT.
//
// Source: spec/workout_colors.yaml
// Regenerate: python3 spec/codegen.py
//
// Edits here are silently discarded on the next codegen run. The tables are
// shared across the Python backend, the JS frontend, and the Kotlin mobile
// core precisely so they cannot drift apart — change the spec, not this.
package com.tracks.core.spec

/** Palette steps for one shade; see spec/workout_colors.yaml. */
data class Shade(
    val fill: Int, val text: Int, val border: Int, val dot: Int,
    val darkFill: Int, val darkFillAlpha: Float, val darkText: Int, val darkBorder: Int,
)

object WorkoutColorsData {
    val familyHue: Map<String, String> = mapOf(
        "running" to "blue",
        "hiking" to "lime",
        "cycling" to "amber",
        "indoor_cycling" to "amber",
        "mtb" to "orange",
        "swimming" to "cyan",
        "rowing" to "indigo",
        "paddling" to "yellow",
        "skiing" to "sky",
        "nordic_skiing" to "teal",
        "climbing" to "rose",
        "bouldering" to "rose",
        "triathlon" to "fuchsia",
        "strength" to "violet",
        "stretching" to "pink",
        "mind_body" to "pink",
        "other" to "slate",
    )

    val stretchingTypes: Set<String> = setOf("flexibility", "mobility", "stretch", "yoga")

    val levelOf: Map<String, Int> = mapOf(
        "easy" to 1,
        "easy_recovery" to 1,
        "recovery" to 1,
        "easy_spin" to 1,
        "aerobic" to 1,
        "technique" to 1,
        "ut2" to 1,
        "skills" to 1,
        "pole_hike" to 1,
        "arc" to 1,
        "flexibility" to 1,
        "mobility" to 1,
        "stretch" to 1,
        "yoga" to 1,
        "drills" to 1,
        "long" to 2,
        "long_run" to 2,
        "endurance" to 2,
        "ut1" to 2,
        "back_to_back" to 2,
        "vert" to 2,
        "descent" to 2,
        "eccentric" to 2,
        "agility" to 2,
        "strength" to 2,
        "tempo" to 3,
        "threshold" to 3,
        "css" to 3,
        "sweet_spot" to 3,
        "intervals" to 3,
        "vo2" to 3,
        "vo2max" to 3,
        "race_pace" to 3,
        "fartlek" to 3,
        "short_quality" to 3,
        "sprint" to 3,
        "plyometrics" to 3,
        "hangboard" to 3,
        "limit_bouldering" to 3,
        "incline_intervals" to 3,
        "bounding" to 3,
        "ski_intervals" to 3,
        "power_endurance" to 3,
        "micro_bursts" to 3,
        "over_unders" to 3,
        "matchbook" to 3,
        "standing_starts" to 3,
        "descent_repeats" to 3,
        "field_test" to 3,
    )

    /** Tailwind steps 100..900, as ARGB. */
    val palette: Map<String, LongArray> = mapOf(
        "slate" to longArrayOf(0xFFF1F5F9, 0xFFE2E8F0, 0xFFCBD5E1, 0xFF94A3B8, 0xFF64748B, 0xFF475569, 0xFF334155, 0xFF1E293B, 0xFF0F172A),
        "blue" to longArrayOf(0xFFDBEAFE, 0xFFBFDBFE, 0xFF93C5FD, 0xFF60A5FA, 0xFF3B82F6, 0xFF2563EB, 0xFF1D4ED8, 0xFF1E40AF, 0xFF1E3A8A),
        "lime" to longArrayOf(0xFFECFCCB, 0xFFD9F99D, 0xFFBEF264, 0xFFA3E635, 0xFF84CC16, 0xFF65A30D, 0xFF4D7C0F, 0xFF3F6212, 0xFF365314),
        "amber" to longArrayOf(0xFFFEF3C7, 0xFFFDE68A, 0xFFFCD34D, 0xFFFBBF24, 0xFFF59E0B, 0xFFD97706, 0xFFB45309, 0xFF92400E, 0xFF78350F),
        "orange" to longArrayOf(0xFFFFEDD5, 0xFFFED7AA, 0xFFFDBA74, 0xFFFB923C, 0xFFF97316, 0xFFEA580C, 0xFFC2410C, 0xFF9A3412, 0xFF7C2D12),
        "cyan" to longArrayOf(0xFFCFFAFE, 0xFFA5F3FC, 0xFF67E8F9, 0xFF22D3EE, 0xFF06B6D4, 0xFF0891B2, 0xFF0E7490, 0xFF155E75, 0xFF164E63),
        "indigo" to longArrayOf(0xFFE0E7FF, 0xFFC7D2FE, 0xFFA5B4FC, 0xFF818CF8, 0xFF6366F1, 0xFF4F46E5, 0xFF4338CA, 0xFF3730A3, 0xFF312E81),
        "yellow" to longArrayOf(0xFFFEF9C3, 0xFFFEF08A, 0xFFFDE047, 0xFFFACC15, 0xFFEAB308, 0xFFCA8A04, 0xFFA16207, 0xFF854D0E, 0xFF713F12),
        "sky" to longArrayOf(0xFFE0F2FE, 0xFFBAE6FD, 0xFF7DD3FC, 0xFF38BDF8, 0xFF0EA5E9, 0xFF0284C7, 0xFF0369A1, 0xFF075985, 0xFF0C4A6E),
        "teal" to longArrayOf(0xFFCCFBF1, 0xFF99F6E4, 0xFF5EEAD4, 0xFF2DD4BF, 0xFF14B8A6, 0xFF0D9488, 0xFF0F766E, 0xFF115E59, 0xFF134E4A),
        "rose" to longArrayOf(0xFFFFE4E6, 0xFFFECDD3, 0xFFFDA4AF, 0xFFFB7185, 0xFFF43F5E, 0xFFE11D48, 0xFFBE123C, 0xFF9F1239, 0xFF881337),
        "fuchsia" to longArrayOf(0xFFFAE8FF, 0xFFF5D0FE, 0xFFF0ABFC, 0xFFE879F9, 0xFFD946EF, 0xFFC026D3, 0xFFA21CAF, 0xFF86198F, 0xFF701A75),
        "violet" to longArrayOf(0xFFEDE9FE, 0xFFDDD6FE, 0xFFC4B5FD, 0xFFA78BFA, 0xFF8B5CF6, 0xFF7C3AED, 0xFF6D28D9, 0xFF5B21B6, 0xFF4C1D95),
        "pink" to longArrayOf(0xFFFCE7F3, 0xFFFBCFE8, 0xFFF9A8D4, 0xFFF472B6, 0xFFEC4899, 0xFFDB2777, 0xFFBE185D, 0xFF9D174D, 0xFF831843),
    )

    /** Index 0 is shade 1. */
    val shades: List<Shade> = listOf(
        Shade(100, 700, 200, 400, 900, 0.4f, 300, 800),
        Shade(200, 800, 300, 500, 800, 0.55f, 200, 700),
        Shade(300, 900, 400, 700, 700, 0.7f, 100, 600),
    )
}
