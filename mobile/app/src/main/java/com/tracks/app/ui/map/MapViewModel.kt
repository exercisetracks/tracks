// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.api.NotAuthenticatedException
import com.tracks.core.api.VaultLockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

data class MapUiState(
    val loading: Boolean = true,
    val styleJson: String? = null,
    /** Every GPS point the user has, as [lng, lat] pairs, for the heatmap. */
    val heatmap: List<List<Double>> = emptyList(),
    val error: String? = null,
    /**
     * Set when the basemap is up but the user's own tracks are not.
     *
     * Its own field rather than [error], because the two are different
     * screens: [error] means there is no map at all, this means there is a map
     * with nothing of the user's on it — which is exactly what a lapsed vault
     * looks like, since the heatmap is built from encrypted lat/lng columns.
     */
    val overlayNotice: String? = null,
    /**
     * No server and no cached style: the map is drawn on a plain canvas (see
     * [blankStyleJson]) with the person's own tracks and places on it, and
     * this one line says why the ground is empty. Public tile sources were
     * considered and deliberately deferred — they would send the phone's
     * location to a third party, which is a choice for the user to make
     * knowingly, not a silent fallback.
     */
    val blankBasemap: Boolean = false,
)

const val NO_SERVER_BASEMAP_NOTICE =
    "Basemaps come from a Tracks server — link one in Settings. Your tracks and places still draw here."

/**
 * The plainest style MapLibre will render: one background layer and nothing
 * else. Sources the screen adds (tracks, courses, waypoints) draw over it as
 * they would over a real basemap.
 */
fun blankStyleJson(backgroundHex: String): String =
    """{"version":8,"name":"blank","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"$backgroundHex"}}]}"""

/**
 * Fetches the style, then the heatmap.
 *
 * The style is cached to disk on first fetch and read from there afterwards.
 * That is not a speed optimisation — it is what makes the Map tab open at all
 * with no signal. A 124-layer style document is the one thing the map cannot
 * render without, and re-fetching it on every visit would mean the map works
 * in town and shows an error in a valley.
 *
 * Tiles are a separate problem and not solved here: with no network the style
 * loads, the layers exist, and the basemap comes up empty until a region has
 * been downloaded. That is the PMTiles work, and it is deliberately not
 * pretended-at — an empty map that says why beats a spinner that never ends.
 */
class MapViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            // Only the first fetch blanks the screen. This is also called when
            // the server's coverage changes — a region landing adds whole
            // sources to the style — and replacing a perfectly good map with a
            // spinner because somebody finished a download elsewhere is a worse
            // answer than swapping the document underneath it.
            _state.update { it.copy(loading = it.styleJson == null, error = null) }
            val cache = container.mapStyleCache()

            val style = runCatching { container.client().mapStyleJson() }
                .onSuccess { fresh -> withContext(Dispatchers.IO) { cache.write(fresh) } }
                .recoverCatching {
                    // Offline, or the server is unreachable. The cached copy is
                    // the whole reason the tab is usable in the field.
                    withContext(Dispatchers.IO) { cache.read() }
                        ?: throw it
                }
                .getOrElse { e ->
                    // A phone that has never had a server has no style to
                    // fall back on, and that is a normal state, not an error.
                    if (!container.isLinked()) {
                        _state.update { it.copy(loading = false, blankBasemap = true) }
                        loadHeatmap()
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Could not load the map style: ${e.message ?: "no connection"}",
                        )
                    }
                    return@launch
                }

            _state.update { it.copy(loading = false, styleJson = style, blankBasemap = false) }
            loadHeatmap()
        }
    }

    /** Where you have been, from the tracks in this phone's own files — no request, and nothing to go stale. */
    private suspend fun loadHeatmap() {
        val heatmap = withContext(Dispatchers.Default) {
            runCatching { container.library.heatmap() }.getOrDefault(emptyList())
        }
        _state.update { it.copy(heatmap = heatmap, overlayNotice = null) }
    }

    /**
     * The heatmap is built from encrypted lat/lng columns, so it is the first
     * thing on this screen to notice a lapsed crypto session — and a 401 here
     * means "unlock", not "sign in", which is a distinction the user can act
     * on and a bare error message is not.
     */
    private fun describeOverlayFailure(e: Throwable): String = when (e) {
        is VaultLockedException ->
            "Your tracks are locked. Enter your password on the Settings tab to show them."
        is NotAuthenticatedException -> "Sign in on the Settings tab to see your tracks."
        else -> "Could not load your tracks: ${e.message ?: "no connection"}"
    }

    private companion object {
    }
}
