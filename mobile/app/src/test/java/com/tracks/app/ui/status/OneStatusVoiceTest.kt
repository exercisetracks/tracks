// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.status

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app reports background work in one place, [StatusHost]. Before it, five
 * features had five ways of saying so, drawn over each other — a snackbar here,
 * a toast there, a pill per runner. This fails when one of those comes back;
 * docs/mobile-status-ui.md says what to use instead.
 */
class OneStatusVoiceTest {

    @Test
    fun no_screen_reports_status_its_own_way() {
        val root = File("src/main/java")
        val banned = listOf("SnackbarHost(", "Toast.makeText(", "ProgressPill(", "StatusToast(")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    banned.firstOrNull { it in line && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") }
                        ?.let { "${file.relativeTo(root)}:${i + 1} uses $it" }
                }
            }
            .toList()
        assertTrue("Report status through StatusBoard instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
