// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.local.RecentLoad
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every event preset, on the two things picking one does: the distance it
 * fills and the name it writes. The web's EventPresets.test.js holds its
 * copy of the table to the same rules.
 *
 * The bug this was written for: picking "10K" after "10 Mile" named the race
 * "10 Mile", because the name was only filled while blank. Here every preset
 * is picked after every other in its sport.
 */
class GoalPresetsTest {

    /**
     * The distance a preset's label names, in metres, or null when it names
     * none. Numbers with a unit ("10K", "50 Mile", "750 m", "1.5 K",
     * "100 mi") read as such; the named distances are the standard ones.
     */
    private fun namedDistance(sport: String, label: String): Double? {
        Regex("""(\d+(?:\.\d+)?)\s*(K|km|m|Mile|mi)\b""").find(label)?.let { m ->
            val n = m.groupValues[1].toDouble()
            return when (m.groupValues[2]) {
                "K", "km" -> n * 1000
                "m" -> n
                else -> n * 1609.34
            }
        }
        val words = mapOf(
            "Half Marathon" to 21097.5, "Metric Century" to 100000.0, "Double Century" to 321869.0,
            "Marathon" to 42195.0,
        )
        words.entries.firstOrNull { it.key in label }?.let { return it.value }
        if (sport == "triathlon") {
            // The three legs summed.
            return when {
                "Super Sprint" in label -> 400.0 + 10000 + 2500
                "Sprint" in label -> 750.0 + 20000 + 5000
                "Olympic" in label -> 1500.0 + 40000 + 10000
                "70.3" in label -> 1900.0 + 90000 + 21100
                "140.6" in label -> 3800.0 + 180000 + 42200
                else -> null
            }
        }
        return null
    }

    @Test
    fun every_preset_carries_the_distance_its_label_names() {
        for ((sport, presets) in GoalOptions.PRESETS) for (p in presets) {
            val named = namedDistance(sport, p.label)
            if (named == null) {
                assertNull(p.distanceMeters, "$sport ${p.label}: a distance its label does not name")
            } else {
                val d = p.distanceMeters ?: error("$sport ${p.label}: names $named m but carries none")
                assertTrue(abs(d - named) / named < 0.005, "$sport ${p.label}: carries $d m, names $named m")
            }
        }
    }

    @Test
    fun picking_any_preset_after_any_other_fills_its_distance_and_its_name() {
        for ((sport, presets) in GoalOptions.PRESETS) for (first in presets) for (then in presets) {
            val d = GoalDraft().pickSport(sport).pickPreset(first).pickPreset(then)
            assertEquals(then.name.orEmpty(), d.eventName, "$sport: ${first.label} then ${then.label}")
            assertEquals(then.id, d.presetId)
            if (then.distanceMeters != null) assertEquals(then.distanceMeters, d.eventDistanceMeters)
            else assertNull(d.eventDistanceMeters, "$sport: ${first.label} then ${then.label} kept a preset's distance")
        }
    }

    @Test
    fun ten_k_after_ten_mile_is_a_10k() {
        val running = GoalOptions.PRESETS.getValue("running")
        val d = GoalDraft().pickPreset(running.first { it.id == "10mile" }).pickPreset(running.first { it.id == "10k" })
        assertEquals("10K", d.eventName)
        assertEquals(10000.0, d.eventDistanceMeters)
    }

    @Test
    fun a_name_somebody_typed_survives_a_preset() {
        val half = GoalOptions.PRESETS.getValue("running").first { it.id == "half" }
        val d = GoalDraft(eventName = "Lisbon Half").pickPreset(half)
        assertEquals("Lisbon Half", d.eventName)
        assertEquals(21097.0, d.eventDistanceMeters)
    }

    @Test
    fun an_olympic_triathlon_is_named_as_one() {
        val oly = GoalOptions.PRESETS.getValue("triathlon").first { it.id == "olympic" }
        assertEquals("Olympic Triathlon", GoalDraft().pickSport("triathlon").pickPreset(oly).eventName)
    }

    @Test
    fun another_sport_clears_what_the_last_preset_filled_in() {
        val marathon = GoalOptions.PRESETS.getValue("running").first { it.id == "marathon" }
        val d = GoalDraft().pickPreset(marathon).pickSport("cycling")
        assertEquals("", d.eventName)
        assertNull(d.eventDistanceMeters)
    }

    @Test
    fun a_new_event_takes_the_recommended_date_until_one_is_picked() {
        val today = CivilDate(2026, 9, 28)
        val advice = RecentLoad.NONE.recommend("running", 5000.0, today)
        val filled = GoalDraft().withAdvice(advice)
        assertEquals(advice.date.isoformat(), filled.eventDate)
        val picked = filled.copy(eventDate = "2027-05-02", eventDateAuto = false)
            .withAdvice(RecentLoad.NONE.recommend("running", 42195.0, today))
        assertEquals("2027-05-02", picked.eventDate)
    }

    @Test
    fun an_edited_goal_keeps_its_date() {
        val advice = RecentLoad.NONE.recommend("running", 5000.0, CivilDate(2026, 9, 28))
        val d = GoalDraft(id = 3, eventDate = "2027-03-01").withAdvice(advice)
        assertEquals("2027-03-01", d.eventDate)
        assertEquals(advice, d.dateAdvice)
    }
}
