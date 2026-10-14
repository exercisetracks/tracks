// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionRowKeyTest {

    /**
     * A phone area saved from server area 7 and server area 7 itself are both
     * listed; keyed by the bare server id, the offline maps sheet crashed with
     * `Key "7" was already used` as soon as it opened.
     */
    @Test
    fun a_phone_area_and_the_server_area_it_came_from_have_different_keys() {
        val rows = listOf(
            RegionRow(name = "Coeur d'Alene", phase = DownloadPhase.Ready(1), serverId = 7, areaId = "a1"),
            RegionRow(name = "Idaho", phase = DownloadPhase.ServerOnly, serverId = 7),
            RegionRow(name = "Spokane", phase = DownloadPhase.Ready(1), serverId = 7, areaId = "a2"),
            RegionRow(name = "Old", phase = DownloadPhase.Ready(1), storedId = 7),
        )
        assertEquals(rows.size, rows.map(::rowKey).toSet().size)
    }
}
