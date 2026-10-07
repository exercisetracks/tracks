// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import com.tracks.core.replica.SyncProgress
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncLabelTest {

    @Test
    fun `file progress is a percentage, not a count`() {
        val p = SyncProgress(SyncProgress.Step.Files, 412, 2994)
        assertEquals("Syncing files", syncLabel(p))
        assertEquals(14, kotlin.math.round(p.fraction!! * 100).toInt())
    }

    /** A pull cannot know how much is left; a "1,500 of 0" would be a lie. */
    @Test
    fun `a pull with no total counts up without one`() {
        assertEquals("Receiving changes", syncLabel(SyncProgress(SyncProgress.Step.Receiving, 1500, null)))
        assertEquals(null, SyncProgress(SyncProgress.Step.Receiving, 1500, null).fraction)
    }
}
