// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.run.RunFit
import com.tracks.app.run.RunPhase
import com.tracks.app.run.RunRecorder
import com.tracks.core.api.LoggedExercise
import com.tracks.core.api.LoggedSet
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.WorkoutSessionIn
import com.tracks.core.fit.vdotToPaces
import com.tracks.core.sync.localCoachingContext
import com.tracks.core.spec.sportType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GuidedUiState(
    val loading: Boolean = true,
    /** Null while loading, and after loading when the plan has no such workout. */
    val workout: PlannedWorkout? = null,
    val steps: List<GuidedStep> = emptyList(),
    val index: Int = 0,
    /** Seconds left on the step on screen; 0 for a step with no target. */
    val remaining: Int = 0,
    /** Metres covered inside the step on screen. Only a recording fills this. */
    val stepCoveredM: Double = 0.0,
    /** Moving time inside the step on screen, for its own pace. Only a recording fills this. */
    val stepMs: Long = 0,
    /**
     * The run's counted distance where each step reached so far began, by step
     * index — what lets the distance dial draw a finished section at its real
     * length rather than its planned one.
     */
    val stepStartsM: List<Double> = emptyList(),
    /**
     * Seconds per kilometre for each pace zone, from the plan's VDOT; null with
     * no running plan. The pace dial's targets.
     */
    val paces: Map<String, Double>? = null,
    /** Paused is a timer that is not running, not a fourth phase. */
    val running: Boolean = false,
    /** Whether the first step has ever been started — a pause is not a start. */
    val begun: Boolean = false,
    /** Walked past the last step. */
    val done: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** Saved into the outbox rather than sent — worth saying, so nobody re-does it. */
    val queued: Boolean = false,
    val error: String? = null,
) {
    val step: GuidedStep? get() = steps.getOrNull(index)
    val next: GuidedStep? get() = steps.getOrNull(index + 1)

    /**
     * Whether this workout is recorded rather than merely counted down.
     *
     * Running only, and that is a limit of the encoder rather than of the idea:
     * [RunFit] writes `sport = running` into every file it builds, so recording
     * a planned ride through it would file the ride as a run. Everything else
     * gets the step runner, which is what the plan describes anyway — the
     * generator gives non-running sports prose and durations, not routes.
     */
    val isRecorded: Boolean
        get() = workout != null && sportType(workout.sport) == "running"
}

/**
 * One planned workout, run the way a watch would run it.
 *
 * ## Two shapes, one plan
 *
 * A planned run is a *recording*: the phone takes GPS fixes, measures distance
 * against what the server planned, and produces a FIT file at the end — the
 * same file a watch would have produced, going in by the same door. Everything
 * else is a *guide*: a sequence of steps with timers, which produces a logged
 * session and no activity.
 *
 * Which one you get is decided by the sport, in [GuidedUiState.isRecorded], and
 * the two share this ViewModel deliberately. The step cursor, the completion
 * write, the cached-plan patch and the "what happens when there is no signal"
 * story are identical for both; only the source of the clock differs.
 *
 * ## Where the clock comes from
 *
 * In the step runner, from a coroutine ticking once a second. In a recording,
 * from the run's own *moving* time — so a step does not tick down while the
 * runner is stopped at a junction with the recording paused. That is the whole
 * reason the two are not the same timer.
 *
 * ## Offline
 *
 * The workout is read from the same cached document the dashboard fills, so a
 * session opened at a trailhead with no signal has its steps. Completion goes
 * through the outbox, and the cached copy is patched on the spot so the
 * dashboard stops offering a workout that has just been done.
 */
