// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import com.tracks.core.replica.SyncProgress

/**
 * What the status pill says for each step of a server sync: words only. How
 * far along it is, is the percentage beside it (from [SyncProgress.fraction]) —
 * a count of files meant nothing to anyone, and "412 of 2,994" read as a
 * chore list.
 */
internal fun syncLabel(p: SyncProgress): String = when (p.step) {
    SyncProgress.Step.Sending -> "Sending changes"
    SyncProgress.Step.Receiving -> "Receiving changes"
    SyncProgress.Step.Files -> "Syncing files"
    SyncProgress.Step.Uploading -> "Uploading files"
}
