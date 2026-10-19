// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import com.tracks.core.api.FlexibilityFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hands-free player's clock and cues — the parts that decide what the phone says and when. */
class FlowEngineTest {

    private val pigeonL = Hold(null, "Pigeon", 30, 10, side = "Left")
    private val pigeonR = Hold(null, "Pigeon", 30, 10, side = "Right")
    private val fold = Hold(null, "Forward Fold", 20, 0)
    private fun session(vararg holds: Hold) = FlowSession(flow = FlexibilityFlow(id = 1, name = "Hips"), holds = holds.toList())

    @Test
    fun the_other_side_of_the_same_stretch_is_announced_as_switch_sides() {
        val s = FlowEngine.start(session(pigeonL, pigeonR), 0).session
        val rest = FlowEngine.tick(s, 30_000)
        assertTrue(rest.cues.single() is FlowEngine.Cue.Rest)
        val next = FlowEngine.tick(rest.session, 40_000)
        val cue = next.cues.single() as FlowEngine.Cue.Hold
        assertTrue(cue.switchSides)
        assertTrue(FlowEngine.spoken(cue).startsWith("Switch sides."))
    }

    @Test
    fun a_different_stretch_is_announced_by_name_not_as_a_side_switch() {
        val s = FlowEngine.start(session(pigeonR, fold), 0).session
        val cues = FlowEngine.tick(s, 40_000).cues
        val hold = cues.filterIsInstance<FlowEngine.Cue.Hold>().single()
        assertFalse(hold.switchSides)
        assertEquals("Forward Fold. 20 seconds.", FlowEngine.spoken(hold))
    }

    /** The screen was off and ticks were missed: a late tick catches up through every phase that ended. */
    @Test
    fun a_late_tick_crosses_every_phase_that_ended_meanwhile() {
        val s = FlowEngine.start(session(pigeonL, pigeonR, fold), 0).session
        val step = FlowEngine.tick(s, 85_000)   // 30 hold + 10 rest + 30 hold + 10 rest, then 5 s into the fold
        assertEquals(2, step.session.index)
        assertEquals(HoldPhase.Holding, step.session.phase)
        assertEquals(15, step.session.remaining)
    }

    @Test
    fun there_is_no_rest_after_the_last_hold() {
        val s = FlowEngine.start(session(pigeonL), 0).session
        val step = FlowEngine.tick(s, 30_000)
        assertEquals(HoldPhase.Done, step.session.phase)
        assertEquals(listOf(FlowEngine.Cue.Done), step.cues)
    }

    @Test
    fun pausing_stops_the_clock_and_resuming_keeps_what_was_left() {
        val s = FlowEngine.start(session(pigeonL), 0).session
        val paused = FlowEngine.pause(s, 12_000)
        assertEquals(18, FlowEngine.tick(paused, 500_000).session.remaining)
        val resumed = FlowEngine.resume(paused, 500_000)
        assertEquals(18, resumed.remaining)
        assertEquals(HoldPhase.Holding, FlowEngine.tick(resumed, 517_000).session.phase)
    }

    @Test
    fun extending_adds_to_the_running_phase() {
        val s = FlowEngine.start(session(pigeonL), 0).session
        assertEquals(45, FlowEngine.extend(s, 15, 0).remaining)
    }

    @Test
    fun skip_turns_a_hold_into_its_rest_and_a_rest_into_the_next_hold() {
        val s = FlowEngine.start(session(pigeonL, pigeonR), 0).session
        val rest = FlowEngine.skip(s, 5_000).session
        assertEquals(HoldPhase.Resting, rest.phase)
        val next = FlowEngine.skip(rest, 6_000).session
        assertEquals(1, next.index)
        assertEquals(HoldPhase.Holding, next.phase)
    }

    @Test
    fun back_early_in_a_hold_goes_to_the_previous_one_and_later_restarts_it() {
        val s = FlowEngine.tick(FlowEngine.start(session(pigeonL, pigeonR), 0).session, 40_000).session
        assertEquals(1, s.index)
        assertEquals(0, FlowEngine.back(s, 41_000).session.index)
        val later = FlowEngine.back(s, 55_000).session
        assertEquals(1, later.index)
        assertEquals(30, later.remaining)
    }

    /** The classic count into the end of a hold: "3, 2, 1", once each, before "rest". */
    @Test
    fun a_long_hold_counts_three_two_one_into_its_end() {
        var step = FlowEngine.start(session(pigeonL, pigeonR), 0)
        val said = mutableListOf<String>()
        var t = 0L
        while (t <= 31_000) {
            step = FlowEngine.tick(step.session, t)
            said += step.cues.map { FlowEngine.spoken(it) }
            t += 250
        }
        assertEquals(listOf("3", "2", "1", "Rest. Next up, Pigeon, Right side."), said)
    }

    @Test
    fun a_short_hold_is_not_counted_down() {
        val quick = Hold(null, "Neck Roll", 8, 0)
        var step = FlowEngine.start(session(quick, fold), 0)
        val cues = mutableListOf<FlowEngine.Cue>()
        for (t in 0L..7_000L step 250) { step = FlowEngine.tick(step.session, t); cues += step.cues }
        assertTrue(cues.none { it is FlowEngine.Cue.Countdown })
    }

    @Test
    fun the_flow_opens_by_naming_its_first_stretch() {
        val first = FlowEngine.start(session(fold), 0).cues.single()
        assertEquals("Starting flexibility flow. First up, Forward Fold. 20 seconds.", FlowEngine.spoken(first))
    }

    @Test
    fun a_rest_before_the_next_set_says_which_set() {
        val set1 = Hold(null, "Child's Pose", 30, 15, set = 1)
        val set2 = Hold(null, "Child's Pose", 30, 15, set = 2)
        val rest = FlowEngine.tick(FlowEngine.start(session(set1, set2), 0).session, 30_000).cues.single()
        assertEquals("Rest. Set 2 of 2 next.", FlowEngine.spoken(rest))
    }

    @Test
    fun the_end_thanks_the_person_by_name() {
        assertEquals("Flexibility flow complete! Good work, Hawk.", FlowEngine.spoken(FlowEngine.Cue.Done, "Hawk", 0))
    }
}

