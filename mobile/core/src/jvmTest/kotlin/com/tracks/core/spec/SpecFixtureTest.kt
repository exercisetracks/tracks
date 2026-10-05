// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kotlin's half of the cross-language pact.
 *
 * The Python suite (backend/tests/test_spec/) and the JavaScript suite
 * (frontend/src/test/SportTaxonomy.test.js) read the same
 * spec/fixtures/sport_taxonomy.json. Its expected values came from the original
 * sportUtils.js, so agreeing with it means two things at once: the YAML
 * transcription changed no behaviour, and the three hand-written evaluators
 * still agree with each other.
 *
 * Lives in jvmTest rather than commonTest because it reads a file. commonMain
 * and commonTest have to compile for iOS, and reaching for java.io there is
 * exactly the mistake the module boundary exists to catch.
 */
class SpecFixtureTest {

    private val fixtures: File by lazy {
        // Walk up to the repo root: the module can be built from mobile/ or
        // from the repo root, and the fixtures live outside the Gradle build.
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "spec/fixtures/sport_taxonomy.json")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("spec/fixtures/sport_taxonomy.json not found above ${System.getProperty("user.dir")}")
    }

    private val cases by lazy {
        Json.parseToJsonElement(fixtures.readText())
            .jsonObject["cases"]!!.jsonArray
            .map { it.jsonObject }
    }

    // ── The shared corpus ────────────────────────────────────────────────────

    @Test
    fun `corpus is substantial and covers every sport type`() {
        // A corpus that silently shrank would make the test below pass without
        // checking anything.
        assertTrue(cases.size >= 50, "corpus shrank to ${cases.size}")
        val produced = cases.map { it["expected"]!!.jsonPrimitive.content }.toSet()
        assertEquals(sportTypes.toSet(), produced, "corpus no longer covers every type")
    }

    @Test
    fun `every case classifies identically`() {
        val mismatches = cases.mapNotNull { c ->
            val sport = c["sport"]!!.jsonPrimitive.content
            val sub = c["sub_sport"]!!.jsonPrimitive.content
            val expected = c["expected"]!!.jsonPrimitive.content
            val got = sportType(sport, sub)
            if (got == expected) null else "(\"$sport\", \"$sub\"): expected $expected, got $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    // ── Ordering invariants ──────────────────────────────────────────────────
    // Stated as behaviour so a reordering fails with a readable name rather
    // than as one line in a corpus diff.

    @Test
    fun `bouldering beats climbing`() {
        assertEquals("bouldering", sportType("rock_climbing", "bouldering"))
        assertEquals("climbing", sportType("rock_climbing", "indoor_climbing"))
    }

    @Test
    fun `a yoga sub-sport beats bare training`() {
        // Garmin files a logged yoga session as training/yoga; the sub-sport
        // says what it was. Treating it as strength put yoga on the strength layout.
        assertEquals("mind_body", sportType("training", "yoga"))
        assertEquals("mind_body", sportType("training", "pilates"))
        assertEquals("mind_body", sportType("fitness_equipment", "yoga"))
        assertEquals("strength", sportType("training", ""))
    }

    @Test
    fun `mtb beats generic cycling and indoor beats both`() {
        assertEquals("mtb", sportType("mountain_biking", ""))
        assertEquals("cycling", sportType("cycling", "road"))
        assertEquals("indoor_cycling", sportType("cycling", "virtual"))
    }

    @Test
    fun `hiking exclusion can strand a pair on the fallback`() {
        // Looks like a bug, is the original behaviour — see the matching test
        // in backend/tests/test_spec/test_sport_taxonomy.py for the reasoning.
        assertEquals("mtb", sportType("hiking", "mountain_biking"))
        assertEquals("other", sportType("walking", "cycling"))
    }

    @Test
    fun `degenerate input does not throw`() {
        assertEquals(FALLBACK, sportType(null, null))
        assertEquals(FALLBACK, sportType("", ""))
        assertEquals("climbing", sportType("Rock Climbing", "Indoor Climbing"))
    }

    // ── Zone arithmetic ──────────────────────────────────────────────────────
    // These constants come from backend/app/calculators/zones.py. They caught
    // Kotlin's roundToInt() rounding halves away from zero where Python rounds
    // half to even — a one-unit disagreement on every tie.

    @Test
    fun `friel running zones match the server`() {
        val run = hrZones(170, "running")
        assertEquals(7, run.size)
        assertEquals(0, run[0].min)
        assertEquals(143, run[0].max)     // round(170*0.85)-1, half-to-even
        assertEquals(180, run[6].min)     // round(170*1.06)
        assertNull(run[6].max)
    }

    @Test
    fun `cycling zones use the lower aerobic floor`() {
        assertEquals(129, hrZones(160, "cycling")[0].max)   // round(160*0.81)-1
        assertEquals(135, hrZones(160, "running")[0].max)   // round(160*0.85)-1
    }

    @Test
    fun `coggan power zones match the server`() {
        val power = powerZones(250)
        assertEquals(225, power[3].min)   // round(250*0.90)
        assertEquals(261, power[3].max)   // round(250*1.05)-1, half-to-even
        assertNull(power[6].max)
    }

    @Test
    fun `display zones need only max HR`() {
        val display = displayHrZones(200)
        assertEquals(5, display.size)
        assertEquals(119, display[0].max)          // round(200*0.60)-1
        assertEquals("#22c55e", display[0].color)
    }

    @Test
    fun `zone lookup finds the containing zone`() {
        assertEquals(5, zoneNumberFor(172, hrZones(170, "running")))
    }

    @Test
    fun `tsb bands classify on an exclusive lower bound`() {
        assertEquals("transition", tsbBandFor(30.0).key)
        assertEquals("fresh", tsbBandFor(25.0).key)
        assertEquals("grey", tsbBandFor(0.0).key)
        assertEquals("optimal", tsbBandFor(-5.0).key)
        assertEquals("high_risk", tsbBandFor(-30.0).key)
        assertEquals(TSB_UNKNOWN_BAND, tsbBandFor(null).key)
    }

    // ── Generated table shape ────────────────────────────────────────────────

    @Test
    fun `every zone model tiles without gaps`() {
        // A gap silently drops samples out of every zone total, which looks
        // like missing data rather than a table error.
        for ((name, model) in hrModels + powerModels) {
            assertEquals(0.0, model.zones.first().minPct, "$name must start at 0")
            assertNull(model.zones.last().maxPct, "$name must be open-ended")
            for (i in 1 until model.zones.size) {
                assertEquals(
                    model.zones[i - 1].maxPct, model.zones[i].minPct,
                    "$name zone ${i + 1} must abut zone $i",
                )
            }
            assertEquals(
                model.zones.indices.map { it + 1 },
                model.zones.map { it.number },
                "$name must number zones consecutively from 1",
            )
        }
    }

    @Test
    fun `every rule targets a declared type and can match`() {
        for (rule in RULES) {
            assertTrue(rule.type in sportTypes, "rule produces undeclared type ${rule.type}")
            assertTrue(rule.any.isNotEmpty(), "rule ${rule.type} has no match conditions")
        }
        assertTrue(FALLBACK in sportTypes)
    }

    @Test
    fun `the unknown tsb band exists`() {
        assertNotNull(tsbBands.firstOrNull { it.key == TSB_UNKNOWN_BAND })
    }
}
