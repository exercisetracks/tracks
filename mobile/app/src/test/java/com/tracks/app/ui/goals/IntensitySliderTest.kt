// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class IntensitySliderTest {

    /** The web moves in 0.05 steps; a value the phone writes must be one the web can show. */
    @Test
    fun a_drag_snaps_to_the_webs_twentieths() {
        assertEquals(1.1f, snap(1.1234f), 1e-6f)
        assertEquals(0.5f, snap(0.49f), 1e-6f)
        assertEquals(1.5f, snap(1.52f), 1e-6f)
    }

    /** The named stops take the web's exact colours, so both apps paint the same label. */
    @Test
    fun the_named_stops_are_the_webs_colours() {
        assertEquals(Color(0xFF60A5FA), intensityColor(0.5))
        assertEquals(Color(0xFF10B981), intensityColor(1.0))
        assertEquals(Color(0xFF60A5FA), intensityColor(0.2))   // clamped, as on the web
    }
}
