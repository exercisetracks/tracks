// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled library is the same spec data the server loads — these guard the
 * two ways it has shown up badly on screen.
 */
class LibraryDataTest {

    private val entries = (EXERCISE_LIBRARY_JSON + STRETCH_LIBRARY_JSON).map {
        val o = Json.parseToJsonElement(it).jsonObject
        Triple(
            o.getValue("name").jsonPrimitive.content,
            o["primary_muscles"]?.jsonArray?.map { m -> m.jsonPrimitive.content }.orEmpty(),
            o["secondary_muscles"]?.jsonArray?.map { m -> m.jsonPrimitive.content }.orEmpty(),
        )
    }

    /** Without a label a raw key like `hip_external_rotators` reaches the screen. */
    @Test
    fun every_library_muscle_has_a_display_name() {
        val missing = entries.flatMap { it.second + it.third }.toSet() - MUSCLE_LABELS.keys
        assertEquals(emptySet(), missing)
    }

    /** The same muscle twice showed as "calves calves". */
    @Test
    fun no_library_entry_lists_a_muscle_twice() {
        for ((name, primary, secondary) in entries) {
            assertEquals(primary.size, primary.toSet().size, name)
            assertEquals(secondary.size, secondary.toSet().size, name)
            assertTrue((primary intersect secondary.toSet()).isEmpty(), name)
        }
    }
}
