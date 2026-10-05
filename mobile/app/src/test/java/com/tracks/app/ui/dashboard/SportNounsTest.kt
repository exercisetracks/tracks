// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The words under the sport donut's count. */
class SportNounsTest {

    @Test
    fun `one of a sport is singular and the noun for one outing`() {
        // It read "1 Running": the sport's name, not what one of it is called.
        assertEquals("run", activityNoun("running", 1))
        assertEquals("runs", activityNoun("running", 2))
        assertEquals("ride", activityNoun("cycling", 1))
        assertEquals("swims", activityNoun("swimming", 4))
    }

    @Test
    fun `the total is an activity or activities and an unknown sport is a session`() {
        assertEquals("activity", activityNoun(null, 1))
        assertEquals("activities", activityNoun(null, 3))
        assertEquals("session", activityNoun("strength_training", 1))
        assertEquals("sessions", activityNoun("paddling", 2))
    }
}
