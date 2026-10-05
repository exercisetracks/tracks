// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import com.tracks.core.replica.SyncProgress
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncLabelTest {

    @Test
    fun `file progress reads as done of total`() {
        Locale.setDefault(Locale.US)
        assertEquals("Syncing 412 of 2,994 files", syncLabel(SyncProgress(SyncProgress.Step.Files, 412, 2994)))
    }

    /** A pull cannot know how much is left; a "1,500 of 0" would be a lie. */
    @Test
    fun `a pull with no total counts up without one`() {
        Locale.setDefault(Locale.US)
        assertEquals("Receiving 1,500 changes", syncLabel(SyncProgress(SyncProgress.Step.Receiving, 1500, null)))
        assertEquals(null, SyncProgress(SyncProgress.Step.Receiving, 1500, null).fraction)
    }
}
