// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin formatters must produce byte-identical output to the web app's.
 *
 * `spec/fixtures/format.json` is baselined from
 * `frontend/src/utils/formatUtils.js` and `frontend/src/lib/weight.js` — the
 * implementations already shipping — so this answers the only question that
 * matters about a port: does it agree, everywhere, including at the boundaries
 * where rounding decides the answer.
 *
 * Regenerate after changing a JS formatter:
 *
 *     python3 spec/make_format_fixtures.py
 *
 * A failure then shows exactly which formatter drifted.
 */
class FormatFixtureTest {

    private val fixtures: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "spec/fixtures/format.json")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("spec/fixtures/format.json not found above ${System.getProperty("user.dir")}")
    }

    private val root by lazy { Json.parseToJsonElement(fixtures.readText()).jsonObject }
    private val inputs by lazy { root["inputs"]!!.jsonObject }
    private val expected by lazy { root["expected"]!!.jsonObject }

    /** JSON null means "the value was absent", which every formatter handles. */
    private fun doubles(key: String): List<Double?> =
        inputs[key]!!.jsonArray.map { (it as JsonPrimitive).doubleOrNull }

    private fun strings(key: String): List<String> =
        expected[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun numbers(key: String): List<Double> =
        expected[key]!!.jsonArray.map { it.jsonPrimitive.double }

    /** Compares element-wise so a failure names the input, not just the index. */
    private fun <T> check(
        formatter: String,
        values: List<T>,
        want: List<String>,
        actual: (T) -> String,
    ) {
        assertEquals(values.size, want.size, "$formatter: fixture arity mismatch")
        val wrong = values.indices.mapNotNull { i ->
            val got = actual(values[i])
            if (got == want[i]) null else "  ${values[i]} -> expected ${want[i]}, got $got"
        }
        assertTrue(wrong.isEmpty(), "$formatter diverged:\n${wrong.joinToString("\n")}")
    }

    @Test
    fun `the corpus is present and substantial`() {
        assertTrue(expected.size >= 15, "only ${expected.size} formatters covered")
        assertTrue(strings("elapsed").isNotEmpty())
    }

    @Test
    fun `pace matches`() {
        val speeds = doubles("speeds")
        check("paceMinPerKm", speeds, strings("pace_min_per_km")) { paceMinPerKm(it) }
        check("paceMinPerMile", speeds, strings("pace_min_per_mile")) { paceMinPerMile(it) }
        check("pace(running, metric)", speeds, strings("pace_running_metric")) {
            pace(it, isRunning = true, imperial = false)
        }
        check("pace(cycling, metric)", speeds, strings("pace_cycling_metric")) {
            pace(it, isRunning = false, imperial = false)
        }
        check("pace(running, imperial)", speeds, strings("pace_running_imperial")) {
            pace(it, isRunning = true, imperial = true)
        }
    }

    @Test
    fun `speed matches`() {
        val speeds = doubles("speeds")
        check("speed(metric)", speeds, strings("speed_metric")) { speed(it, false) }
        check("speed(imperial)", speeds, strings("speed_imperial")) { speed(it, true) }
    }

    @Test
    fun `distance matches`() {
        val d = doubles("distances")
        check("distance(metric)", d, strings("distance_metric")) { distance(it, false) }
        check("distance(imperial)", d, strings("distance_imperial")) { distance(it, true) }
    }

    @Test
    fun `elevation matches`() {
        val e = doubles("elevations")
        check("elevation(metric)", e, strings("elevation_metric")) { elevation(it, false) }
        check("elevation(imperial)", e, strings("elevation_imperial")) { elevation(it, true) }
    }

    @Test
    fun `elapsed matches`() {
        check("elapsed", doubles("durations"), strings("elapsed")) { elapsed(it) }
    }

    @Test
    fun `weight matches`() {
        val w = doubles("weights")
        check("weight(metric)", w, strings("weight_metric")) { weight(it, false) }
        check("weight(imperial)", w, strings("weight_imperial")) { weight(it, true) }
    }

    @Test
    fun `kg to display matches`() {
        val w = doubles("weights")
        val wantMetric = numbers("kg_to_display_metric")
        val wantImperial = numbers("kg_to_display_imperial")
        w.indices.forEach { i ->
            assertEquals(wantMetric[i], kgToDisplay(w[i], false), 1e-9, "kgToDisplay(${w[i]}, metric)")
            assertEquals(wantImperial[i], kgToDisplay(w[i], true), 1e-9, "kgToDisplay(${w[i]}, imperial)")
        }
    }

    @Test
    fun `heart-rate colour matches`() {
        val ratios = doubles("hrRatios").map { it ?: 0.0 }
        check("hrColor", ratios, strings("hr_color")) { hrColor(it) }
    }

    @Test
    fun `weight round-trips through the user's unit`() {
        // Not in the corpus because it is a property, not a value: the backend
        // stores kg even for weights entered in pounds, so a value that cannot
        // survive the trip out and back would drift every time it is edited.
        for (kg in listOf(9.0718, 20.0, 47.5, 70.307, 100.0)) {
            for (imperial in listOf(false, true)) {
                val shown = kgToDisplay(kg, imperial)
                val back = displayToKg(shown, imperial)
                assertTrue(
                    kotlin.math.abs(back - kg) < 0.06,
                    "round trip of $kg (imperial=$imperial) gave $back",
                )
            }
        }
    }
}

private val JsonPrimitive.double: Double get() = content.toDouble()
