// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Real watch files parse on the phone exactly as on the server — when they are
 * on this machine.
 *
 * The synthetic corpus can only exercise what somebody thought to write into
 * it; a year of real files exercises what a watch actually does. But those
 * files are someone's GPS and health history, so neither they nor anything
 * derived from them is committed: `make_fit_parse_fixtures.py` records only
 * digests, into the gitignored `spec/fixtures/personal/`, along with where the
 * files were. On a machine without them this test has nothing to check and
 * says so, rather than failing a checkout that is simply not the author's.
 */
class PersonalFitParityTest {

    @Test
    fun real_files_parse_identically_when_present() {
        val fixtureFile = File(Canonical.root, "spec/fixtures/personal/fit_parse_personal.json")
        if (!fixtureFile.isFile) {
            println("PersonalFitParityTest: no personal fixture, skipped")
            return
        }
        val fixture = Canonical.parseJson(fixtureFile) as Map<*, *>
        val root = File((fixture["root"] as String).replaceFirst("~", System.getProperty("user.home")))
        if (!root.isDirectory) {
            println("PersonalFitParityTest: $root absent, skipped")
            return
        }

        val failures = ArrayList<String>()
        var checked = 0
        for (case in fixture["cases"] as List<*>) {
            case as Map<*, *>
            val file = File(root, case["file"] as String)
            if (!file.isFile) continue
            val bytes = file.readBytes()
            if (Canonical.sha256(bytes) != case["sha256"]) continue
            checked++
            val name = case["file"]

            val claims = case["claims"] as Map<*, *>
            for (kind in FitParsing.Kind.entries) {
                val got = FitParsing.canParse(kind, bytes)
                if (got != claims[kind.key]) failures += "$name ${kind.key}: claims $got"
            }
            val selected = FitParsing.select(bytes)
            if (selected?.key != case["selected"]) failures += "$name: selected ${selected?.key}"

            if (Canonical.sha256(Canonical.write(Canonical.messageDump(bytes))) != case["messages_sha256"]) {
                failures += "$name: decoded messages differ"
            }

            for ((key, expected) in case["result"] as Map<*, *>) {
                val kind = FitParsing.Kind.entries.first { it.key == key }
                val outcome = runCatching { FitParsing.parse(kind, bytes) }
                if (expected is Map<*, *>) {
                    if (outcome.isSuccess) failures += "$name $key: server raised ${expected["error"]}, phone parsed"
                } else {
                    val parsed = outcome.getOrElse {
                        failures += "$name $key: phone threw $it"
                        null
                    } ?: continue
                    val digest = Canonical.sha256(Canonical.write(Canonical.tag(parsed, normalize = true)))
                    if (digest != expected) failures += "$name $key: output differs"
                }
            }
        }
        println("PersonalFitParityTest: checked $checked real files, ${failures.size} failures")
        if (failures.isNotEmpty()) fail(failures.take(40).joinToString("\n"))
    }
}
