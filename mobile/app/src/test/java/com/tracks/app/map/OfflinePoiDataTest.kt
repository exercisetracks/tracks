// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import com.tracks.core.api.OfflinePoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline search fallback's ranking, with no filesystem or server in the
 * way — see [OfflinePoiData.search] for why this is a free function.
 */
class OfflinePoiDataTest {

    private fun poi(id: Long, name: String) = OfflinePoi(id = id, name = name, lat = 40.0, lng = -150.0)

    @Test
    fun `an exact match wins over a prefix, which wins over a substring`() {
        val candidates = listOf(
            poi(1, "Glen Pass Winter Recreation Area"),
            poi(2, "Glen"),
            poi(3, "East Glen Trailhead"),
        )
        assertEquals(listOf(2L, 1L, 3L), rankOfflinePoi(candidates, "glen").map { it.id })
    }

    @Test
    fun `matching is case-insensitive`() {
        val candidates = listOf(poi(1, "Crescent Lake"))
        assertEquals(listOf(1L), rankOfflinePoi(candidates, "CRESCENT").map { it.id })
    }

    @Test
    fun `among equal-strength matches the shorter name wins`() {
        val candidates = listOf(
            poi(1, "Glen Pass Winter Recreation Area"),
            poi(2, "Glen Mountain"),
        )
        // Both are prefix matches for "glen" — neither is exact — so length
        // breaks the tie, the same way it separates "Glen" from the rest.
        assertEquals(listOf(2L, 1L), rankOfflinePoi(candidates, "glen").map { it.id })
    }

    @Test
    fun `a name that does not contain the query at all is excluded`() {
        val candidates = listOf(poi(1, "Crescent Lake"))
        assertTrue(rankOfflinePoi(candidates, "seaside").isEmpty())
    }

    @Test
    fun `a blank query returns nothing rather than everything`() {
        val candidates = listOf(poi(1, "Crescent Lake"))
        assertTrue(rankOfflinePoi(candidates, "   ").isEmpty())
    }

    @Test
    fun `results are capped at the requested limit`() {
        val candidates = (1..20).map { poi(it.toLong(), "Trail $it") }
        assertEquals(3, rankOfflinePoi(candidates, "trail", limit = 3).size)
    }

    @Test
    fun `leading and trailing whitespace in the query is ignored`() {
        val candidates = listOf(poi(1, "Crescent Lake"))
        assertEquals(listOf(1L), rankOfflinePoi(candidates, "  crescent  ").map { it.id })
    }
}
