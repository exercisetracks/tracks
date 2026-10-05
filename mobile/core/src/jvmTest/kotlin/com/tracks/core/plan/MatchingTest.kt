// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.spec.SpecFixtures
import com.tracks.core.spec.SpecFixtures.cases
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Replays spec/fixtures/matching.json, plus the phone-only workout pick. */
class MatchingTest {

    private val fx = SpecFixtures.load("matching")

    private fun JsonElement?.str(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.content
    private fun JsonElement?.dbl(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
    private fun JsonElement?.int(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull

    @Test
    fun field_tests_update_thresholds_as_the_server_does() {
        for (c in fx.cases("field_tests")) {
            val e = c["expected"]!!.jsonObject
            val got = Matching.fieldTestUpdates(
                c["test"].str()!!, c["watts"].dbl(), c["max_hr"].int(), c["ftp_mode"].str(), c["hr_mode"].str(),
            )
            assertEquals(Matching.FieldTestUpdate(e["ftp_auto"].int(), e["threshold_hr_auto"].int()), got, "$c")
        }
    }

    @Test
    fun strength_sessions_advance_each_exercise_as_the_server_does() {
        for (c in fx.cases("strength")) {
            val sets = c["sets"]!!.jsonArray.map {
                val o = it.jsonObject
                Matching.SetIn(o["exercise_name"].str(), o["weight_kg"].dbl(), o["repetitions"].int())
            }
            val existing = c["existing"]!!.jsonObject.mapValues { (_, v) ->
                v.jsonObject["estimated_1rm_kg"].dbl() to v.jsonObject["sessions_completed"].int()
            }
            val want = c["expected"]!!.jsonObject.entries.associate { (k, v) ->
                val o = v.jsonObject
                k to Matching.StrengthState(
                    o["estimated_1rm_kg"].dbl(), o["last_weight_kg"].dbl(), o["last_reps"].int(),
                    o["last_session_volume_kg"].dbl()!!, o["sessions_completed"].int()!!, o["progression_stage"].str()!!,
                )
            }
            val got = Matching.strengthFingerprintUpdates(sets, existing)
            assertEquals(want.keys.toList(), got.keys.toList(), "$c")
            assertEquals(want, got, "$c")
        }
    }

    @Test
    fun an_activity_lands_on_the_day_it_happened_in_the_account_zone_as_the_server_says() {
        // Includes the run that exposed this: 18:04 in California, stored as
        // 01:04 UTC the next day, which used to tick off the wrong workout.
        val cases = fx.cases("local_dates")
        assert(cases.isNotEmpty())
        for (c in cases) {
            val got = Matching.localDate(c["started_at"].str()!!, com.tracks.core.time.ZoneOffsets.of(c["tz"].str()))
            assertEquals(c["expected"].str(), got, "$c")
        }
    }

    private fun cand(
        uid: String, plan: String? = "p", date: String = "2026-09-25", type: String = "easy",
        done: String? = null, sport: String? = "running",
    ) = Matching.Candidate(uid, plan, date, type, done, sport)

    /** Two devices must pick the same workout, and matching writes synced fields. */
    @Test
    fun the_earliest_uid_among_eligible_workouts_is_picked_whatever_the_callers_order() {
        val got = Matching.pickWorkout(
            "2026-09-25", "running",
            listOf(
                cand("b-second"),
                cand("other-day", date = "2026-09-24"),
                cand("rest", type = "rest"),
                cand("race", type = "race"),
                cand("done", done = "a1"),
                cand("a-first"),
            ),
        )
        assertEquals("a-first", got?.uid)
    }

    /** A run used to tick off whatever was planned that day, a strength session included. */
    @Test
    fun a_run_does_not_complete_a_workout_of_another_sport() {
        val got = Matching.pickWorkout(
            "2026-09-25", "running",
            listOf(cand("a-lift", sport = "strength_training"), cand("b-run", sport = "trail_running")),
        )
        assertEquals("b-run", got?.uid)
    }

    /** A workout the user added themselves is their plan too; it used to be skipped. */
    @Test
    fun a_hand_added_workout_can_be_completed() {
        assertEquals("mine", Matching.pickWorkout("2026-09-25", "running", listOf(cand("mine", plan = null)))?.uid)
    }

    @Test
    fun nothing_is_picked_when_no_workout_qualifies() {
        assertNull(
            Matching.pickWorkout(
                "2026-09-25", "running",
                listOf(cand("x", sport = "cycling"), cand("y", type = "rest")),
            ),
        )
    }
}
