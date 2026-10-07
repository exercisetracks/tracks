// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PasswordFieldTest {

    /**
     * A field masked by hand gets the dots and nothing else: the keyboard is
     * never told it is a secret, so it autocorrects the password, shows it in
     * the suggestion strip, and learns it — and there is no eye to check it.
     * That is how four of them came to exist beside PasswordField.
     */
    @Test
    fun `every masked field in the app is a PasswordField`() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "no sources found from ${File(".").absolutePath}")
        val offenders = sources
            .filter { it.name != "PasswordField.kt" && "PasswordVisualTransformation" in it.readText() }
            .map { it.path }
        assertTrue(offenders.isEmpty(), "mask these with PasswordField instead: $offenders")
    }
}
