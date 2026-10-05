// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import com.tracks.core.api.TrainingGoal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalDraftTest {

    /**
     * A goal type writes only its own fields. Writing the others as null would
     * stamp them and erase what was set while the goal was another type.
     */
    @Test
    fun a_volume_goal_does_not_write_event_fields() {
        val v = GoalDraft(goalType = "volume_target", targetWeeklyKm = 40.0, volumeSport = "cycling").values()
        assertEquals(40.0, v["target_weekly_km"])
        assertEquals("cycling", v["volume_sport"])
        assertFalse("event_date" in v)
        assertFalse("event_sport" in v)
    }

    @Test
    fun a_discipline_is_written_only_for_the_sport_that_has_one() {
        val run = GoalDraft(eventSport = "running", eventDate = "2026-11-01").values()
        assertFalse("mtb_discipline" in run)
        val mtb = GoalDraft(eventSport = "mountain biking", eventDate = "2026-11-01", mtbDiscipline = "xco").values()
        assertEquals("xco", mtb["mtb_discipline"])
    }

    /** An edit re-stamps only what changed, so a concurrent edit elsewhere to another field survives. */
    @Test
    fun an_edit_writes_only_the_fields_that_changed() {
        val goal = TrainingGoal(
            id = 7, goalType = "event", eventName = "Half", eventSport = "running",
            eventDate = "2026-11-15", eventDistanceMeters = 21097.0, daysPerWeek = 4, planIntensity = 1.0,
        )
        val edited = GoalDraft.of(goal).copy(daysPerWeek = 5)
        assertEquals(mapOf<String, Any?>("days_per_week" to 5), edited.changedValues(goal))
    }

    @Test
    fun save_waits_for_what_each_goal_type_needs() {
        assertNotNull(GoalDraft(goalType = "event").missing)
        assertNull(GoalDraft(goalType = "event", eventDate = "2026-11-01").missing)
        assertNull(GoalDraft(goalType = "fitness").missing)
    }

    /**
     * A fitness goal writes its sport where every plan path reads a sport
     * from, and its ramp — and no event date, which would make it look like a
     * race to anything that checks.
     */
    @Test
    fun a_fitness_goal_writes_its_sport_and_ramp_and_no_event_fields() {
        val v = GoalDraft(goalType = "fitness", fitnessSports = listOf("cycling", "hiking"), ctlRampPerWeek = 3.5,
            eventDate = "2026-11-01").values()
        assertEquals("cycling", v["event_sport"])
        assertEquals(listOf("cycling", "hiking"), v["fitness_sports"])
        assertEquals(3.5, v["ctl_ramp_per_week"])
        assertFalse("event_date" in v)
        assertFalse("event_distance_meters" in v)
    }

    /** A fitness goal trains several sports; one must always stay picked, or there is nothing to plan. */
    @Test
    fun fitness_sports_toggle_but_the_last_one_stays() {
        var d = GoalDraft(goalType = "fitness", fitnessSports = listOf("running"))
        d = d.toggleFitnessSport("alpine_skiing").toggleFitnessSport("climbing")
        assertEquals(listOf("running", "alpine_skiing", "climbing"), d.fitnessSports)
        d = d.toggleFitnessSport("running").toggleFitnessSport("alpine_skiing").toggleFitnessSport("climbing")
        assertEquals(listOf("climbing"), d.fitnessSports)
    }

    /** Pool then open water is one sport to the planner; the second pick replaces the first. */
    @Test
    fun a_second_kind_of_the_same_sport_replaces_the_first() {
        val d = GoalDraft(goalType = "fitness", fitnessSports = listOf("swimming", "running"))
            .toggleFitnessSport("open_water_swimming")
        assertEquals(listOf("open_water_swimming", "running"), d.fitnessSports)
    }

    /** Every new sport the planner builds can be a fitness sport. */
    @Test
    fun every_planned_sport_can_be_picked_for_fitness() {
        val keys = GoalOptions.FITNESS_SPORTS.map { it.first }
        for (s in listOf("open_water_swimming", "cross_country_skiing", "backcountry_skiing", "alpine_skiing",
            "climbing", "rowing", "hiking", "triathlon")) assertTrue(s in keys, s)
    }

    @Test
    fun the_ramp_reads_as_a_signed_rate_and_a_word() {
        assertEquals("+3 CTL / week", GoalOptions.rampLabel(3.0))
        assertEquals("−1.5 CTL / week", GoalOptions.rampLabel(-1.5).replace(',', '.'))
        assertEquals("0 CTL / week", GoalOptions.rampLabel(0.0))
        assertEquals("Detraining", GoalOptions.rampWord(-0.5))
        assertEquals("Maintain", GoalOptions.rampWord(0.0))
        assertEquals("Build", GoalOptions.rampWord(3.0))
        assertEquals("Aggressive", GoalOptions.rampWord(3.5))
    }

    @Test
    fun the_ramp_slider_snaps_to_half_points_inside_its_range() {
        assertEquals(2.5, snapRamp(2.6f))
        assertEquals(-2.0, snapRamp(-3f))
        // +6: the plan delivers it; past it the first week is about twice CTL.
        assertEquals(6.0, snapRamp(9.2f))
    }

    /** A goal saved before the cap opens at the top of the slider, not off its end. */
    @Test
    fun a_ramp_from_before_the_cap_opens_at_the_top_of_the_slider() {
        val goal = com.tracks.core.api.TrainingGoal(id = 1, goalType = "fitness", ctlRampPerWeek = 8.0)
        assertEquals(6.0, GoalDraft.of(goal).ctlRampPerWeek)
    }

    @Test
    fun the_intensity_label_is_the_nearest_web_stop() {
        assertEquals("Moderate", GoalOptions.intensityLabel(1.1))
        assertEquals("Easy", GoalOptions.intensityLabel(0.5))
        assertTrue(GoalOptions.intensityLabel(1.45) == "Max")
    }

    /**
     * The kind of swimming or skiing is the sport itself, which is what the
     * planner and the watch read; the chip it sits under must still light.
     */
    @Test
    fun a_ski_or_swim_kind_is_written_as_the_sport_and_shown_under_its_chip() {
        val alpine = GoalDraft(eventSport = "skiing", eventDate = "2026-12-01").withVariant("alpine_skiing")
        assertEquals("alpine_skiing", alpine.values()["event_sport"])
        assertEquals("skiing", GoalOptions.sportChip("alpine_skiing"))
        assertEquals("swimming", GoalOptions.sportChip("open_water_swimming"))
        assertEquals("running", GoalOptions.sportChip("running"))
        // A bare "skiing" goal is cross-country, the first kind.
        assertEquals("cross_country_skiing", GoalOptions.variantOf("skiing"))
        assertNull(GoalOptions.variantOf("running"))
    }

    /** Alpine leans on the strength planner for its heavy leg work. */
    @Test
    fun choosing_alpine_turns_strength_on_and_other_kinds_leave_it_alone() {
        assertTrue(GoalDraft(eventSport = "skiing").withVariant("alpine_skiing").includeStrength)
        assertFalse(GoalDraft(eventSport = "skiing").withVariant("cross_country_skiing").includeStrength)
    }

    @Test
    fun climbing_is_a_goal_sport_with_presets() {
        assertEquals("Climbing", GoalOptions.SPORTS["climbing"])
        assertNotNull(GoalOptions.PRESETS["climbing"])
    }
}
