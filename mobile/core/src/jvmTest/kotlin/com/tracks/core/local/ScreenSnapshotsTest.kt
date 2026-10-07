// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.TrainingLoadPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenSnapshotsTest {
    @Test
    fun a_dashboard_comes_back_as_it_was_saved() {
        val s = ScreenSnapshots.Dashboard(
            period = "Monthly",
            trainingLoad = listOf(TrainingLoadPoint("2026-10-01", 40.0, 30.0, 35.0, -5.0, 1.2)),
            extras = ScreenSnapshots.Extras(activeDays = 4, busiestSport = "running"),
        )
        assertEquals(s, ScreenSnapshots.decodeDashboard(ScreenSnapshots.encode(s)))
    }

    @Test
    fun a_snapshot_from_an_older_shape_is_ignored_rather_than_crashing_startup() {
        assertNull(ScreenSnapshots.decodeDashboard("""{"trainingLoad": 3}"""))
        assertNull(ScreenSnapshots.decodeHealth("not json"))
        assertNull(ScreenSnapshots.decodeHealth(null))
    }
}
