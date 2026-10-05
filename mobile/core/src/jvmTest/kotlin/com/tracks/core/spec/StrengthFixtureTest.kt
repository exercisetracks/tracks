// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import com.tracks.core.spec.SpecFixtures.arr
import com.tracks.core.spec.SpecFixtures.bool
import com.tracks.core.spec.SpecFixtures.cases
import com.tracks.core.spec.SpecFixtures.double
import com.tracks.core.spec.SpecFixtures.int
import com.tracks.core.spec.SpecFixtures.intOrNull
import com.tracks.core.spec.SpecFixtures.str
import com.tracks.core.spec.SpecFixtures.strOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin's half of the strength equipment/experience pact.
 *
 * Unlike the muscle-group and fueling corpora, the backend is *authoritative*
 * here: the expected values came from the original
 * backend/app/calculators/strength_plan/leveling.py, and the frontend had been
 * carrying a hand-maintained mirror of one of its four tables with a comment
 * asking whoever edited it to keep both in sync. That comment is the exact
 * failure mode spec/ exists to make impossible, so this suite's job is to prove
 * the Kotlin port did not quietly become a third copy that drifts.
 */
class StrengthFixtureTest {

    private val fixture by lazy { SpecFixtures.load("strength") }

    @Test
    fun `the equipment vocabulary matches the backend exactly`() {
        val expected = fixture.arr("equipment").map { it.jsonObject }.map {
            EquipmentOption(it.str("value"), it.str("label"), it.str("description"))
        }
        assertEquals(expected, equipmentOptions)
        // The validation set and the display list must not be able to disagree
        // about which values exist — that split is what the backend's
        // VALID_EQUIPMENT and the frontend's EQUIPMENT_OPTIONS used to risk.
        assertEquals(expected.map { it.value }.toSet(), validEquipment)
    }

    @Test
    fun `the experience levels match the backend, in order`() {
        val expected = fixture.arr("experience_levels").map { it.jsonPrimitive.content }
        assertEquals(expected, experienceLevels)
    }

    @Test
    fun `default tier matches for every case`() {
        val mismatches = fixture.cases("default_tier_cases").mapNotNull { c ->
            val experience = c.strOrNull("experience")
            val endurance = c.bool("endurance_goal")
            val expected = c.int("expected")
            val got = experienceDefaultTier(experience, endurance)
            if (got == expected) null else {
                "experienceDefaultTier($experience, $endurance): expected $expected, got $got"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `max difficulty matches for every case`() {
        val mismatches = fixture.cases("max_difficulty_cases").mapNotNull { c ->
            val experience = c.strOrNull("experience")
            val expected = c.intOrNull("expected")
            val got = experienceMaxDifficulty(experience)
            if (got == expected) null else {
                "experienceMaxDifficulty($experience): expected $expected, got $got"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `stage floor matches for every case`() {
        val mismatches = fixture.cases("stage_floor_cases").mapNotNull { c ->
            val experience = c.strOrNull("experience")
            val expected = c.int("expected")
            val got = experienceStageFloor(experience)
            if (got == expected) null else {
                "experienceStageFloor($experience): expected $expected, got $got"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `starting weight factor matches for every case`() {
        val mismatches = fixture.cases("starting_weight_factor_cases").mapNotNull { c ->
            val experience = c.strOrNull("experience")
            val expected = c.double("expected")
            val got = startingWeightFactor(experience)
            if (kotlin.math.abs(got - expected) < 1e-9) null else {
                "startingWeightFactor($experience): expected $expected, got $got"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    /**
     * Every lookup has to survive an experience value it has never seen.
     *
     * `strength_experience` is nullable in the database — "never asked" is a
     * real state, not a bug — and the column is a free string rather than an
     * enum, so a stale client or a hand-edited row can produce anything. The
     * corpus covers null, "", and a bogus value for exactly this reason; this
     * test names the invariant so a future contributor sees why.
     */
    @Test
    fun `unknown experience falls back rather than throwing`() {
        for (unknown in listOf(null, "", "bogus_value")) {
            assertEquals(3, experienceDefaultTier(unknown, enduranceGoal = false))
            assertEquals(3, experienceDefaultTier(unknown, enduranceGoal = true))
            assertEquals(null, experienceMaxDifficulty(unknown))
            assertEquals(0, experienceStageFloor(unknown))
            assertEquals(1.0, startingWeightFactor(unknown))
        }
    }
}
