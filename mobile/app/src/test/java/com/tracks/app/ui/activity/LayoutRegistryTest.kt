// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.core.spec.sportType
import com.tracks.core.spec.sportTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry has to keep up with the spec.
 *
 * [FallbackLayout] is a good fallback and a bad default. It exists so an
 * unrecognised sport still renders its real numbers, but a sport the taxonomy
 * *does* know about falling through to it is a gap, not a design — and it is
 * invisible, because the generic layout looks perfectly reasonable until you
 * notice it is showing a climbing session's distance instead of its grades.
 *
 * That is exactly what happened: `spec/sport_taxonomy.yaml` listed nineteen
 * sport types and [LAYOUTS] claimed four, so the most-recorded sport in the
 * author's own data rendered as though nobody had heard of it. This test is
 * what makes adding a sport to the spec fail loudly here rather than quietly
 * on a phone.
 */
class LayoutRegistryTest {

    /** `other` is the taxonomy's own fallback, and the one type that should
     *  legitimately reach [FallbackLayout]. */
    private val fallbackType = "other"

    @Test
    fun `every sport type in the spec has a layout`() {
        val claimed = LAYOUTS.flatMap { it.sportTypes }.toSet()
        val unclaimed = sportTypes.filterNot { it == fallbackType || it in claimed }
        assertTrue(
            unclaimed.isEmpty(),
            "sport types with no layout, which will silently render generically: $unclaimed",
        )
    }

    @Test
    fun `no sport type is claimed twice`() {
        // Two registrations claiming one sport is not an error the dispatcher
        // reports — layoutFor takes the first and the second is dead code that
        // reads as though it works.
        val all = LAYOUTS.flatMap { it.sportTypes }
        val duplicates = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "claimed by more than one layout: $duplicates")
    }

    @Test
    fun `every claimed type is a real sport type`() {
        // Catches a typo in a registration, which would otherwise mean a layout
        // that is never reached and a sport that silently stays generic.
        val unknown = LAYOUTS.flatMap { it.sportTypes }.filterNot { it in sportTypes }
        assertTrue(unknown.isEmpty(), "not in spec/sport_taxonomy.yaml: $unknown")
    }

    @Test
    fun `an indoor climb is a climb and gets the climbing layout`() {
        // A session logged as rock_climbing/indoor_climbing reads as "climbing
        // indoor" on the watch and could plausibly have been left unclaimed.
        // It is not: the taxonomy folds indoor rope climbing in with rock, so
        // it gets grades and routes rather than the fallback's stat grid.
        assertEquals("climbing", sportType("rock_climbing", "indoor_climbing"))
        assertEquals(
            layoutFor("climbing")::class,
            layoutFor(sportType("rock_climbing", "indoor_climbing"))::class,
        )
    }

    @Test
    fun `an unknown sport still gets the generic layout`() {
        assertEquals(
            layoutFor("other")::class,
            layoutFor("nothing_like_this")::class,
        )
    }
}
