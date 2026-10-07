// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.tour

import com.tracks.app.ui.Destination
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tutorial's content against the app it describes.
 *
 * A tip whose anchor has been renamed or deleted does not fail — it quietly
 * shows centred, pointing at nothing — so nobody would notice until a new user
 * did. That is what the source scan here is for.
 */
class TourContentTest {

    @Test
    fun every_page_in_the_sidebar_has_a_tour() {
        Destination.entries.forEach { d ->
            assertTrue(Tours.all[Tours.idFor(d)].orEmpty().isNotEmpty(), "${d.label} has no tour")
        }
    }

    @Test
    fun the_activity_page_has_a_tour_and_other_detail_routes_do_not() {
        assertEquals("activity", Tours.idForRoute(Tours.ACTIVITY_ROUTE))
        assertEquals(null, Tours.idForRoute("workout/{workoutId}"))
    }

    @Test
    fun every_step_says_something() {
        Tours.all.forEach { (id, steps) ->
            steps.forEach { assertTrue(it.title.isNotBlank() && it.body.isNotBlank(), "a blank step in $id") }
        }
    }

    @Test
    fun every_anchor_a_tip_points_at_is_placed_somewhere_in_the_app() {
        val placed = placedAnchors()
        val missing = Tours.all.flatMap { (id, steps) ->
            steps.mapNotNull { it.anchor }.filter { it !in placed }.map { "$id → $it" }
        }
        assertEquals(emptyList(), missing, "tips pointing at anchors no screen places")
    }

    /** Every string literal on a line that places an anchor (`tourAnchor(…)` or `TourAnchor(…)`). */
    private fun placedAnchors(): Set<String> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val literal = Regex("\"([a-z0-9-]+)\"")
        return root.walk()
            .filter { it.extension == "kt" }
            .flatMap { it.readLines().asSequence() }
            .filter { "tourAnchor(" in it || "TourAnchor(" in it }
            .flatMap { line -> literal.findAll(line).map { it.groupValues[1] } }
            .toSet()
    }
}
