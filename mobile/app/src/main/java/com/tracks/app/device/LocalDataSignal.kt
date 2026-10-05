// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "The phone just changed its own copy of something."
 *
 * ## The bug this exists for
 *
 * Reading the watch's files on the phone is only half of working offline. The
 * other half is the screens noticing. Every page-level ViewModel here is held
 * for the life of the process — deliberately, because the app bar carries each
 * page's controls and re-firing nine requests every time somebody glances at
 * another tab would be absurd — and each of them loads once, in `init`. So a
 * watch sync run from Settings wrote a night's sleep and three thousand steps
 * into the mirror, and the Health page went on showing what it had loaded that
 * morning until the app was killed.
 *
 * With a server that was invisible: something else always came along and
 * refetched. Without one, nothing ever does, which is precisely the case the
 * offline work is for.
 *
 * ## Why a counter and not an event
 *
 * Because the question a screen asks is "is what I am showing still current",
 * not "what happened". A counter answers that with no buffering, no replay
 * semantics to get wrong, and no way for a missed emission to leave a page
 * stale forever: a collector that joins late reads the current value and knows
 * where it stands.
 *
 * ## Why one signal rather than one per kind
 *
 * A single watch sync writes activities *and* days, and the two screens that
 * care read cheaply from disk. Splitting this into "activities changed" and
 * "health changed" would buy each screen the right to ignore half the syncs,
 * in exchange for two things to keep in step. The refresh on the other side of
 * this is a local read, and it is allowed to be occasionally unnecessary.
 */
class LocalDataSignal {

    private val _revision = MutableStateFlow(0L)

    /** Changes whenever the phone writes something a screen reads. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun changed() {
        _revision.update { it + 1 }
    }
}
