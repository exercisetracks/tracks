// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.tour

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the tutorial is: which tour is showing, which step, and what has been
 * seen. The web's TourContext as a value, so every transition is a pure
 * function a test can call ([TourStateTest]).
 *
 * [seen] is the account's whole `tour_seen` map — the web's keys as well as
 * this phone's ([Tours.seenKey]) — because it is written back whole, and a
 * write that dropped the web's keys would replay the browser's tutorial.
 */
data class TourState(
    /** False until the settings row has been read; nothing starts before. */
    val hydrated: Boolean = false,
    val enabled: Boolean = true,
    val seen: Map<String, Any?> = emptyMap(),
    val active: String? = null,
    val step: Int = 0,
) {
    val steps: List<TourStep> get() = active?.let { Tours.all[it] }.orEmpty()
    val current: TourStep? get() = steps.getOrNull(step)

    fun hasSeen(id: String) = seen[Tours.seenKey(id)] == true

    /** Whether opening the page for [id] should start its tour. */
    fun shouldStart(id: String?): Boolean =
        id != null && hydrated && enabled && active == null && !hasSeen(id) && !Tours.all[id].isNullOrEmpty()

    fun start(id: String) = if (Tours.all[id].isNullOrEmpty()) this else copy(active = id, step = 0)

    /** The next step, or — past the last — the tour finished. */
    fun next() = if (step >= steps.size - 1) complete() else copy(step = step + 1)

    fun prev() = copy(step = (step - 1).coerceAtLeast(0))

    /** Done or Skip: remembered, so it never fires on its own again. */
    fun complete(): TourState {
        val id = active ?: return this
        return copy(active = null, step = 0, seen = seen + (Tours.seenKey(id) to true))
    }

    /**
     * Dropped without being remembered — the user left the page mid-tour — so
     * it shows again next time they open it. The web does the same.
     */
    fun abort() = copy(active = null, step = 0)

    fun withEnabled(value: Boolean) = if (value) copy(enabled = true) else copy(enabled = false, active = null, step = 0)

    /**
     * Forget this phone's tours and switch tips back on. Only the phone's: a
     * replay asked for here is a replay of what this phone shows, and the
     * browser's tutorial is the browser's to restart.
     */
    fun restart() = copy(
        enabled = true,
        active = null,
        step = 0,
        seen = seen.filterKeys { !it.startsWith(Tours.seenKey("")) },
    )
}

/**
 * The tutorial's state, kept in the account's settings row.
 *
 * `tour_seen` and `tour_enabled` are synced fields the web already writes, so
 * switching tips off in either place switches them off in both, and a phone
 * restored from a backup does not replay tours already dismissed.
 *
 * Held by the Activity (see TracksNavHost) rather than a page, because a tour
 * outlives the page that started it only long enough to be aborted, and a
 * rotation mid-tour should not lose the step.
 */
class TourViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(TourState())
    val state: StateFlow<TourState> = _state.asStateFlow()

    /**
     * Keys finished here whose write has not landed yet. A re-read in that
     * window would otherwise hand back the map without them, and the page's
     * tour — just dismissed — would start again under the user's thumb.
     */
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        // Re-read on every change the phone sees, which includes a server sync
        // bringing the web's choice down: tips switched off in the browser
        // should stop here without a restart.
        viewModelScope.launch {
            container.localData.revision.collect { hydrate() }
        }
    }

    private suspend fun hydrate() {
        val values = runCatching { container.sources.settingValues() }.getOrNull() ?: emptyMap()
        _state.update {
            it.copy(
                hydrated = true,
                enabled = values["tour_enabled"] != false,
                seen = seenOf(values) + pending.associateWith { true },
            )
        }
    }

    fun start(id: String) = _state.update { it.start(id) }
    fun prev() = _state.update { it.prev() }
    fun abort() = _state.update { it.abort() }

    fun next() {
        val finishing = _state.value.active?.takeIf { _state.value.step >= _state.value.steps.size - 1 }
        _state.update { it.next() }
        finishing?.let(::rememberSeen)
    }

    fun complete() {
        val id = _state.value.active ?: return
        _state.update { it.complete() }
        rememberSeen(id)
    }

    fun setEnabled(value: Boolean) {
        _state.update { it.withEnabled(value) }
        viewModelScope.launch { runCatching { container.sources.writeSetting("tour_enabled", value) } }
    }

    fun restart() {
        pending.clear()
        _state.update { it.restart() }
        viewModelScope.launch {
            runCatching {
                container.sources.writeSetting("tour_enabled", true)
                container.sources.writeSetting("tour_seen", TourState(seen = latestSeen()).restart().seen)
            }
        }
    }

    /**
     * Add one key to the map as it stands now, not as it was read at start.
     * The map is one field, so the last writer wins it whole; merging into the
     * latest copy is what stops this write undoing a key a sync just brought.
     */
    private fun rememberSeen(id: String) {
        val key = Tours.seenKey(id)
        pending += key
        viewModelScope.launch {
            try {
                runCatching { container.sources.writeSetting("tour_seen", latestSeen() + (key to true)) }
            } finally {
                pending -= key
            }
        }
    }

    private suspend fun latestSeen() = seenOf(container.sources.settingValues())

    @Suppress("UNCHECKED_CAST")
    private fun seenOf(values: Map<String, Any?>) = (values["tour_seen"] as? Map<String, Any?>) ?: emptyMap()
}
