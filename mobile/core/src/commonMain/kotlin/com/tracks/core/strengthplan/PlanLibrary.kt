// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.strengthplan

import com.tracks.core.api.TracksJson
import com.tracks.core.parse.Py
import com.tracks.core.spec.EXERCISE_LIBRARY_JSON
import com.tracks.core.spec.STRETCH_LIBRARY_JSON

/**
 * The bundled exercise and stretch libraries in the shapes the planners take —
 * `get_exercise_library_cache()`'s dict and `_build_candidates`' rows — so the
 * phone plans from exactly what the server does: both read spec/library/.
 */
object PlanLibrary {

    @Suppress("UNCHECKED_CAST")
    private fun rows(json: List<String>): List<Map<String, Any?>> =
        json.map { dyn(TracksJson.parseToJsonElement(it)) as Map<String, Any?> }

    /** `{name: {...}}` exactly as `get_exercise_library_cache()` builds it. */
    val exercises: Map<String, Map<String, Any?>> by lazy {
        rows(EXERCISE_LIBRARY_JSON).associate { r ->
            (r["name"] as String) to linkedMapOf(
                "garmin_category" to r["garmin_category"],
                "garmin_subtype" to r["garmin_subtype"],
                "has_animation" to Py.truthy(r["has_animation"]),
                "primary_muscles" to (r["primary_muscles"] ?: emptyList<String>()),
                "secondary_muscles" to (r["secondary_muscles"] ?: emptyList<String>()),
                "equipment" to ((r["equipment"] as List<*>?)?.takeIf { it.isNotEmpty() } ?: listOf("bodyweight")),
                "movement_pattern" to r["movement_pattern"],
                "sport_relevance" to (r["sport_relevance"] ?: emptyMap<String, Any?>()),
                "difficulty" to r["difficulty"],
                "is_compound" to r["is_compound"],
                "cues" to (r["cues"] ?: emptyList<String>()),
            )
        }
    }

    val stretches: List<StretchRow> by lazy { rows(STRETCH_LIBRARY_JSON).map(::stretchRow) }

    @Suppress("UNCHECKED_CAST")
    fun stretchRow(r: Map<String, Any?>): StretchRow = StretchRow(
        name = r["name"] as String,
        primaryMuscles = (r["primary_muscles"] as List<String>?).orEmpty(),
        secondaryMuscles = (r["secondary_muscles"] as List<String>?).orEmpty(),
        movementPattern = r["movement_pattern"] as String?,
        difficulty = (r["difficulty"] as Number?)?.toInt(),
        durationPerSideSec = (r["duration_per_side_sec"] as Number?)?.toInt(),
        sets = (r["sets"] as Number?)?.toInt(),
        eachSide = r["each_side"] as Boolean?,
        description = r["description"] as String?,
        garminCategory = r["garmin_category"] as String?,
        garminSubtype = (r["garmin_subtype"] as Number?)?.toLong(),
        equipment = r["equipment"] as List<String>?,
        cues = r["cues"] as List<String>?,
        breathCue = r["breath_cue"] as String?,
        position = r["position"] as String?,
    )
}
