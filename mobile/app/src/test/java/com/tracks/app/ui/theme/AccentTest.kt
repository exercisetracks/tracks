// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The accent arrives over the wire, so it has to survive anything.
 *
 * The value is written by whichever client the user last used, including one
 * offering a custom hex the phone has no ramp for, and including an older or
 * newer web app with presets this build has never heard of. A colour is not
 * worth a crash on start, so every unknown answer resolves to a colour.
 */
class AccentTest {

    @Test
    fun `the web app's preset keys all resolve`() {
        // These are the seven `AppearanceSection.jsx` offers. If the web app
        // adds one and this list does not, that colour silently becomes the
        // default on the phone — which is the failure this test describes.
        listOf("emerald", "blue", "violet", "rose", "amber", "sky", "teal").forEach { key ->
            assertEquals(key, Accent.of(key).key)
        }
    }

    @Test
    fun `an unknown accent falls back rather than failing`() {
        // A custom "#ff00aa" from the browser's colour picker, or a preset from
        // a newer web build.
        assertEquals(Accent.default, Accent.of("#ff00aa"))
        assertEquals(Accent.default, Accent.of("chartreuse"))
        assertEquals(Accent.default, Accent.of(null))
    }

    @Test
    fun `case does not decide the colour`() {
        assertEquals(Accent.Sky, Accent.of("SKY"))
    }

    @Test
    fun `each scheme gets its own shade`() {
        // One hue cannot serve both: a 500 that reads on white is muddy on true
        // black. Dark takes the lighter end and light the darker.
        Accent.entries.forEach { accent ->
            assertNotEquals(
                "${accent.key} uses one shade for both schemes",
                accent.primary(dark = true),
                accent.primary(dark = false),
            )
        }
    }

    @Test
    fun `the theme mode is device-local and defaults to the system's`() {
        assertEquals(ThemeMode.System, ThemeMode.of(null))
        assertEquals(ThemeMode.Dark, ThemeMode.of("dark"))
        assertEquals(ThemeMode.System, ThemeMode.of("solar"))
    }

    /**
     * No Material slot is left to its baseline lavender: a selected segmented
     * button or chip reads secondaryContainer, and before this it showed a
     * colour from neither the accent nor the web.
     */
    @Test
    fun `selected-state containers follow the accent in both modes`() {
        for (dark in listOf(false, true)) {
            val scheme = schemeFor(dark, Accent.default)
            assertEquals(Accent.default.container(dark), scheme.secondaryContainer)
            assertEquals(Accent.default.container(dark), scheme.tertiaryContainer)
            assertEquals(Accent.default.primary(dark), scheme.tertiary)
        }
    }
}
