// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import com.tracks.core.api.FlowStretch
import com.tracks.core.api.Stretch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a stretch's place in a flow reads.
 *
 * A flow carries only its *deviations* — a stretch it says nothing about is
 * held for whatever the library prescribes. So the distinction that matters
 * here is between a figure the flow chose and one it inherited: showing them
 * identically would make an inherited hold look deliberate, and editing the
 * library later would then silently change a flow that appeared explicit.
 */
class FlowStretchLineTest {

    private val library = Stretch(
        name = "Low Lunge",
        durationPerSideSec = 30,
        eachSide = true,
        sets = 2,
    )

    @Test
    fun `a hold the flow chose is shown plainly`() {
        val line = flowStretchLine(FlowStretch("Low Lunge", durationSeconds = 45), library)
        assertTrue(line.startsWith("45s"), line)
        assertFalse(line.contains("from the library"), line)
    }

    @Test
    fun `a hold the flow left alone says where it came from`() {
        val line = flowStretchLine(FlowStretch("Low Lunge"), library)
        assertTrue(line.startsWith("30s"), line)
        assertTrue(line.contains("from the library"), line)
    }

    @Test
    fun `a one-sided stretch says so, because it doubles the session`() {
        val line = flowStretchLine(FlowStretch("Low Lunge"), library)
        assertTrue(line.contains("each side"), line)
    }

    @Test
    fun `a stretch the library does not know reads as written rather than as zero`() {
        val line = flowStretchLine(FlowStretch("Something new"), null)
        assertEquals("as written", line)
    }

    @Test
    fun `sets and rest appear only when the flow set them`() {
        val bare = flowStretchLine(FlowStretch("Low Lunge", durationSeconds = 40), library)
        assertFalse(bare.contains("×"), bare)
        assertFalse(bare.contains("rest"), bare)

        val full = flowStretchLine(
            FlowStretch("Low Lunge", durationSeconds = 40, sets = 3, restSeconds = 15),
            library,
        )
        assertTrue(full.contains("× 3"), full)
        assertTrue(full.contains("15s rest"), full)
    }

    @Test
    fun `a single set is not worth saying`() {
        val line = flowStretchLine(
            FlowStretch("Low Lunge", durationSeconds = 40, sets = 1), library,
        )
        assertFalse(line.contains("×"), line)
    }
}
