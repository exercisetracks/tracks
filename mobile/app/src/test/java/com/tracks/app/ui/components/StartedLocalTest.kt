// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * An activity's day is the one on the phone's clock, not the UTC day it is
 * stored under. A run at 18:04 in California was listed on the next day.
 */
class StartedLocalTest {

    private val la = ZoneId.of("America/Los_Angeles")

    @Test
    fun `an evening run stays on its own day`() {
        assertEquals("2026-09-30", startedLocal("2026-10-01 01:04:12+00:00", la)!!.toLocalDate().toString())
    }

    @Test
    fun `both stored shapes give the same moment`() {
        // Phone imports write a space, server rows a T; the header used to
        // reject the first and show no start time at all.
        assertEquals(
            startedLocal("2026-10-01T01:04:12+00:00", la),
            startedLocal("2026-10-01 01:04:12+00:00", la),
        )
        assertEquals(18, startedLocal("2026-10-01 01:04:12+00:00", la)!!.hour)
    }
}
