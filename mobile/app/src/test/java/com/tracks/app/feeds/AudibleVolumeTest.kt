// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How loud a lost phone is allowed to make itself.
 *
 * The one judgement call in find-my-phone, and it fails in both directions. Too
 * timid and the phone rings inaudibly under a cushion, which is the whole
 * feature not working. Too aggressive and Tracks is an app that turns your
 * volume to maximum — on a phone that is about to be picked up and held next to
 * somebody's ear.
 */
class AudibleVolumeTest {

    @Test
    fun `a volume nobody could hear is raised`() {
        // A phone that is lost is usually a phone that was silenced.
        assertEquals(9, audibleVolume(current = 1, max = 15))
        assertEquals(9, audibleVolume(current = 0, max = 15))
    }

    @Test
    fun `a volume that is already loud is left exactly as it was`() {
        assertNull(audibleVolume(current = 12, max = 15))
        assertNull(audibleVolume(current = 15, max = 15))
    }

    @Test
    fun `the threshold itself counts as loud enough`() {
        // Nine of fifteen is the floor; being at it is not a reason to touch
        // somebody's settings.
        assertNull(audibleVolume(current = 9, max = 15))
    }

    @Test
    fun `it raises into earshot without going to the top`() {
        for (max in 3..30) {
            val raised = audibleVolume(current = 0, max = max)
                ?: error("silence should always be raised (max $max)")
            assertTrue("raised to $raised of $max", raised < max)
            assertTrue("raised to $raised, which is still silence", raised >= 1)
        }
    }

    @Test
    fun `a phone with no volume scale is left alone rather than divided by zero`() {
        assertNull(audibleVolume(current = 0, max = 0))
        assertNull(audibleVolume(current = 0, max = -1))
    }
}
