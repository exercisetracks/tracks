// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import com.tracks.core.spec.SpecFixtures.arr
import com.tracks.core.spec.SpecFixtures.bool
import com.tracks.core.spec.SpecFixtures.cases
import com.tracks.core.spec.SpecFixtures.double
import com.tracks.core.spec.SpecFixtures.doubleOrNull
import com.tracks.core.spec.SpecFixtures.int
import com.tracks.core.spec.SpecFixtures.obj
import com.tracks.core.spec.SpecFixtures.objOrNull
import com.tracks.core.spec.SpecFixtures.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin's half of the race-day fueling pact.
 *
 * This one has no Python side: fueling is a client-side race-day calculator,
 * so spec/fixtures/fueling.json is shared by exactly two implementations —
 * frontend/src/utils/fuelingUtils.js (the original) and
 * mobile/core's [getPerLapDrinks]. That makes this suite the *only* thing
 * standing between the two, which matters more than usual here: the phone runs
 * this with no network, on a start line, and a drink schedule that disagrees
 * with the plan the athlete reviewed in the browser the night before is a
 * silent, physical failure rather than a rendering one.
 */
class FuelingFixtureTest {

    private val fixture by lazy { SpecFixtures.load("fueling") }

    @Test
    fun `default carbs per hour matches at every breakpoint`() {
        val cases = fixture.cases("default_carbs_per_hour_cases")
        assertTrue(cases.size >= 8, "corpus shrank to ${cases.size}")

        val mismatches = cases.mapNotNull { c ->
            val hours = c.double("duration_hours")
            val expected = c.int("expected")
            val got = defaultCarbsPerHour(hours)
            if (got == expected) null else "defaultCarbsPerHour($hours): expected $expected, got $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `heat classification and band selection match`() {
        val mismatches = fixture.cases("fueling_params_cases").mapNotNull { c ->
            val got = computeFuelingParams(c.double("duration_hours"), c.weather())
            val want = c.obj("expected")

            val problems = buildList {
                check("concentrationPct", want.int("concentrationPct"), got.concentrationPct)
                    ?.let { add(it) }
                check("sipIntervalMin", want.int("sipIntervalMin"), got.sipIntervalMin)?.let { add(it) }
                check("firstSipMin", want.int("firstSipMin"), got.firstSipMin)?.let { add(it) }
                check("saltIdx", want.int("saltIdx"), got.saltIdx)?.let { add(it) }
                check("heatAdjusted", want.bool("heatAdjusted"), got.heatAdjusted)?.let { add(it) }
                check("isHot", want.bool("isHot"), got.isHot)?.let { add(it) }
                check("isVeryHot", want.bool("isVeryHot"), got.isVeryHot)?.let { add(it) }
                check("isHumid", want.bool("isHumid"), got.isHumid)?.let { add(it) }
                val wantConc = want.double("concentration")
                if (kotlin.math.abs(wantConc - got.concentration) >= 1e-9) {
                    add("concentration: expected $wantConc, got ${got.concentration}")
                }
            }
            if (problems.isEmpty()) null else "${c.str("name")}: ${problems.joinToString("; ")}"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `per-sip volume rounds the same way`() {
        val mismatches = fixture.cases("sip_ml_cases").mapNotNull { c ->
            val carbs = c.double("carbsPerHour")
            val concentration = c.double("concentration")
            val interval = c.int("sipIntervalMin")
            val expected = c.int("expected")
            val got = computeSipMl(carbs, concentration, interval)
            if (got == expected) null else {
                "computeSipMl($carbs, $concentration, $interval): expected $expected, got $got"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `drinks land on the same laps`() {
        val mismatches = fixture.cases("per_lap_drinks_cases").mapNotNull { c ->
            val got = computePerLapDrinks(
                laps = c.laps(),
                sipIntervalMin = c.int("sip_interval_min"),
                firstSipMin = c.int("first_sip_min"),
                perSipMl = c.int("per_sip_ml"),
            )
            val expected = c.expectedDrinks()
            if (got == expected) null else "${c.str("name")}: expected $expected, got $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `the end-to-end schedule matches`() {
        val mismatches = fixture.cases("get_per_lap_drinks_cases").mapNotNull { c ->
            val got = getPerLapDrinks(
                laps = c.laps(),
                predictedSeconds = c.doubleOrNull("predicted_seconds"),
                fuelingPlanCarbs = c.doubleOrNull("fueling_plan_carbs"),
                weather = c.weather(),
            )
            val expected = c.expectedDrinks()
            if (got == expected) null else "${c.str("name")}: expected $expected, got $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    /**
     * A lap with no pace target contributes no time and takes no drink, and —
     * the part worth pinning — does not shift the ones after it.
     *
     * Named separately because it is the case a re-implementation is most
     * likely to get subtly wrong: the obvious loop advances the clock by a
     * lap's duration before checking whether the lap is usable, which silently
     * moves every later drink earlier.
     */
    @Test
    fun `a lap with no target neither drinks nor shifts the schedule`() {
        val withGap = computePerLapDrinks(
            laps = listOf(
                FuelingLap(300.0, 1000.0),
                FuelingLap(null, null),
                FuelingLap(300.0, 1000.0),
            ),
            sipIntervalMin = 5,
            firstSipMin = 5,
            perSipMl = 100,
        )
        assertEquals(3, withGap.size)
        assertEquals(null, withGap[1])
    }

    // ── Fixture shapes ───────────────────────────────────────────────────────

    private fun kotlinx.serialization.json.JsonObject.weather(): WeatherSnapshot? =
        objOrNull("weather")?.let {
            WeatherSnapshot(
                temperatureC = it.doubleOrNull("temperature_c"),
                humidityPct = it.doubleOrNull("humidity_pct"),
            )
        }

    private fun kotlinx.serialization.json.JsonObject.laps(): List<FuelingLap> =
        arr("laps").map { it.jsonObject }.map {
            FuelingLap(
                targetSecPerKm = it.doubleOrNull("target_sec_per_km"),
                distanceM = it.doubleOrNull("distance_m"),
            )
        }

    private fun kotlinx.serialization.json.JsonObject.expectedDrinks(): List<Drink?> =
        arr("expected").map { element ->
            if (element is JsonNull) null else {
                val o = element.jsonObject
                Drink(sipMl = o.int("sipMl"), atMin = o.int("atMin"))
            }
        }

    private fun <T> check(field: String, want: T, got: T): String? =
        if (want == got) null else "$field: expected $want, got $got"
}
