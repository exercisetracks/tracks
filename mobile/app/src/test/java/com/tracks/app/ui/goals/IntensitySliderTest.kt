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

    /** The race slider writes hundredths inside its ends: a value the web and the server both accept. */
    @Test
    fun a_race_intensity_drag_snaps_to_hundredths_inside_the_ends() {
        assertEquals(1.03f, snapRace(1.0312f), 1e-6f)
        assertEquals(0.85f, snapRace(0.85f), 1e-6f)
        assertEquals(0.8f, snapRace(0.75f), 1e-6f)
        assertEquals(1.2f, snapRace(1.3f), 1e-6f)
    }

    /** The web's raceIntensityLabel word for word, and its colours from the plan slider's track. */
    @Test
    fun a_race_intensity_reads_and_is_coloured_as_on_the_web() {
        assertEquals("Predicted", raceIntensityLabel(1.0))
        assertEquals("Predicted (+4%)", raceIntensityLabel(1.04))
        assertEquals("Hard (+5%)", raceIntensityLabel(1.05))
        assertEquals("Steady (−5%)", raceIntensityLabel(0.95))
        assertEquals("Steady (−10%)", raceIntensityLabel(0.9))
        assertEquals("All out (+15%)", raceIntensityLabel(1.15))
        assertEquals("Easy (−20%)", raceIntensityLabel(0.8))
        assertEquals(intensityColor(1.0), raceIntensityColor(1.0))
        assertEquals(intensityColor(0.5), raceIntensityColor(0.8))
        assertEquals(intensityColor(1.5), raceIntensityColor(1.2))
    }
}
