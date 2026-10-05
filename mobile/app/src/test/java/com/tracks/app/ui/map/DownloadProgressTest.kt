// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The units on the progress bar.
 *
 * The server counts extraction in percent and [DownloadPhase.Extracting]
 * carries a fraction. Handed over undivided, every download past one percent
 * drew as a completely full bar — so a job with eight minutes left looked
 * finished, which is indistinguishable from the download having silently
 * stopped.
 */
class DownloadProgressTest {

    @Test
    fun `percent becomes a fraction`() {
        assertEquals(0f, percentToFraction(0.0), 1e-6f)
        assertEquals(0.5f, percentToFraction(50.0), 1e-6f)
        assertEquals(1f, percentToFraction(100.0), 1e-6f)
    }

    /** The bug, stated as a test: 97% is not a finished bar. */
    @Test
    fun `a job in progress does not read as complete`() {
        assertEquals(0.972f, percentToFraction(97.2), 1e-3f)
    }

    /** The builder's stages round, and can land a hair over. */
    @Test
    fun `an overshoot is clamped rather than drawn past the end`() {
        assertEquals(1f, percentToFraction(100.4), 1e-6f)
        assertEquals(0f, percentToFraction(-3.0), 1e-6f)
    }
}
