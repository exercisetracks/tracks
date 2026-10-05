// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two questions the watch asks when a workout is saved, read back in the
 * watch's own words. Both are stored scaled — feel 0–100 in quarters, effort
 * ×10 — so printing the raw number would show "75" and "60" for "Strong" and
 * "6/10".
 */
class HowItFeltTest {

    @Test
    fun `feel reads as the watch's five words`() {
        assertEquals("Very weak", feelLabel(0))
        assertEquals("Weak", feelLabel(25))
        assertEquals("Normal", feelLabel(50))
        assertEquals("Strong", feelLabel(75))
        assertEquals("Very strong", feelLabel(100))
    }

    @Test
    fun `effort is out of ten, with its band`() {
        assertEquals("6/10 · Moderate", effortLabel(60))
        assertEquals("7/10 · Hard", effortLabel(70))
        assertEquals("10/10 · Maximum", effortLabel(100))
        assertEquals("1/10 · Very light", effortLabel(10))
    }

    @Test
    fun `an unanswered prompt shows nothing`() {
        // Zero effort is the watch's "skipped", and an out-of-range feel is a
        // corrupt field; neither may surface as an answer the person never gave.
        assertNull(effortLabel(null))
        assertNull(effortLabel(0))
        assertNull(feelLabel(null))
        assertNull(feelLabel(255))
    }
}
