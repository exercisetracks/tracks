// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.tour

import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourStateTest {

    private val ready = TourState(hydrated = true)

    /** Before the settings row is read, "not seen" only means "not known yet". */
    @Test
    fun nothing_starts_before_the_settings_are_read() {
        assertFalse(TourState().shouldStart("dashboard"))
        assertTrue(ready.shouldStart("dashboard"))
    }

    @Test
    fun a_finished_tour_is_remembered_and_does_not_start_again() {
        val steps = Tours.all.getValue("activities").size
        var s = ready.start("activities")
        repeat(steps) { s = s.next() }
        assertNull(s.active)
        assertEquals(true, s.seen["phone.activities"])
        assertFalse(s.shouldStart("activities"))
    }

    /** Leaving a page mid-tour is not the same as dismissing it — the web's abortTour. */
    @Test
    fun a_tour_left_halfway_shows_again_next_time() {
        val s = ready.start("health").next().abort()
        assertNull(s.active)
        assertTrue(s.shouldStart("health"))
    }

    /**
     * The map is shared with the browser. Finishing the web's dashboard tour
     * must not silence the phone's, whose controls the browser never showed.
     */
    @Test
    fun the_web_s_tours_do_not_count_as_the_phone_s() {
        val s = ready.copy(seen = mapOf("dashboard" to true))
        assertTrue(s.shouldStart("dashboard"))
    }

    @Test
    fun restarting_forgets_only_this_phone_s_tours_and_switches_tips_back_on() {
        val s = ready.copy(enabled = false, seen = mapOf("dashboard" to true, "phone.dashboard" to true)).restart()
        assertTrue(s.enabled)
        assertEquals(mapOf<String, Any?>("dashboard" to true), s.seen)
    }

    @Test
    fun switching_tips_off_closes_the_one_showing_and_starts_no_more() {
        val s = ready.start("maps").withEnabled(false)
        assertNull(s.active)
        assertFalse(s.shouldStart("maps"))
    }

    @Test
    fun back_from_the_first_step_stays_on_it() {
        assertEquals(0, ready.start("maps").prev().step)
    }

    @Test
    fun a_page_with_no_tour_starts_nothing() {
        assertFalse(ready.shouldStart(null))
        assertFalse(ready.shouldStart("no-such-page"))
        assertNull(ready.start("no-such-page").active)
    }

    // ── Where the card goes ──────────────────────────────────────────────

    private fun place(anchor: IntRect?, cardHeight: Int = 200) =
        placeCard(anchor, cardWidth = 300, cardHeight = cardHeight, viewWidth = 400, viewHeight = 800, margin = 16, gap = 12)

    @Test
    fun the_card_sits_under_a_target_near_the_top() {
        val p = place(IntRect(50, 40, 150, 80))
        assertEquals(true, p.below)
        assertEquals(92, p.y)
    }

    @Test
    fun the_card_sits_over_a_target_near_the_bottom() {
        val p = place(IntRect(50, 700, 150, 760))
        assertEquals(false, p.below)
        assertEquals(700 - 12 - 200, p.y)
    }

    /** A list fills the screen; there is no side to put the card on, so it goes over the bottom. */
    @Test
    fun a_target_too_tall_to_sit_beside_gets_the_card_along_the_bottom() {
        val p = place(IntRect(0, 100, 400, 790))
        assertNull(p.below)
        assertEquals(800 - 16 - 200, p.y)
    }

    @Test
    fun the_card_stays_on_screen_for_a_target_in_the_corner() {
        assertEquals(400 - 300 - 16, place(IntRect(360, 10, 390, 40)).x)
        assertEquals(16, place(IntRect(0, 10, 30, 40)).x)
    }

    @Test
    fun a_tip_with_no_target_is_centred() {
        val p = place(null)
        assertNull(p.below)
        assertEquals(50, p.x)
        assertEquals(300, p.y)
    }
}
