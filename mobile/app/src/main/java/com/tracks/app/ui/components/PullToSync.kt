// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
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
 * It wraps every page but the map (whose drag pans), and it began on the two
 * that explain it best: the dashboard and the health page are about what your
 * body did, and every figure on either originates in a file the watch is
 * holding — steps, sleep, resting heart rate, the ride you finished an
 * hour ago. The server is downstream of all of it, and on the trips this app
 * exists for it is the half that is not there. Reaching for the nearest source
 * of new data means reaching for the wrist.
 *
 * Nothing is lost by it either: the sync uploads what it pulled when it can,
 * and the pages redraw themselves from the mirror as soon as anything lands —
 * see [com.tracks.app.device.LocalDataSignal].
 *
 * ## Why the indicator lets go at once
 *
 * The pull is answered by the status pill at the top ([com.tracks.app.ui.status.StatusHost]),
 * which shows the sync for as long as it runs. A spinner here as well would be
 * two indicators for one operation. The `syncing` flag is still owned here, so
 * a second pull during a running sync is ignored rather than queued.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToSync(
    onSync: suspend () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False with neither a watch nor a server, and on the map, whose drag is
     * panning. Applied as a modifier rather than by leaving the box out, so
     * switching it does not rebuild the pages inside — this wraps all of them.
     */
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()

    Box(
        modifier.fillMaxSize().pullToRefresh(
            // The spinner lets go as soon as the pull is answered: the status
            // pill at the top carries the sync from there, and two indicators
            // for one sync is what the status guide exists to stop.
            isRefreshing = false,
            state = state,
            enabled = enabled,
            onRefresh = {
                // Guarded, because the gesture can be repeated while the first
                // one is still going and a BLE conversation does not take two
                // callers — see WatchSyncRunner's mutex.
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
        ),
    ) {
        content()
        if (enabled) {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = false,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