class GuidedWorkoutViewModel(
    private val container: AppContainer,
    private val workoutId: Int,
    /** This session's key in the Activity's store — see [ActiveWorkout]. */
    val key: String,
) : ViewModel() {

    private val _state = MutableStateFlow(GuidedUiState())
    val state: StateFlow<GuidedUiState> = _state.asStateFlow()

    /** Where the current step began, in the recording's own numbers. */
    private var stepStartMovingMs = 0L
    private var stepStartDistanceM = 0.0

    /** A race's course, for changing legs by position (RaceGuide.CourseProgress). */
    private var course: com.tracks.core.race.RaceGuide.CourseProgress? = null

    init {
        load()
        watchRecording()
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    /** The workout is a row in this phone's replica; nothing to fetch. */
    private fun load() {
        viewModelScope.launch {
            val workout = runCatching { container.sources.get("planned_workout", workoutId, PlannedWorkout.serializer()) }.getOrNull()
            if (workout != null) apply(listOf(workout), stillLoading = false)
            else _state.update { it.copy(loading = false) }
            if (workout != null && workout.workoutType == "race") raceLegs(workout)
        }
        // The paces the watch would be given for this plan, regardless of the
        // watch's pace-coaching switch: that switch is about the wrist
        // buzzing, and a dial showing a target nags nobody.
        viewModelScope.launch {
            val vdot = runCatching { localCoachingContext(container.sources).vdot }.getOrNull()
            if (vdot != null) _state.update { it.copy(paces = vdotToPaces(vdot)) }
        }
    }

    private fun apply(workouts: List<PlannedWorkout>, stillLoading: Boolean) {
        val workout = workouts.firstOrNull { it.id == workoutId } ?: return
        _state.update { current ->
            // Steps are built once. A refresh landing mid-session must not
            // rebuild the list under the user and reset them to step one.
            val steps = current.steps.ifEmpty { guidedSteps(workout) }
            current.copy(
                // Found is found. The refresh behind this one changes what the
                // workout says, not whether there is one to show.
                loading = false,
                workout = workout,
                steps = steps,
                remaining = if (current.steps.isEmpty()) {
                    steps.firstOrNull()?.seconds ?: 0
                } else {
                    current.remaining
                },
            )
        }
    }

    /**
     * Race day: the race plan's terrain legs instead of the workout's one block.
     *
     * The race is the event goal on this date; its plan is worked out the way
     * the Race Plans screen does it, so what is paced is what was shown. Each
     * leg changes where the terrain does — by position on the course when one
     * is loaded, by distance run otherwise — and is announced with a buzz and
     * the words for the change ("Climb ahead…", "Top of the climb…").
     * Swapped in only before the race begins.
     */
    private suspend fun raceLegs(workout: PlannedWorkout) {
        val imperial = com.tracks.core.format.Units.imperial
        val plans = com.tracks.core.local.LocalRacePlans(container.sources)
        val today = java.time.LocalDate.now().let { com.tracks.core.fit.decode.CivilDate(it.year, it.monthValue, it.dayOfMonth) }
        val goal = runCatching {
            container.sources.goals().firstOrNull {
                it.goalType == "event" && it.eventDate == workout.scheduledDate &&
                    sportType(it.eventSport ?: "running") == sportType(workout.sport)
            }
        }.getOrNull() ?: return
        val (pred, strategy) = runCatching { plans.runningPlan(goal, imperial, today) }.getOrNull() ?: return
        val legs = com.tracks.core.race.RaceGuide.legs(pred.laps, imperial)
        if (legs.isEmpty()) return
        val progress = com.tracks.core.race.RaceGuide.CourseProgress(strategy.path, legs.last().courseEndM)
        course = progress.takeIf { it.usable }
        val steps = legs.mapIndexed { i, leg ->
            GuidedStep(
                title = leg.title,
                detail = leg.detail,
                metres = leg.distanceM,
                courseEndM = if (course != null) leg.courseEndM else null,
                spoken = leg.spoken,
                position = "${i + 1} of ${legs.size}",
            )
        }
        _state.update { if (it.begun) it else it.copy(steps = steps, index = 0, remaining = 0) }
    }

    // ── The step cursor ──────────────────────────────────────────────────────

    /**
     * Begin, or resume after a pause.
     *
     * In a recording this is called once the service is actually running, not
     * when the button is pressed: the permission dialog sits between the two,
     * and a workout that started its first step while the user was reading a
     * permission prompt would be a minute into a warm-up nobody had begun.
     */
    fun begin() {
        markStepStart()
        _state.update { it.copy(running = true, begun = true, error = null) }
        ActiveWorkout.begin(
            ActiveWorkout.Session(
                workoutId = workoutId,
                title = _state.value.workout?.title?.takeIf { it.isNotBlank() } ?: "Workout",
                key = key,
            )
        )
        if (!_state.value.isRecorded) tick()
    }

    fun togglePause() {
        val running = _state.value.running
        _state.update { it.copy(running = !running) }
        if (running) timer?.cancel() else if (!_state.value.isRecorded) tick()
    }

    /** Skip whatever is on screen — a step you cannot do, or a rest cut short. */
    fun skip() = advance()

    fun back() {
        val index = _state.value.index
        if (index == 0) return
        timer?.cancel()
        _state.update {
            it.copy(index = index - 1, remaining = it.steps[index - 1].seconds ?: 0, done = false)
        }
        markStepStart()
        if (_state.value.running && !_state.value.isRecorded) tick()
    }

    private fun advance() {
        val state = _state.value
        val next = state.index + 1
        if (next >= state.steps.size) {
            timer?.cancel()
            _state.update { it.copy(done = true, running = false, remaining = 0) }
            return
        }
        timer?.cancel()
        _state.update {
            it.copy(index = next, remaining = it.steps[next].seconds ?: 0)
        }
        markStepStart()
        announce(state.steps[next])
        if (state.running && !state.isRecorded) tick()
    }

    private var timer: Job? = null

    private fun tick() {
        timer?.cancel()
        val step = _state.value.step ?: return
        // A step with no target waits for a person. Ticking a clock nobody is
        // counting down to would advance the runner past a set of squats.
        if (step.seconds == null) return
        timer = viewModelScope.launch {
            while (true) {
                delay(1000)
                val state = _state.value
                if (!state.running || state.done) return@launch
                if (state.remaining > 1) {
                    _state.update { it.copy(remaining = it.remaining - 1) }
                } else {
                    advance()
                    return@launch
                }
            }
        }
    }

    // ── The recording ────────────────────────────────────────────────────────

    /**
     * Advance the plan from the run's own measurements.
     *
     * A step ends when the runner has covered its distance or spent its time,
     * measured from where the step began — which is what makes "6 × 400 m"
     * something the phone can call rather than something the runner has to
     * count. Moving time, not elapsed: a pause at a road crossing is not part
     * of the interval.
     */
    private fun watchRecording() {
        viewModelScope.launch {
            RunRecorder.state.collect { run ->
                val state = _state.value
                if (!state.isRecorded || !state.running || state.done) return@collect
                if (run.phase != RunPhase.Recording) return@collect
                // Only the live session steers the recording. The Activity
                // keeps finished sessions' ViewModels until it goes, and one of
                // those reacting to the next run would advance its own plan and
                // switch that run's distance counting on and off.
                if (ActiveWorkout.current.value?.key != key) return@collect
                val step = state.step ?: return@collect

                val covered = run.distanceM - stepStartDistanceM
                _state.update { it.copy(stepCoveredM = covered, stepMs = run.movingMs - stepStartMovingMs) }

                // A race leg on a known course ends where the course says,
                // whatever the GPS distance has drifted to; off the line, or
                // with no course, the distance run is what is left.
                val onCourse = step.courseEndM?.let { end ->
                    val lat = run.lat
                    val lng = run.lng
                    if (lat == null || lng == null) null else course?.update(lat, lng)?.let { it >= end }
                }
                if (onCourse == true) {
                    advance()
                    return@collect
                }
                val metres = step.metres
                if (onCourse == null && metres != null && covered >= metres) {
                    advance()
                    return@collect
                }
                val seconds = step.seconds
                if (seconds != null) {
                    val spent = ((run.movingMs - stepStartMovingMs) / 1000).toInt()
                    val left = (seconds - spent).coerceAtLeast(0)
                    if (left == 0) advance() else _state.update { it.copy(remaining = left) }
                }
            }
        }
    }

    private fun markStepStart() {
        // A walk step (the plan's walking warm-up and cool-down) is on the map
        // but not in the run's distance or pace.
        if (_state.value.isRecorded) RunRecorder.setCounting(_state.value.step?.countsDistance ?: true)
        val run = RunRecorder.state.value
        stepStartMovingMs = run.movingMs
        stepStartDistanceM = run.distanceM
        // Truncated to the cursor first, so stepping back re-measures the step
        // rather than keeping the start of the attempt that was abandoned.
        _state.update {
            it.copy(
                stepCoveredM = 0.0,
                stepMs = 0,
                stepStartsM = it.stepStartsM.take(it.index) + run.distanceM,
            )
        }
    }

    /** Said out loud, because the phone is in a pocket. Silent if the step runner. */
    private fun announce(step: GuidedStep) {
        if (!_state.value.isRecorded) return
        RunRecorder.cues?.announceStep(spokenStep(step))
    }

    // ── Finishing ────────────────────────────────────────────────────────────

    /**
     * A guided session, recorded as one.
     *
     * Not an activity: there is no non-FIT path into the activity table, and
     * synthesising a FIT for a strength session would be a lot of machinery for
     * a record with no track. The session log is where the server keeps these,
     * and logging one with a planned workout on it ticks that workout off — so
     * this is one write, not two.
     */
    fun finishSession(rpe: Int?) {
        val state = _state.value
        val workout = state.workout ?: return
        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            runCatching {
                container.training.logSession(
                    WorkoutSessionIn(
                        plannedWorkoutId = workout.id,
                        exercises = completedExercises(),
                        sessionRpe = rpe,
                        notes = workout.title.takeIf { it.isNotBlank() },
                    ),
                    workoutId = null,
                    completedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
                )
            }.onFailure { Log.w(TAG, "session not logged", it) }
            markCompleteLocally(workout)
            _state.update { it.copy(saving = false, saved = true, queued = false) }
            ActiveWorkout.end(key)
        }
    }

    /**
     * Every set the user actually walked past, grouped by lift.
     *
     * Walked past, not prescribed: someone who stops after two of four sets has
     * done two, and telling the server otherwise would move next week's
     * prescription on a session that did not happen. The prescribed weight and
     * reps are used for the sets that *were* done, because this runner has no
     * field to correct them in — the strength screen's own runner does, and is
     * still the place to log a session that went differently.
     */
    private fun completedExercises(): List<LoggedExercise> {
        val state = _state.value
        // Everything before the cursor, plus the step on screen once the runner
        // has been walked off the end of the list.
        val reached = if (state.done) state.steps else state.steps.take(state.index)
        return reached.mapNotNull { it.exercise }
            .groupBy { it.name }
            .map { (name, targets) ->
                LoggedExercise(
                    exerciseName = name,
                    sets = targets.map {
                        LoggedSet(weightKg = it.weightKg ?: 0.0, reps = it.reps ?: 0)
                    },
                )
            }
    }

    /**
     * Save the run: encode, import, upload, tick off.
     *
     * The order matters and is the same one [com.tracks.app.ui.run] settled on:
     * the file is built and put in the user's own activity list before anything
     * is asked of a radio, because a run finished out of signal is still a run
     * that happened.
     */
    fun saveRun() {
        val state = _state.value
        val workout = state.workout ?: return
        val run = RunRecorder.state.value
        val fixes = RunRecorder.fixes
        if (fixes.isEmpty()) {
            _state.update { it.copy(error = "There are no GPS points to save.") }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val bytes = runCatching {
                RunFit.encode(
                    fixes = fixes,
                    distanceM = run.distanceM,
                    ascentM = run.ascentM,
                    elapsedMs = run.elapsedMs,
                    movingMs = run.movingMs,
                    startedAtMs = RunRecorder.startedAt(),
                    cumulativeM = RunRecorder.cumulativeM(),
                    runningMs = run.runningMs,
                )
            }.getOrElse {
                Log.e(TAG, "could not encode the run", it)
                null
            }
            if (bytes == null) {
                _state.update {
                    it.copy(saving = false, error = "Could not build the activity file from this run.")
                }
                return@launch
            }

            // Stored and imported like a file off the watch — the run is in
            // the list, on the dashboard and in the fitness model at once —
            // then sent up through the same queue as every other file.
            runCatching { container.files.add(bytes) }
            val uploaded = runCatching { container.uploadFiles() > 0 || !container.isLinked() }.getOrDefault(false)

            // Ticked off either way: a run done at 90% of the planned distance
            // was still the session, which is a judgement the person who ran it
            // gets to make.
            markCompleteLocally(workout)
            val sent = true

            _state.update {
                it.copy(
                    saving = false,
                    saved = true,
                    queued = !uploaded || !sent,
                    error = if (uploaded) null else UPLOAD_PENDING,
                )
            }
            // Saved is over: the run is in the activity list now, and a
            // recorder still holding it would offer to save it again the next
            // time any run screen opened.
            RunRecorder.reset()
            ActiveWorkout.end(key)
        }
    }

    /** Done with a finished run, so the recorder is free for the next one. */
    fun clearRun() {
        RunRecorder.reset()
        ActiveWorkout.end(key)
    }

    /**
     * Delete everything done so far and leave — the paused screen's "delete
     * and quit", confirmed there.
     *
     * Nothing is written: no FIT, no session log, the workout stays unticked
     * on the plan so it can be started again. A recording is thrown away by
     * the service (`ACTION_DISCARD`), which owns the GPS; the caller sends that.
     */
    fun discard() {
        timer?.cancel()
        _state.update { it.copy(running = false) }
        ActiveWorkout.end(key)
    }

    /** Tick the workout off — a one-field local edit that syncs by field. */
    private suspend fun markCompleteLocally(workout: PlannedWorkout) {
        runCatching { container.sources.setValues("planned_workout", workout.id, mapOf("is_complete" to true)) }
        _state.update { it.copy(workout = it.workout?.copy(isComplete = true)) }
    }

    override fun onCleared() {
        timer?.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "TracksGuided"

        const val UPLOAD_PENDING =
            "Saved to this phone and showing in your activities. " +
                "It will upload to your server on the next sync."
    }
}
