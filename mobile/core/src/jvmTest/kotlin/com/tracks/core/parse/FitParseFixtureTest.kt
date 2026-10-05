// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.parse.Canonical.NumberText
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone parses every committed FIT sample exactly as the server does.
 *
 * `spec/fixtures/fit_parse.json` is the server's own parsers and fitdecode run
 * over `spec/fixtures/fit/` — see `spec/make_fit_parse_fixtures.py`. For each
 * file this checks four things, because each catches a different bug:
 *
 * - **which parsers claim it**, and so which one the importer picks — a file
 *   claimed differently is a sleep file imported as an activity;
 * - **every decoded message, field by field**, value and raw value — the
 *   decoder is where most divergence would start, and a parser can hide it
 *   by ignoring the field that differs, until the day it doesn't;
 * - **every parser's output**, not only the selected one's, because a parser
 *   run on the "wrong" file still exercises paths nothing else does;
 * - **that failures fail**: where the server's import raises, the phone's must
 *   too, rather than salvaging a result the server never had.
 *
 * Floats compare bit for bit. No tolerance anywhere: the port reproduces
 * Python's rounding, summation and mean exactly (see [PyMath]), so any
 * difference at all is a bug worth seeing.
 */
class FitParseFixtureTest {

    private val fixture = Canonical.parseJson(File(Canonical.root, "spec/fixtures/fit_parse.json")) as Map<*, *>

    @Suppress("UNCHECKED_CAST")
    private val cases = fixture["cases"] as List<Map<String, Any?>>

    private fun bytesOf(case: Map<String, Any?>): ByteArray =
        File(Canonical.root, case["file"] as String).readBytes()

    @Test
    fun the_corpus_covers_every_parser_and_the_failure_paths() {
        val selected = cases.map { it["selected"] }.toSet()
        assertTrue(setOf("activity", "sleep", "daily", null).all { it in selected }, "selected: $selected")
        assertTrue(cases.any { c -> (c["results"] as Map<*, *>).values.any { (it as Map<*, *>).containsKey("error") } })
        assertTrue(cases.size >= 15, "only ${cases.size} cases")
    }

    @Test
    fun the_samples_on_disk_are_the_ones_the_fixture_was_made_from() {
        for (case in cases) {
            assertEquals(case["sha256"], Canonical.sha256(bytesOf(case)), "${case["file"]} changed since the fixture")
        }
    }

    @Test
    fun each_file_is_claimed_by_the_same_parsers_as_on_the_server() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val bytes = bytesOf(case)
            val claims = case["claims"] as Map<*, *>
            for (kind in FitParsing.Kind.entries) {
                val expected = claims[kind.key] as Boolean
                val actual = FitParsing.canParse(kind, bytes)
                if (expected != actual) failures += "${case["file"]} ${kind.key}: expected $expected, got $actual"
            }
            val selected = FitParsing.select(bytes)?.key
            if (selected != case["selected"]) failures += "${case["file"]} selected $selected, expected ${case["selected"]}"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun every_message_decodes_to_the_same_fields_and_values_as_fitdecode() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val dump = Canonical.messageDump(bytesOf(case))
            val actual = Canonical.write(dump)
            if (Canonical.sha256(actual) == case["messages_sha256"]) continue
            val expected = case["messages"] as List<*>?
            failures += "${case["file"]}: " + (expected?.let { firstDifference(it, dump) } ?: "digest differs")
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun every_parser_produces_what_the_server_produces_or_fails_where_it_fails() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val bytes = bytesOf(case)
            val results = case["results"] as Map<*, *>
            for (kind in FitParsing.Kind.entries) {
                val expected = results[kind.key] as Map<*, *>
                val outcome = runCatching { FitParsing.parse(kind, bytes) }
                if (expected.containsKey("error")) {
                    if (outcome.isSuccess) {
                        failures += "${case["file"]} ${kind.key}: server raised ${expected["error"]}, phone parsed"
                    }
                    continue
                }
                val parsed = outcome.getOrNull()
                if (parsed == null) {
                    failures += "${case["file"]} ${kind.key}: phone threw ${outcome.exceptionOrNull()}"
                    continue
                }
                val want = expected["ok"]
                val got = Canonical.tag(parsed, normalize = true)
                if (Canonical.write(want) != Canonical.write(got)) {
                    failures += "${case["file"]} ${kind.key}: ${firstDifference(want, got)}"
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    companion object {
        /** A path to the first place two trees differ, for a failure message a person can act on. */
        fun firstDifference(expected: Any?, actual: Any?, path: String = "$"): String? {
            if (Canonical.write(expected) == Canonical.write(actual)) return null
            if (expected is Map<*, *> && actual is Map<*, *>) {
                for (key in (expected.keys + actual.keys).map { it as String }.toSortedSet()) {
                    if (!expected.containsKey(key)) return "$path.$key: unexpected ${show(actual[key])}"
                    if (!actual.containsKey(key)) return "$path.$key: missing, expected ${show(expected[key])}"
                    firstDifference(expected[key], actual[key], "$path.$key")?.let { return it }
                }
            }
            if (expected is List<*> && actual is List<*>) {
                for (i in 0 until minOf(expected.size, actual.size)) {
                    firstDifference(expected[i], actual[i], "$path[$i]")?.let { return it }
                }
                return "$path: length ${actual.size}, expected ${expected.size}"
            }
            return "$path: got ${show(actual)}, expected ${show(expected)}"
        }

        private fun show(v: Any?): String {
            val text = when (v) {
                is NumberText -> v.text
                else -> Canonical.write(v)
            }
            return if (text.length > 300) text.take(300) + "…" else text
        }
    }
}
