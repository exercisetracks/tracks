// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.strength

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.api.Exercise
import com.tracks.core.api.LoggedExercise
import com.tracks.core.api.LoggedSet
import com.tracks.core.api.StrengthHistoryEntry
import com.tracks.core.api.StrengthProgress
import com.tracks.core.api.UserWorkout
import com.tracks.core.api.UserWorkoutIn
import com.tracks.core.api.WorkoutSessionIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import android.util.Log
import kotlin.math.roundToInt

/**
 * One exercise as the session is doing it — the prescription and what has
 * actually been lifted so far.
 *
 * Sets are a list of rows rather than a count plus a tally, because they differ
 * from each other: a set dropped from 8 reps to 5, or taken lighter than the one
 * before, is the interesting part of a session and a counter cannot hold it.
 */
data class SessionSet(
    val weightKg: Double,
    val reps: Int,
    val done: Boolean = false,
)

data class SessionExercise(
    val exercise: Exercise,
    val sets: List<SessionSet>,
    /** Seconds between sets. Starts the timer when a set is ticked off. */
    val restSeconds: Int = DEFAULT_REST_SECONDS,
    /**
     * The circuit or superset this belongs to, or null. Inside a group a set
     * is a round: ticking one moves to the next member, and the group's rest
     * runs only after the last. See [SessionLogic.afterSetDone].
     */
    val group: com.tracks.core.api.StepGroup? = null,
    /** A rest block that follows this exercise in the workout, in seconds. */
    val restAfterSeconds: Int = 0,
) {
    val allDone: Boolean get() = sets.all { it.done }
}

/**
 * A session in progress.
 *
 * Held in the ViewModel rather than in composable state so that rotating the
 * phone, or the screen turning itself off between sets, does not lose a
 * half-finished workout.
 */
data class SessionState(
    val exercises: List<SessionExercise> = emptyList(),
    val current: Int = 0,
    /** Seconds left on the rest timer, or null when it is not running. Derived from [restEndsAt]. */
    val restRemaining: Int? = null,
    /** When the running rest ends, wall clock — survives the process; see [SessionLogic]. */
    val restEndsAt: Long? = null,
    /** When the session began, for the summary's duration. */
    val startedAtMillis: Long = 0,
    /** Showing the end-of-session summary, before it is logged. */
    val reviewing: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val exercise: SessionExercise? get() = exercises.getOrNull(current)

    /** Nothing to send if no set was actually completed. */
    val hasWork: Boolean get() = exercises.any { ex -> ex.sets.any { it.done && it.reps > 0 } }
}

data class StrengthUiState(
    val loading: Boolean = true,
    val exercises: List<Exercise> = emptyList(),
    /** Per-exercise standing state, keyed by name. Sparse — see [StrengthProgress]. */
    val progress: Map<String, StrengthProgress> = emptyMap(),
    val equipment: List<String> = emptyList(),
    val search: String = "",
    val muscles: Set<String> = emptySet(),
    /** Equipment the user has said they own; null until asked, empty means none. */
    val myEquipment: Set<String> = emptySet(),
    val onlyMyEquipment: Boolean = false,
    /** The web's All / Preferred / Excluded / Custom filter. */
    val filter: LibraryFilter = LibraryFilter.All,
    /** Names picked for the session being assembled, in the order they were picked. */
    val picked: List<String> = emptyList(),
    /**
     * The user's own saved workouts — templates they wrote, not sessions.
     *
     * Cached like the library, so the list is there in a basement gym. Editing
     * one still needs a connection: unlike a planned workout, these are not in
     * the outbox, because nothing downstream depends on a template existing
     * before there is signal to save it.
     */
    val savedWorkouts: List<UserWorkout> = emptyList(),
    val session: SessionState? = null,
    val error: String? = null,
) {
    /**
     * The library, narrowed.
     *
     * Every filter is a conjunction and every one of them is optional, so an
     * untouched screen shows the whole library rather than nothing — the state
     * a user lands in should be the useful one.
     */
    val visible: List<Exercise>
        get() = exercises.filter { ex ->
            val matchesSearch = search.isBlank() ||
                ex.name.contains(search, ignoreCase = true)
            val matchesMuscle = muscles.isEmpty() ||
                // Secondary muscles count. Filtering to primaries only hides
                // the chin-up from someone who tapped their biceps, which is
                // both true and unhelpful.
                muscles.any { it in ex.primaryMuscles || it in ex.secondaryMuscles }
            val matchesEquipment = !onlyMyEquipment ||
                // Bodyweight is always available, whatever the user listed.
                ex.equipment.isEmpty() ||
                ex.equipment.any { it == "bodyweight" || it in myEquipment }
            matchesSearch && matchesMuscle && matchesEquipment && filter.accepts(ex.preference, ex.isCustom)
        }
}

