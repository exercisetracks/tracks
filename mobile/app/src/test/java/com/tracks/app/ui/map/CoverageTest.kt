// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What colour an area's outline is, and what that colour promises.
 *
 * Blue means the phone holds it and the map works with the radio off. Orange
 * means the server holds it: full detail with a signal, the coarse overview
 * without one. Getting this mapping wrong paints a promise of offline coverage
 * over ground that has none — a mistake nobody discovers in town, and everybody
 * discovers in a canyon.
 */
class CoverageTest {

    @Test
    fun `an area stored on the phone is blue`() {
        assertEquals(
            Coverage.Phone,
            coverageOf(DownloadPhase.Ready(120_000_000), serverReady = true),
        )
    }

    @Test
    fun `an area only the server has is orange`() {
        assertEquals(
            Coverage.Server,
            coverageOf(DownloadPhase.ServerOnly, serverReady = true),
        )
    }

    /**
     * The case the colours exist to separate. Half the tiles are here, so the
     * phone cannot promise this ground offline — but the server can serve every
     * bit of it right now, and orange is the honest claim.
     */
    @Test
    fun `a half-finished download over a built region is orange, not blue`() {
        assertEquals(
            Coverage.Server,
            coverageOf(DownloadPhase.Storing(0.5f, 40_000_000), serverReady = true),
        )
        assertEquals(
            Coverage.Server,
            coverageOf(DownloadPhase.Paused(0.5f, 40_000_000), serverReady = true),
        )
    }

    @Test
    fun `nothing is claimed while the server is still cutting it`() {
        assertEquals(
            Coverage.Building,
            coverageOf(DownloadPhase.Extracting(0.4f, "Merging trails…"), serverReady = false),
        )
        // A region the server has since forgotten, with a partial phone copy:
        // neither half can answer for it.
        assertEquals(
            Coverage.Building,
            coverageOf(DownloadPhase.Paused(0.5f, 1), serverReady = false),
        )
    }

    @Test
    fun `a failed area promises nothing`() {
        assertEquals(
            Coverage.Building,
            coverageOf(DownloadPhase.Failed("The server could not build this area"), serverReady = false),
        )
    }
}
