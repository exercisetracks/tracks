// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import com.tracks.core.spec.SpecFixtures.arr
import com.tracks.core.spec.SpecFixtures.cases
import com.tracks.core.spec.SpecFixtures.intMap
import com.tracks.core.spec.SpecFixtures.intOrNull
import com.tracks.core.spec.SpecFixtures.numberMap
import com.tracks.core.spec.SpecFixtures.obj
import com.tracks.core.spec.SpecFixtures.str
import com.tracks.core.spec.SpecFixtures.strOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin's half of the muscle-activation pact.
 *
 * The same spec/fixtures/muscle_groups.json runs against
 * backend/tests/test_spec/test_muscle_groups.py and
 * frontend/src/test/MuscleGroups.test.js. The expected values came from the
 * original frontend/src/utils/muscleGroups.js, so agreeing with them means the
 * YAML transcription changed no behaviour *and* the three hand-written
 * evaluators still agree.
 *
 * Worth stating what this protects, because the phone is the one client that
 * cannot fall back to asking the server: a strength session's muscle map is
 * computed on-device from the FIT the watch just handed over, with no network.
 * A Kotlin-only rounding or filtering divergence would show a different body
 * diagram for the same workout depending on which screen you opened.
 */
class MuscleGroupsFixtureTest {

    private val fixture by lazy { SpecFixtures.load("muscle_groups") }

    @Test
    fun `every activation case reduces identically`() {
        val cases = fixture.cases("activation_cases")
        assertTrue(cases.size >= 10, "corpus shrank to ${cases.size}")

        val mismatches = cases.mapNotNull { c ->
            val sets = c.arr("sets").map { it.jsonObject }.map { s ->
                StrengthSet(
                    setType = s.strOrNull("set_type"),
                    exerciseCategory = s.strOrNull("exercise_category"),
                    repetitions = s.intOrNull("repetitions"),
                )
            }
            val expected = c.obj("expected")
            val got = computeMuscleActivation(sets)

            val problems = buildList {
                val wantActivation = expected.numberMap("activation")
                if (!sameNumbers(wantActivation, got.activation)) {
                    add("activation: expected $wantActivation, got ${got.activation}")
                }
                val wantTotals = expected.numberMap("totals")
                if (!sameNumbers(wantTotals, got.totals)) {
                    add("totals: expected $wantTotals, got ${got.totals}")
                }
                val wantCounts = expected.intMap("category_counts")
                if (wantCounts != got.categoryCounts) {
                    add("category_counts: expected $wantCounts, got ${got.categoryCounts}")
                }
            }
            if (problems.isEmpty()) null else "${c.str("name")}: ${problems.joinToString("; ")}"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `category labels match`() {
        val mismatches = fixture.cases("category_label_cases").mapNotNull { c ->
            val key = c.strOrNull("key")
            val expected = c.str("expected")
            val got = categoryLabel(key)
            if (got == expected) null else "categoryLabel($key): expected $expected, got $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `muscle labels match`() {
        val mismatches = fixture.cases("muscle_label_cases").mapNotNull { c ->
            val key = c.str("key")
            val expected = c.str("expected")
            val got = muscleLabel(key)
            if (got == expected) null else "muscleLabel($key): expected $expected, got $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    /**
     * A zero-rep set is treated as ten, not as zero.
     *
     * Called out as its own test because it is the one place this port
     * deliberately preserves a JavaScript quirk — `set.repetitions || 10`, where
     * `0` is falsy — rather than "fixing" it. A silent correction here would
     * make the phone disagree with the web app on any set logged with zero
     * reps, which is exactly the drift spec/ exists to prevent. If this ever
     * changes it must change in all three languages at once.
     */
    @Test
    fun `zero reps falls back to ten, matching the original JavaScript`() {
        val zero = computeMuscleActivation(listOf(StrengthSet("active", "curl", 0)))
        val ten = computeMuscleActivation(listOf(StrengthSet("active", "curl", 10)))
        assertEquals(ten.totals, zero.totals)
        assertEquals(10.0, zero.totals["biceps"])
    }

    @Test
    fun `reps are clamped so one long set cannot dominate a session`() {
        val hundred = computeMuscleActivation(listOf(StrengthSet("active", "curl", 100)))
        val forty = computeMuscleActivation(listOf(StrengthSet("active", "curl", 40)))
        assertEquals(forty.totals, hundred.totals)
    }

    /** Doubles from JSON and doubles from arithmetic need a tolerance. */
    private fun sameNumbers(want: Map<String, Double>, got: Map<String, Double>): Boolean {
        if (want.keys != got.keys) return false
        return want.all { (k, v) -> kotlin.math.abs(v - got.getValue(k)) < 1e-9 }
    }
}