/**
 * The strength library, and running a session out of it.
 *
 * Nothing here needs a server: the library ships in the app, saved workouts
 * and preferences are replica rows, and where the lifter stands on each lift
 * is derived from their logged sessions — which is where this screen is used
 * most, a basement gym with no signal.
 *
 * The session is the one write, logged locally as a `workout_session` (a sync
 * log row). Each exercise's best set moves that lift's estimated max and its
 * progression stage, which is what makes next week heavier. A session in
 * progress is written to disk on every change, so the process being reclaimed
 * between sets loses nothing — see [SessionLogic].
 */
class StrengthViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(StrengthUiState())
    val state: StateFlow<StrengthUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { container.localData.revision.drop(1).collect { load() } }
    }

    /**
     * Everything on this screen is the phone's own: the library ships in the
     * app (spec/library/), saved workouts and preferences are replica rows,
     * and where the lifter stands is derived from their logged sessions
     * ([com.tracks.core.local.LocalTraining.standings]). No request is made.
     */
    fun load() {
        viewModelScope.launch {
            val t = container.training
            val exercises = runCatching { t.exercises() }.getOrNull()
            val standings = runCatching { t.standings() }.getOrNull()
            val owned = runCatching { t.equipment() }.getOrNull()
            apply(exercises = exercises, standings = standings, owned = owned, stillLoading = false)
            val saved = runCatching { t.savedWorkouts() }.getOrNull()
            _state.update {
                it.copy(savedWorkouts = saved ?: it.savedWorkouts)
            }
        }
    }

    /**
     * Fold a set of sections into state, keeping whatever a null did not bring.
     *
     * Null means "this one did not arrive", never "this one is empty" — which
     * is the distinction that lets the same function apply the cached copy and
     * then the fresh one over the top of it.
     *
     * The error is only raised when there is nothing to show at all. A library
     * loaded yesterday is not an error condition; it is the offline case
     * working.
     */
    private fun apply(
        exercises: List<Exercise>?,
        standings: List<StrengthProgress>?,
        owned: List<String>?,
        stillLoading: Boolean,
    ) {
        _state.update {
            val library = exercises ?: it.exercises
            it.copy(
                loading = stillLoading && library.isEmpty(),
                exercises = library,
                progress = standings?.associateBy { p -> p.exerciseName } ?: it.progress,
                myEquipment = owned?.toSet() ?: it.myEquipment,
                equipment = library.flatMap(Exercise::equipment).distinct().sorted(),
                error = if (!stillLoading && library.isEmpty()) {
                    "Could not reach the server, and nothing is saved on this phone yet."
                } else {
                    null
                },
            )
        }
    }

    fun setSearch(value: String) = _state.update { it.copy(search = value) }

    fun setFilter(value: LibraryFilter) = _state.update { it.copy(filter = value) }

    /** ✓ / ✗ on a row — a replica edit, keyed by name so two phones agree on the row. */
    fun setPreference(name: String, preference: String?) {
        _state.update { s -> s.copy(exercises = s.exercises.map { if (it.name == name) it.copy(preference = preference) else it }) }
        viewModelScope.launch { runCatching { container.training.setPreference(false, name, preference) } }
    }

    fun saveCustom(draft: CustomDraft) {
        viewModelScope.launch { runCatching { container.training.saveCustom(false, draft.id, draft.values(false)) }; load() }
    }

    fun deleteCustom(id: Int) {
        viewModelScope.launch { runCatching { container.training.deleteCustom(false, id) }; load() }
    }

    fun setMuscles(value: Set<String>) = _state.update { it.copy(muscles = value) }

    fun toggleOnlyMyEquipment() =
        _state.update { it.copy(onlyMyEquipment = !it.onlyMyEquipment) }

    /**
     * The same synced `equipment_available` setting Settings › Strength edits —
     * one field, not a Strength-page copy, so the plan generator and the
     * filter always agree. Applied to state at once so the list refilters
     * under the sheet while the replica write lands.
     */
    fun setEquipment(items: List<String>) {
        _state.update { it.copy(myEquipment = items.toSet()) }
        viewModelScope.launch { runCatching { container.training.setEquipment(items) } }
    }

    fun togglePicked(name: String) = _state.update {
        it.copy(picked = if (name in it.picked) it.picked - name else it.picked + name)
    }

    fun clearPicked() = _state.update { it.copy(picked = emptyList()) }

    /**
     * Sets already recorded for one lift, newest first, for the detail sheet.
     *
     * Cached per exercise. A lift's history is what tells the user what to put
     * on the bar, and it is worth having in a gym with no signal — the same
     * argument as the library, applied to the one screen that is opened
     * mid-session.
     */
    suspend fun history(exerciseName: String): List<StrengthHistoryEntry> = withContext(Dispatchers.IO) {
        runCatching {
            container.library.strengthHistory(
                java.time.LocalDate.now().minusDays(365).toString(), container.sources.accountZone(),
                exerciseName, HISTORY_LIMIT,
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Record a max the user knows rather than one the server guessed.
     *
     * Written through and then folded into local state, so the list reflects it
     * without a refetch. If the write fails the local value is left alone —
     * showing a number the server does not have would be worse than showing the
     * old one.
     */
    fun setOneRepMax(exerciseName: String, kg: Double) {
        viewModelScope.launch {
            container.training.setOneRepMax(exerciseName, kg)
            load()
        }
    }

    // ── The session ──────────────────────────────────────────────────────────

    /**
     * Turn the picked exercises into a session, prefilled from last time.
     *
     * The weight field opens on what was lifted last rather than empty, which is
     * the difference between logging a workout and being told what to lift.
     * Reps and set count come from the library's own prescription, falling back
     * to a plain three-by-eight when the row does not carry one.
     */
    fun startSession() {
        val state = _state.value
        val byName = state.exercises.associateBy(Exercise::name)
        val exercises = state.picked.mapNotNull(byName::get).map { exercise ->
            val standing = state.progress[exercise.name]
            val sets = exercise.defaultSets ?: DEFAULT_SETS
            val reps = standing?.lastReps ?: exercise.defaultReps ?: DEFAULT_REPS
            SessionExercise(
                exercise = exercise,
                sets = List(sets.coerceIn(1, MAX_SETS)) {
                    SessionSet(weightKg = standing?.lastWeightKg ?: 0.0, reps = reps)
                },
            )
        }
        if (exercises.isEmpty()) return
        _state.update { it.copy(session = SessionState(exercises = exercises, startedAtMillis = now())) }
        persist()
    }

    /**
     * Save a template, new or edited — a local edit like everything else, so
     * a template built in a basement gym exists at once and syncs later.
     */
    fun saveWorkout(id: Int?, workout: UserWorkoutIn) {
        viewModelScope.launch {
            runCatching { container.training.saveWorkout(id, workout) }.onFailure {
                _state.update { it.copy(error = "Could not save that workout.") }
                return@launch
            }
            load()
        }
    }

    fun deleteWorkout(id: Int) {
        viewModelScope.launch {
            runCatching { container.sources.delete("workout", id) }
            _state.update { state -> state.copy(savedWorkouts = state.savedWorkouts.filterNot { it.id == id }) }
        }
    }

    /**
     * Run a saved workout now.
     *
     * Its exercises become the picked set and the session opens, so a template
     * is a way into the runner that already exists rather than a second one.
     * A name the library no longer has is skipped — a custom exercise can be
     * deleted out from under a template.
     */
    fun startSavedWorkout(workout: UserWorkout) {
        val state = _state.value
        val exercises = SessionLogic.plan(
            workout.exercises.sortedBy { it.orderIndex },
            state.exercises.associateBy(Exercise::name),
        ) { exercise -> state.progress[exercise.name]?.let { it.lastWeightKg to it.lastReps } }
        if (exercises.isEmpty()) {
            _state.update { it.copy(error = "None of that workout's exercises are in the library.") }
            return
        }
        _state.update { it.copy(session = SessionState(exercises = exercises, startedAtMillis = now())) }
        persist()
    }

    /**
     * Leave the runner. A session with work in it is kept (on disk too), so
     * "Back" pauses rather than discards; [discardSession] is the explicit bin.
     */
    fun closeSession() {
        val s = _state.value.session
        if (s != null && (s.saved || !s.hasWork)) discardSession()
        else _state.update { it.copy(session = null) }
    }

    fun discardSession() {
        restJob?.cancel()
        _state.update { it.copy(session = null) }
        sessionPrefs.edit().remove(KEY_SESSION).apply()
    }

    /** Reopen a session left with work in it, from disk. */
    fun resumeSession() {
        val text = sessionPrefs.getString(KEY_SESSION, null) ?: return
        val restored = SessionLogic.decode(text, _state.value.exercises) ?: return
        _state.update { it.copy(session = restored) }
        restored.restEndsAt?.let { if (it > now()) runRest(it) }
    }

    /** True when a session with work in it is waiting on disk — the screen offers to resume it. */
    val hasSavedSession: Boolean get() = sessionPrefs.contains(KEY_SESSION)

    fun selectSessionExercise(index: Int) = updateSession {
        it.copy(current = index.coerceIn(0, it.exercises.lastIndex))
    }

    /** On to the next unfinished exercise, leaving this one's sets as they are. */
    fun skipExercise() = updateSession { s ->
        SessionLogic.nextUnfinished(s.exercises, s.current)?.let { s.copy(current = it) } ?: s
    }

    /** Alternatives for the exercise on screen. */
    fun substitutes(): List<Exercise> {
        val st = _state.value
        val s = st.session ?: return emptyList()
        val current = s.exercise?.exercise ?: return emptyList()
        return SessionLogic.substitutes(
            current, st.exercises, s.exercises.map { it.exercise.name }.toSet(),
            st.myEquipment, st.onlyMyEquipment,
        )
    }

    /**
     * Swap the exercise on screen for [replacement], keeping its set count and
     * prefilling weight and reps from where the lifter stands on the new one.
     */
    fun substitute(replacement: Exercise) {
        val standing = _state.value.progress[replacement.name]
        updateSession { s ->
            s.mapCurrent { ex ->
                val reps = standing?.lastReps ?: replacement.defaultReps ?: DEFAULT_REPS
                ex.copy(
                    exercise = replacement,
                    sets = ex.sets.map { SessionSet(weightKg = standing?.lastWeightKg ?: 0.0, reps = reps) },
                )
            }
        }
    }

    /** Show what the session added up to before it is logged. */
    fun review(open: Boolean = true) = updateSession { it.copy(reviewing = open) }

    fun summary(): SessionLogic.Summary? = _state.value.session?.let { SessionLogic.summary(it, now()) }

    fun editSet(setIndex: Int, weightKg: Double? = null, reps: Int? = null) = updateSession { s ->
        s.mapCurrent { ex ->
            ex.copy(
                sets = ex.sets.mapIndexed { i, set ->
                    if (i != setIndex) set
                    else set.copy(
                        weightKg = weightKg ?: set.weightKg,
                        reps = reps ?: set.reps,
                    )
                },
            )
        }
    }

    fun addSet() = updateSession { s ->
        s.mapCurrent { ex ->
            if (ex.sets.size >= MAX_SETS) ex
            // Copied from the last one rather than blank: an added set is
            // almost always another of the same, and a zero would have to be
            // retyped every time.
            else ex.copy(sets = ex.sets + (ex.sets.lastOrNull() ?: SessionSet(0.0, DEFAULT_REPS)).copy(done = false))
        }
    }

    fun removeSet() = updateSession { s ->
        s.mapCurrent { ex -> if (ex.sets.size <= 1) ex else ex.copy(sets = ex.sets.dropLast(1)) }
    }

    /**
     * Tick a set off, and start resting.
     *
     * The rest timer starts here rather than on a separate button because the
     * moment a set ends is the moment rest begins, and a timer you have to
     * remember to start is one that measures the wrong thing.
     */
    fun toggleSetDone(setIndex: Int) {
        val session = _state.value.session ?: return
        val nowDone = session.exercise?.sets?.getOrNull(setIndex)?.done == false
        updateSession { s ->
            s.mapCurrent { ex ->
                ex.copy(sets = ex.sets.mapIndexed { i, set ->
                    if (i == setIndex) set.copy(done = !set.done) else set
                })
            }
        }
        if (!nowDone) return
        // Inside a circuit or superset the next thing is the next member, not
        // a rest — see SessionLogic.afterSetDone.
        val after = _state.value.session?.let { SessionLogic.afterSetDone(it, it.current, setIndex) } ?: return
        if (after.moveTo != null) updateSession { it.copy(current = after.moveTo) }
        startRest(after.restSeconds)
    }

    fun setRestSeconds(seconds: Int) = updateSession { s ->
        s.mapCurrent { it.copy(restSeconds = seconds.coerceIn(0, MAX_REST_SECONDS)) }
    }

    fun skipRest() {
        restJob?.cancel()
        restJob = null
        updateSession { it.copy(restRemaining = null, restEndsAt = null) }
    }

    /** A little more rest, without restarting the count. */
    fun extendRest(seconds: Int) {
        val endsAt = _state.value.session?.restEndsAt ?: return
        runRest(endsAt + seconds * 1000L)
    }

    private var restJob: kotlinx.coroutines.Job? = null

    private fun startRest(seconds: Int) {
        if (seconds <= 0) {
            skipRest()
            return
        }
        runRest(now() + seconds * 1000L)
    }

    /**
     * Count down to a wall-clock deadline.
     *
     * A deadline rather than a counted loop: it is what gets written to disk,
     * so a session restored after the process was killed mid-rest resumes the
     * same rest instead of losing it or starting it again. The phone buzzes when
     * it is over — the lifter is across the room, not looking at the screen.
     */
    private fun runRest(endsAt: Long) {
        restJob?.cancel()
        updateSession { it.copy(restEndsAt = endsAt, restRemaining = SessionLogic.restRemaining(endsAt, now())) }
        restJob = viewModelScope.launch {
            while (true) {
                val left = SessionLogic.restRemaining(endsAt, now())
                updateSession { it.copy(restRemaining = left) }
                if (left == null) break
                kotlinx.coroutines.delay(250)
            }
            updateSession { it.copy(restEndsAt = null, restRemaining = null) }
            cues.nudge()
        }
    }

    /**
     * Send the session.
     *
     * Only completed sets with real reps go: an untouched row is a set that did
     * not happen, and sending it as zero would drag the exercise's estimated max
     * down for a lift the user simply stopped short of.
     */
    fun finishSession(sessionRpe: Int?) {
        val session = _state.value.session ?: return
        if (!session.hasWork) {
            updateSession { it.copy(error = "Tick off at least one set first.") }
            return
        }
        updateSession { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val body = WorkoutSessionIn(
                sessionRpe = sessionRpe,
                exercises = session.exercises.mapNotNull { ex ->
                    val sets = ex.sets
                        .filter { it.done && it.reps > 0 }
                        .map { LoggedSet(weightKg = it.weightKg, reps = it.reps) }
                    if (sets.isEmpty()) null
                    else LoggedExercise(exerciseName = ex.exercise.name, sets = sets)
                },
            )
            // A log entry, written locally: a session that happened, happened,
            // and nothing about signal or a server can refuse it.
            runCatching {
                container.training.logSession(
                    body, workoutId = null,
                    completedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
                )
            }.onFailure { Log.w("TracksStrength", "session not logged", it) }
            updateSession { it.copy(saving = false, saved = true) }
            sessionPrefs.edit().remove(KEY_SESSION).apply()
            load()
        }
    }

    private fun updateSession(block: (SessionState) -> SessionState) {
        _state.update { it.copy(session = it.session?.let(block)) }
        persist()
    }

    /**
     * Write the running session down. Cheap (a few hundred bytes) and done on
     * every change, because the change that is not written is the one lost when
     * the process is reclaimed between sets. A logged session is removed.
     */
    private fun persist() {
        val s = _state.value.session ?: return
        if (s.saved) return
        sessionPrefs.edit().putString(KEY_SESSION, SessionLogic.encode(s)).apply()
    }

    private val sessionPrefs by lazy {
        container.appContext.getSharedPreferences("strength_session", android.content.Context.MODE_PRIVATE)
    }

    private val cuesLazy = lazy { com.tracks.app.run.RunCues(container.appContext) }
    private val cues by cuesLazy

    private fun now(): Long = System.currentTimeMillis()

    override fun onCleared() {
        restJob?.cancel()
        if (cuesLazy.isInitialized()) cues.release()
    }

    private fun SessionState.mapCurrent(block: (SessionExercise) -> SessionExercise): SessionState =
        copy(exercises = exercises.mapIndexed { i, ex -> if (i == current) block(ex) else ex })

    private companion object {
        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 8
        const val MAX_SETS = 12
        const val MAX_REST_SECONDS = 600
        const val HISTORY_LIMIT = 40
        const val KEY_SESSION = "session"
    }
}

/**
 * Ninety seconds.
 *
 * Long enough for a compound lift to be worth resting from, short enough that
 * an accessory set does not feel like waiting. It is per-exercise and editable
 * in the runner, so this is only where the number starts.
 */
const val DEFAULT_REST_SECONDS = 90

/** e1RM by Epley, the same formula the server uses when it folds in a session. */
fun epleyOneRepMax(weightKg: Double, reps: Int): Double =
    if (reps <= 1) weightKg else (weightKg * (1 + reps / 30.0) * 10).roundToInt() / 10.0
