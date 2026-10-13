// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The height profile built on the phone, in the server's shape, so a track has
 * one the moment it exists rather than after a sync.
 */
class TrackProfileTest {

    private val climb = (0..400).map { listOf<Double?>(20.0, 10.0 + it * 0.0001, 500.0 + it) }

    @Test
    fun `a line that carries elevation needs no sampler`() = runTest {
        val profile = assertNotNull(TrackProfile.build(climb) { _, _ -> error("not asked") })
        assertEquals(400.0, profile["gain_m"] as Double, 0.5)
        assertEquals(500.0, profile["min_m"])
        assertEquals(900.0, profile["max_m"])
        assertTrue((profile["points"] as List<*>).size <= TrackProfile.SAMPLES)
    }

    @Test
    fun `a flat drawn line takes its heights from the sampler`() = runTest {
        val drawn = climb.map { listOf(it[0], it[1]) }
        var asked = 0
        val profile = assertNotNull(TrackProfile.build(drawn) { _, _ -> asked++; 1200.0 })
        assertTrue(asked > 0)
        assertEquals(1200.0, profile["max_m"])
    }

    @Test
    fun `no heights anywhere is no profile, not a flat one`() = runTest {
        val drawn = climb.map { listOf(it[0], it[1]) }
        assertNull(TrackProfile.build(drawn) { _, _ -> null })
    }
}
