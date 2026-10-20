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
    /** Seconds the step runner has been running, pauses excluded. */
    val elapsedS: Int = 0,
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
     * Whether the session can be resized before it starts: a flexibility flow
     * or a strength session, rebuilt for a different length (see
     * [GuidedWorkoutViewModel.resize]).
     */
    val resizable: Boolean
        get() = !begun && workout?.steps?.any { it.type == "mobility_exercise" || it.type == "strength_exercise" } == true

    /** The workout's sport family (PlanBase.sportFamily), lower case. */
    val family: String
        get() = com.tracks.core.plan.PlanBase.sportFamily((workout?.sport ?: "running").lowercase())

    /**
     * Whether this workout is recorded with GPS rather than merely counted down:
     * any sport done by moving across the ground or water. Running only until
     * [RunFit] learned to write other sports; strength, flexibility and the
     * like get the step runner and a logged session.
     */
    val isRecorded: Boolean
        get() = workout != null && (
            family in GPS_FAMILIES || (workout.sport ?: "").lowercase() in GPS_SPORTS
            )

    /** Whether the main figure is speed rather than pace: anything on wheels or skis. */
    val bySpeed: Boolean get() = family in SPEED_FAMILIES

    companion object {
        val GPS_FAMILIES = setOf("running", "hiking", "cycling", "mountain_biking", "nordic_skiing", "rowing")
        val GPS_SPORTS = setOf("walking", "kayaking", "paddling", "stand_up_paddleboarding", "trail_running")
        val SPEED_FAMILIES = setOf("cycling", "mountain_biking", "nordic_skiing")
    }
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

    /** The person's name, for the closing line ("Good work, Hawk"). */
    private var athleteName: String? = null

    /**
     * The step runner's voice. A recording speaks through its service's cues
     * ([RunRecorder.cues]); a strength or mobility session has no service of
     * that kind and used to be silent, so it gets its own.
     */
    private var sessionCues: com.tracks.app.run.RunCues? = null

    private val cues: com.tracks.app.run.RunCues?
        get() = if (_state.value.isRecorded) RunRecorder.cues else sessionCues

    /** How this session is spoken of: "run", "strength workout". */
    private val spokenSport: com.tracks.app.run.SpokenCues.Sport
        get() = com.tracks.app.run.SpokenCues.sport(
            _state.value.workout?.sport?.let { com.tracks.core.plan.PlanBase.sportFamily(it.lowercase()) }
                ?.takeIf { it != "generic" } ?: _state.value.workout?.sport?.lowercase(),
        )

    init {
        load()
        watchRecording()
        viewModelScope.launch { athleteName = runCatching { container.sources.setting("name") }.getOrNull() }
    }

    /**
     * Before the recording service starts: what it says and how it splits.
     * The service speaks the opening line the moment it begins.
     */
    fun prepareVoice() {
        val sport = spokenSport
        RunRecorder.voice = RunRecorder.Voice(
            sport = sport,
            imperial = com.tracks.core.format.Units.imperial,
            opening = com.tracks.app.run.SpokenCues.start(sport, _state.value.steps.firstOrNull()?.let(::openingStep)),
            name = athleteName,
        )
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    /** The workout is a row in this phone's replica; nothing to fetch. */
    private fun load() {
        viewModelScope.launch {
            val workout = if (AdHocWorkouts.isAdHoc(workoutId)) AdHocWorkouts.get(workoutId)
            else runCatching { container.sources.get("planned_workout", workoutId, PlannedWorkout.serializer()) }.getOrNull()
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

    /**
     * Rebuild the session for [minutes] -- ten minutes instead of the planned
     * fifteen, or forty-five on a sore day -- keeping its muscles and its
     * intensity (LocalPlanning.resized). For this session only: the plan's
     * entry is left as it was.
     */
    fun resize(minutes: Int) {
        val workout = _state.value.workout ?: return
        if (_state.value.begun) return
        viewModelScope.launch {
            val steps = runCatching {
                com.tracks.core.local.LocalPlanning(container.sources, container.library).resized(workout, minutes)
            }.getOrNull() ?: return@launch
            if (steps.isEmpty() || _state.value.begun) return@launch
            val resized = workout.copy(steps = steps, durationMinutes = minutes)
            val guided = guidedSteps(resized)
            _state.update {
                it.copy(workout = resized, steps = guided, index = 0, remaining = guided.firstOrNull()?.seconds ?: 0)
            }
        }
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
        val first = ActiveWorkout.current.value?.key != key
        ActiveWorkout.begin(
            ActiveWorkout.Session(
                workoutId = workoutId,
                title = _state.value.workout?.title?.takeIf { it.isNotBlank() } ?: "Workout",
                key = key,
            )
        )
        if (!_state.value.isRecorded) {
            runClock()
            if (sessionCues == null) sessionCues = com.tracks.app.run.RunCues(container.appContext)
            if (first) {
                sessionCues?.announceStart(
                    com.tracks.app.run.SpokenCues.start(spokenSport, _state.value.step?.let(::openingStep)),
                )
            }
            GuidedSessionService.start(container.appContext, _state.value.workout?.title ?: "Workout")
            tick()
        }
    }

    fun togglePause() {
        val running = _state.value.running
        _state.update { it.copy(running = !running) }
        // A recording's service says this itself (RunService); a step session
        // has only its own voice.
        if (!_state.value.isRecorded) {
            if (running) sessionCues?.announcePause() else sessionCues?.announceResume()
        }
        if (running) timer?.cancel() else if (!_state.value.isRecorded) tick()
        if (!running && !_state.value.isRecorded) runClock()
    }

    private var clockJob: Job? = null

    /** The step runner's session clock: one second at a time while running. */
    private fun runClock() {
        if (clockJob?.isActive == true) return
        clockJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val state = _state.value
                if (!state.running || state.done) return@launch
                _state.update { it.copy(elapsedS = it.elapsedS + 1) }
            }
        }
    }

    /**
     * Skip whatever is on screen — a step you cannot do, or a rest cut short.
     * What was still being said about it stops, so a run of skips speaks only
     * the step it lands on.
     */
    fun skip() {
        cues?.interrupt()
        advance()
    }

    fun back() {
        val index = _state.value.index
        if (index == 0) return
        timer?.cancel()
        _state.update {
            it.copy(index = index - 1, remaining = it.steps[index - 1].seconds ?: 0, done = false)
        }
        markStepStart()
        cues?.interrupt()
        _state.value.step?.let(::announce)
        if (_state.value.running && !_state.value.isRecorded) tick()
    }

    private fun advance() {
        val state = _state.value
        val next = state.index + 1
        if (next >= state.steps.size) {
            timer?.cancel()
            _state.update { it.copy(done = true, running = false, remaining = 0) }
            if (!state.isRecorded) {
                sessionCues?.announceFinish(
                    com.tracks.app.run.SpokenCues.finish(spokenSport, athleteName, (System.currentTimeMillis() / 1000).toInt()),
                )
            }
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
                    // "3, 2, 1" into the end of a timed step long enough to
                    // need warning -- the phone is across the room.
                    val left = _state.value.remaining
                    if (left in 1..3 && (state.step?.seconds ?: 0) >= COUNTDOWN_MIN_STEP_S) sessionCues?.speak("$left")
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

    /** Said out loud, because the phone is in a pocket or across the room. */
    private fun announce(step: GuidedStep) {
        cues?.announceStep(spokenStep(step))
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
                        // A session started by hand is on no plan.
                        plannedWorkoutId = workout.id.takeIf { !AdHocWorkouts.isAdHoc(it) },
                        exercises = completedExercises(),
                        sessionRpe = rpe,
                        notes = workout.title.takeIf { it.isNotBlank() },
                    ),
                    workoutId = null,
                    completedAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(),
                )
            }.onFailure { Log.w(TAG, "session not logged", it) }
            // A timed activity started by hand -- yoga, climbing -- is also an
            // activity, so it counts toward fitness like any other: a FIT file
            // with no track, in its sport, going in by the same door.
            if (AdHocWorkouts.isAdHoc(workout.id) && !isLoggedAsSession(workout.sport) && state.elapsedS > 0) {
                val (sport, sub) = RunFit.fitSport(workout.sport)
                val ms = state.elapsedS * 1000L
                RunFit.encodeTimed(System.currentTimeMillis() - ms, ms, ms, sport, sub)?.let { bytes ->
                    runCatching { container.files.add(bytes) }.onFailure { Log.w(TAG, "activity not stored", it) }
                    runCatching { container.uploadFiles() }
                }
            }
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
                    sport = RunFit.fitSport(workout.sport).first,
                    subSport = RunFit.fitSport(workout.sport).second,
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

    /** Strength and flexibility are logged as sessions, not as activities. */
    private fun isLoggedAsSession(sport: String?): Boolean =
        (sport ?: "").lowercase() in setOf("strength_training", "strength", "flexibility_training", "flexibility", "mobility")

    /** Tick the workout off — a one-field local edit that syncs by field. */
    private suspend fun markCompleteLocally(workout: PlannedWorkout) {
        if (AdHocWorkouts.isAdHoc(workout.id)) {
            _state.update { it.copy(workout = it.workout?.copy(isComplete = true)) }
            return
        }
        runCatching { container.sources.setValues("planned_workout", workout.id, mapOf("is_complete" to true)) }
        _state.update { it.copy(workout = it.workout?.copy(isComplete = true)) }
    }

    override fun onCleared() {
        timer?.cancel()
        clockJob?.cancel()
        sessionCues?.release()
        sessionCues = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "TracksGuided"

        /** A timed step this long or longer gets a spoken 3-2-1 before it ends. */
        const val COUNTDOWN_MIN_STEP_S = 10

        const val UPLOAD_PENDING =
            "Saved to this phone and showing in your activities. " +
                "It will upload to your server on the next sync."
    }
}
