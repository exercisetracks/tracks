// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Drag down to sync the watch.
 *
 * ## Why this gesture syncs the *watch* and not the server
 *
 * Because of what these two pages are. The dashboard and the health page are
 * about what your body did, and every figure on either originates in a file the
 * watch is holding — steps, sleep, resting heart rate, the ride you finished an
 * hour ago. The server is downstream of all of it, and on the trips this app
 * exists for it is the half that is not there. Reaching for the nearest source
 * of new data means reaching for the wrist.
 *
 * Nothing is lost by it either: the sync uploads what it pulled when it can,
 * and the pages redraw themselves from the mirror as soon as anything lands —
 * see [com.tracks.app.device.LocalDataSignal].
 *
 * ## Why the indicator is owned here
 *
 * The spinner has to stop when the sync stops, which means somebody has to
 * await it. Driving it from the view model's `busy` flag instead would spin
 * this page for any action anywhere in the app, including ones the user
 * triggered on another screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToSync(
    onSync: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = {
            // Guarded, because the gesture can be repeated while the first one
            // is still going and a BLE conversation does not take two callers —
            // see WatchSyncRunner's mutex, which would decline the second and
            // leave this indicator spinning for a sync that never ran.
            if (!syncing) {
                syncing = true
                scope.launch {
                    try {
                        onSync()
                    } finally {
                        syncing = false
                    }
                }
            }
        },
        state = state,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = syncing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        content = { content() },
    )
}
