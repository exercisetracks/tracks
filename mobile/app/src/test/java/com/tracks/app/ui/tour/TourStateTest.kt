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
        assertFalse(TourState().shouldStart("dashboard-welcome"))
        assertTrue(ready.shouldStart("dashboard-welcome"))
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
        assertTrue(s.shouldStart("dashboard-welcome"))
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

    // ── The dashboard's two tours ────────────────────────────────────────

    /**
     * A new user's dashboard has nothing on it. Explaining the fitness chart
     * to them described an empty box; the charts wait for something to show.
     */
    @Test
    fun the_charts_tour_waits_until_the_dashboard_has_data() {
        val welcomed = ready.copy(seen = mapOf("phone.dashboard-welcome" to true))
        assertFalse(welcomed.shouldStart("dashboard-charts", hasData = false))
        assertTrue(welcomed.shouldStart("dashboard-charts", hasData = true))
    }

    /** Empty, the welcome sends them out to record something; with data, that tip is beside the point. */
    @Test
    fun the_push_to_record_shows_only_on_an_empty_dashboard() {
        val empty = ready.start("dashboard-welcome", hasData = false).steps.map { it.title }
        val full = ready.start("dashboard-welcome", hasData = true).steps.map { it.title }
        assertTrue("Record your first activity" in empty)
        assertFalse("Record your first activity" in full)
        assertEquals(empty.size - 1, full.size)
    }

    /** The welcome says nothing about the charts; the charts are the other tour's. */
    @Test
    fun the_welcome_does_not_explain_the_charts() {
        val anchors = Tours.all.getValue("dashboard-welcome").mapNotNull { it.anchor }
        listOf("dashboard-fitness", "dashboard-volume", "dashboard-history", "dashboard-sports").forEach {
            assertFalse(it in anchors, "the welcome points at $it")
        }
    }

    /**
     * Both came out of the one dashboard tour. Someone who finished that has
     * read both, and should not have the charts replayed to them.
     */
    @Test
    fun finishing_the_old_dashboard_tour_counts_for_both_new_ones() {
        val s = ready.copy(seen = mapOf("phone.dashboard" to true))
        assertFalse(s.shouldStart("dashboard-welcome", hasData = true))
        assertFalse(s.shouldStart("dashboard-charts", hasData = true))
    }

    /**
     * A standalone phone with no watch has nothing to sync with, and no
     * gesture to do it; a tip teaching one would teach something that does not
     * exist.
     */
    @Test
    fun the_pull_to_sync_tip_needs_a_watch_or_a_server() {
        val alone = ready.copy(canSync = false).start("dashboard-welcome").steps.map { it.title }
        val linked = ready.start("dashboard-welcome").steps.map { it.title }
        assertFalse("Pull to sync" in alone)
        assertTrue("Pull to sync" in linked)
    }

    @Test
    fun the_charts_follow_the_welcome_on_the_dashboard() {
        assertEquals(listOf("dashboard-welcome", "dashboard-charts"), Tours.idsForRoute("dashboard"))
        assertEquals(listOf("health"), Tours.idsForRoute("health"))
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
