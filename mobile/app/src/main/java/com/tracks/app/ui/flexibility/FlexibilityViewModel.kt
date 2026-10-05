// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.api.FlexibilityFlow
import com.tracks.core.api.FlexibilityFlowIn
import com.tracks.core.api.FlowStretch
import com.tracks.core.api.Stretch
import com.tracks.core.api.WorkoutSessionIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import com.tracks.app.ui.strength.accepts

/**
 * One hold the runner is going to walk the user through.
 *
 * A flow's stretches are flattened into holds before the session starts, rather
 * than being worked out as it goes. A two-set, both-sides stretch is four holds,
 * and knowing that up front is what lets the runner say "3 of 12" and show what
 * is coming next — neither of which a stepper computing as it goes can do.
 */
data class Hold(
    val stretch: Stretch?,
    val name: String,
    val seconds: Int,
    val restSeconds: Int,
    /** "Left" / "Right" for a one-sided stretch, null when it is symmetrical. */
    val side: String? = null,
    /** Which set of this stretch, 1-based; null when there is only one. */
    val set: Int? = null,
    val note: String? = null,
)

/** What the runner is doing right now. */
enum class HoldPhase { Holding, Resting, Done }

data class FlowSession(
    val flow: FlexibilityFlow,
    val holds: List<Hold> = emptyList(),
    val index: Int = 0,
    val phase: HoldPhase = HoldPhase.Holding,
    /** Whole seconds left in this phase, derived from [phaseEndsAt] — see [FlowEngine]. */
    val remaining: Int = 0,
    /** Paused counts as a phase the timer is not running in, not a fourth phase. */
    val running: Boolean = true,
    /** When the current phase ends, wall clock; null while paused or done. */
    val phaseEndsAt: Long? = null,
    /** What was left of the phase when it was paused. */
    val pausedLeftMs: Long? = null,
    val startedAtMillis: Long = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val hold: Hold? get() = holds.getOrNull(index)
    val next: Hold? get() = holds.getOrNull(index + 1)
}

data class FlexibilityUiState(
    val loading: Boolean = true,
    val flows: List<FlexibilityFlow> = emptyList(),
    val stretches: List<Stretch> = emptyList(),
    val search: String = "",
    val muscles: Set<String> = emptySet(),
    val filter: com.tracks.app.ui.strength.LibraryFilter = com.tracks.app.ui.strength.LibraryFilter.All,
    /** Stretches ticked in the library, waiting to become a flow or a session. */
    val picked: List<String> = emptyList(),
    val session: FlowSession? = null,
    /** Sessions finished here that the server has not taken yet. */
    val pending: Int = 0,
    val error: String? = null,
) {
    /** The library, narrowed the same way the strength library is. */
    val visibleStretches: List<Stretch>
        get() = stretches.filter { s ->
            (search.isBlank() || s.name.contains(search, ignoreCase = true)) &&
                (muscles.isEmpty() || muscles.any {
                    it in s.primaryMuscles || it in s.secondaryMuscles
                }) && filter.accepts(s.preference, s.customId != null)
        }
}

/**
 * Mobility: the user's saved flows, the stretch library behind them, and a
 * runner that counts the holds out.
 *
 * A flow names its stretches; the library says how each one is done. Both are
 * fetched here and joined on the phone, because a flow on its own has no cues,
 * no caution text and no idea whether a stretch is one-sided — and that last one
 * decides how many holds the session actually has.
 *
 * ## Offline
 *
 * Both reads come from the local mirror first — a flow is a list of stretches
 * the user wrote down once and the library changes twice a year, so there is no
 * version of this screen that should ever be empty for want of signal. The
 * finished session goes through the outbox.
 *
 * ## What finishing a session records
 *
 * A row in the workout-session log, naming the flow. Not an activity: there is
 * no non-FIT path into the activity table — `POST /sync/ingest` takes sealed FIT
 * files and nothing else — and having the phone synthesise a FIT for a ten-minute
 * mobility routine would be a lot of machinery for a record with no distance, no
 * heart rate and no track. The web app does not create one either; it ticks the
 * planned workout off and stops. This does the same and adds the session row, so
 * "I did my mobility work on Tuesday" survives somewhere both clients can see.
 */
class FlexibilityViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(FlexibilityUiState())
    val state: StateFlow<FlexibilityUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { container.localData.revision.drop(1).collect { load() } }
        // The playing flow outlives this ViewModel (it plays with the screen
        // off); the screen only mirrors it.
        viewModelScope.launch { FlowPlayback.state.collect { s -> _state.update { it.copy(session = s) } } }
    }

    /** The bundled stretch library and this phone's flows — nothing to fetch. */
    fun load() {
        viewModelScope.launch {
            val t = container.training
            apply(
                loadedFlows = runCatching { t.flows() }.getOrNull(),
                library = runCatching { t.stretches() }.getOrNull(),
                stillLoading = false,
            )
            _state.update { it.copy(pending = container.replica.pendingCount().toInt()) }
        }
    }

    /**
     * Fold what arrived into state, keeping whatever did not.
     *
     * Null is "did not arrive", not "is empty" — the same distinction the
     * strength screen makes, and for the same reason: this runs twice, once
     * with the cached copy and once with the fresh one on top.
     */
    private fun apply(
        loadedFlows: List<FlexibilityFlow>?,
        library: List<Stretch>?,
        stillLoading: Boolean,
    ) {
        _state.update {
            val flows = loadedFlows ?: it.flows
            val stretches = library ?: it.stretches
            it.copy(
                loading = stillLoading && flows.isEmpty() && stretches.isEmpty(),
                flows = flows,
                stretches = stretches,
                error = if (!stillLoading && flows.isEmpty() && stretches.isEmpty()) {
                    "The stretch library could not be read."
                } else {
                    null
                },
            )
        }
    }

    /**
     * Save a flow, new or edited — locally, and synced by field: each stretch
     * is a row of its own, so a hold time changed here and a reorder made on
     * the web both survive.
     */
    fun saveFlow(id: Int?, flow: FlexibilityFlowIn) {
        viewModelScope.launch {
            runCatching { container.training.saveFlow(id, flow) }.onFailure {
                _state.update { it.copy(error = "Could not save that flow.") }
                return@launch
            }
            load()
        }
    }

    fun deleteFlow(id: Int) {
        viewModelScope.launch {
            runCatching { container.sources.delete("flow", id) }
            _state.update { state -> state.copy(flows = state.flows.filterNot { it.id == id }) }
        }
    }

    fun togglePicked(name: String) = _state.update {
        it.copy(picked = if (name in it.picked) it.picked - name else it.picked + name)
    }

    fun clearPicked() = _state.update { it.copy(picked = emptyList()) }

    /**
     * Run what is ticked, without saving it.
     *
     * A flow built out of a muscle filter is usually a one-off — the point of
     * filtering to hips and running six stretches is the six stretches, not a
     * routine to keep. So this constructs a flow that never reaches the server:
     * [startFlow] reads only its name and its stretches, and a finished session
     * is logged by name, so nothing here needs an id.
     */
    fun startPickedFlow() {
        val picked = _state.value.picked
        if (picked.isEmpty()) return
        startFlow(
            FlexibilityFlow(
                id = 0,
                name = "Quick flow",
                stretches = picked.mapIndexed { index, name ->
                    FlowStretch(exerciseName = name, orderIndex = index)
                },
            ),
        )
    }

    fun setSearch(value: String) = _state.update { it.copy(search = value) }

    fun setFilter(value: com.tracks.app.ui.strength.LibraryFilter) = _state.update { it.copy(filter = value) }

    fun setPreference(name: String, preference: String?) {
        _state.update { s -> s.copy(stretches = s.stretches.map { if (it.name == name) it.copy(preference = preference) else it }) }
        viewModelScope.launch { runCatching { container.training.setPreference(true, name, preference) } }
    }

    fun saveCustom(draft: com.tracks.app.ui.strength.CustomDraft) {
        viewModelScope.launch { runCatching { container.training.saveCustom(true, draft.id, draft.values(true)) }; load() }
    }

    fun deleteCustom(id: Int) {
        viewModelScope.launch { runCatching { container.training.deleteCustom(true, id) }; load() }
    }

    fun setMuscles(value: Set<String>) = _state.update { it.copy(muscles = value) }

    // ── Running a flow ───────────────────────────────────────────────────────

    fun startFlow(flow: FlexibilityFlow) {
        val library = _state.value.stretches.associateBy(Stretch::name)
        val holds = FlowPlan.holds(flow.stretches.sortedBy { it.orderIndex }, library)
        if (holds.isEmpty()) {
            _state.update { it.copy(error = "\"${flow.name}\" has no stretches in it.") }
            return
        }
        FlowPlayback.start(container.appContext, FlowSession(flow = flow, holds = holds))
    }

    // The controls are FlowPlayback's: the notification drives the same
    // session, so there is one clock and one set of commands, not two.
    fun closeSession() = FlowPlayback.close()
    fun togglePause() = FlowPlayback.togglePause()
    fun skip() { FlowPlayback.skip() }
    fun back() { FlowPlayback.back() }
    fun extend() { FlowPlayback.extend(FlowPlayerService.EXTEND_SECONDS) }

    /**
     * Record the session.
     *
     * No exercises on it: a stretch has no weight and no reps, and the session
     * endpoint's progression machinery is for lifts. What is worth keeping is
     * that the flow was done, and when — see the class comment.
     */
    fun finishSession(rpe: Int?) {
        val session = _state.value.session ?: return
        updateSession { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // A log entry, written locally — a routine done on a hotel floor
            // or at a trailhead is the usual case, not the exception.
            runCatching {
                container.training.logSession(
                    WorkoutSessionIn(sessionRpe = rpe, notes = "Mobility: ${session.flow.name}"),
                    workoutId = null,
                    completedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
                )
            }
            updateSession { it.copy(saving = false, saved = true) }
            _state.update { it.copy(pending = container.replica.pendingCount().toInt()) }
        }
    }

    private fun updateSession(block: (FlowSession) -> FlowSession) = FlowPlayback.update(block)

    companion object {
        /** What the library uses when a stretch does not say. */
        const val DEFAULT_HOLD_SECONDS = 30

        /** Short: the point of a flow is to keep moving through it. */
        const val DEFAULT_REST_SECONDS = 10

        const val MAX_SETS = 6
    }
}
