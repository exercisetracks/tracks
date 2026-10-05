// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.ui.goals.GoalDraft
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.PlannedWorkoutCreate
import com.tracks.core.api.PlannedWorkoutUpdate
import com.tracks.core.api.TrainingGoal
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.local.LocalMetrics
import com.tracks.core.local.LocalPlanning
import com.tracks.core.local.LocalRacePlans
import com.tracks.core.local.RecentLoad
import com.tracks.core.local.str
import com.tracks.core.plan.PlanPhases
import com.tracks.core.plan.PlanStaleness
import com.tracks.core.race.RacePredictor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The Training page: goals, the plan built for the active one, and the
 * calendar of workouts — the web's /goals page, on a phone.
 *
 * Everything here reads and writes this phone's replica, so the page opens,
 * plans, reschedules and edits goals with no server at all. One thing is
 * still the server's and appears only when one is linked: the calendar
 * subscription link, which is a URL a calendar app on some other machine
 * polls. (The week-ahead form outlook from `/coaching/plan` used to show here
 * too; it was dropped on 2026-09-28 — a column of projected form numbers
 * beside suggestions that were not the plan read as a second, conflicting
 * plan.)
 */
data class PlanUiState(
    val loading: Boolean = true,
    val workouts: List<PlannedWorkout> = emptyList(),
    val goals: List<TrainingGoal> = emptyList(),
    /** The active event goal's Base/Build/Peak/Taper position, when it has one. */
    val phase: PlanPhases.Info? = null,
    /**
     * The race-time prediction on the goal card ("Predicted 33:42"): the
     * plan's VDOT through the ported race predictor. Running only — VDOT is a
     * running model, and the web shows it for runs alone.
     */
    val predicted: String? = null,
    /**
     * Gut-training carbs per hour for each long session before the active
     * race, by workout id — the ramp com.tracks.core.fuel.FuelPlan works out
     * from the race target and the logs, identically to the web.
     */
    val gutTargets: Map<Int, Int> = emptyMap(),
    val generating: Boolean = false,
    val linked: Boolean = false,
    /**
     * Today's fitness and the last six weeks' load by sport, for the goal
     * sheet's recommended event date. Read when the sheet opens (null until
     * then): it walks every activity, which the page itself never needs.
     */
    val eventLoad: RecentLoad? = null,
    /** Set once fetched, for the UI to copy to the clipboard. */
    val icsUrl: String? = null,
    val error: String? = null,
    /** A one-line confirmation ("Plan rebuilt — 84 workouts"). */
    val message: String? = null,
) {
    /** The one active goal (the contract's read rule has already picked it). */
    val activeGoal: TrainingGoal? get() = goals.firstOrNull { it.isActive }

    /**
     * The goal a plan is built for: the active goal when it is an event or a
     * fitness goal. A weekly-volume goal is a number to hit, not a plan.
     */
    val planGoal: TrainingGoal? get() = activeGoal?.takeIf { it.goalType == "event" || it.goalType == "fitness" }

    val byDate: Map<String, List<PlannedWorkout>> get() = workouts.groupBy { it.scheduledDate.take(10) }
}

class PlanViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private val planning by lazy { LocalPlanning(container.sources, container.library) }

    // Above `init` on purpose: load() starts there and reads both, and a
    // property declared below an init block is still unset when it runs.
    /** Goals this page has already built a missing plan for: once each, so a refusal cannot loop. */
    private val autoBuilt = mutableSetOf<String>()
    /** An edit made while a rebuild runs: rebuild once more when it finishes, rather than drop it. */
    private var rebuildAgain = false

    init {
        load()
        // A pull, a watch sync or another screen can change the plan; this
        // page lives for the process and would otherwise show breakfast's.
        viewModelScope.launch { container.localData.revision.drop(1).collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val workouts = runCatching {
                container.sources.plannedWorkouts(
                    today.minusDays(HORIZON_DAYS).toString(),
                    today.plusDays(HORIZON_DAYS).toString(),
                )
            }.getOrNull()
            val goals = runCatching { container.sources.goals() }.getOrNull()
            val active = goals?.firstOrNull { it.isActive }
            val phase = active?.let { runCatching { planning.phase(it, today.civil()) }.getOrNull() }
            val predicted = active?.let { runCatching { predicted(it) }.getOrNull() }
            val gut = active?.takeIf { it.goalType == "event" }?.let { goal ->
                runCatching {
                    val plans = LocalRacePlans(container.sources)
                    plans.fuel(goal, predictionFor(goal)).gut.mapKeys { (uid, _) -> container.sources.idOf(uid) }
                }.getOrNull()
            }.orEmpty()
            val linked = container.isLinked()
            val missingPlan = active?.takeIf { g ->
                g.uid != null && g.uid !in autoBuilt && planning.isPlannable(g, today.civil()) &&
                    container.sources.replica.rows("plan").none { it.str("goal_uid") == g.uid && !it.isTombstone }
            }
            _state.update {
                it.copy(
                    loading = false,
                    workouts = workouts ?: it.workouts,
                    goals = goals ?: it.goals,
                    phase = phase,
                    predicted = predicted,
                    gutTargets = gut,
                    linked = linked,
                )
            }
            missingPlan?.let { autoBuilt += it.uid!!; rebuild() }
        }
    }

    /** The recent training the goal sheet's recommended date is worked out from. */
    fun loadEventLoad() {
        viewModelScope.launch {
            val today = LocalDate.now().civil()
            val load = runCatching {
                val thresholds = container.sources.importThresholds()
                LocalMetrics(container.library, container.sources).recentLoad(today, thresholds.thresholdHr)
            }.getOrDefault(RecentLoad.NONE)
            _state.update { it.copy(eventLoad = load) }
        }
    }

    /** The race's predicted duration, which sets the default carb target the ramp climbs to. */
    private suspend fun predictionFor(goal: TrainingGoal): LocalRacePlans.Prediction? {
        if ((goal.eventSport ?: "running") != "running") return null
        val distance = goal.eventDistanceMeters?.takeIf { it > 0 } ?: return null
        val vdot = container.sources.replica.rows("plan")
            .firstOrNull { it.str("goal_uid") == goal.uid }
            ?.str("vdot")?.toDoubleOrNull() ?: return null
        return LocalRacePlans.Prediction(predictRunning(vdot, distance), emptyList())
    }

    /** A running race's finish time as the race plans screen predicts it —
     *  the marathon corrected by the last 8 weeks' volume. */
    private suspend fun predictRunning(vdot: Double, distance: Double): Double {
        val indices = runCatching {
            LocalRacePlans(container.sources).trainingIndices(LocalDate.now().civil())
        }.getOrNull()
        return RacePredictor.predictRunningRaceSec(vdot, distance, indices)
    }

    /** How a fuelled long session went; the ramp adapts from it on every device. */
    fun logFuel(workout: PlannedWorkout, carbsG: Double, comfort: Int, notes: String?) {
        viewModelScope.launch {
            val uid = container.sources.row("planned_workout", workout.id)?.uid
            LocalRacePlans(container.sources).logGut(
                uid, workout.scheduledDate, workout.durationMinutes, carbsG, comfort, notes?.ifBlank { null },
            )
            _state.update { it.copy(message = "Fuelling logged") }
            load()
        }
    }

    private suspend fun predicted(goal: TrainingGoal): String? {
        if (goal.goalType != "event" || (goal.eventSport ?: "running") != "running") return null
        val distance = goal.eventDistanceMeters?.takeIf { it > 0 } ?: return null
        val vdot = container.sources.replica.rows("plan")
            .firstOrNull { it.str("goal_uid") == goal.uid }
            ?.str("vdot")?.toDoubleOrNull() ?: return null
        return RacePredictor.formatTime(predictRunning(vdot, distance))
    }

    // ── Workouts ─────────────────────────────────────────────────────────────

    /** Tick a workout off, or un-tick it — one field, merged by field. */
    fun toggleComplete(workout: PlannedWorkout) {
        val target = !workout.isComplete
        _state.update { s -> s.copy(workouts = s.workouts.map { if (it.id == workout.id) it.copy(isComplete = target) else it }) }
        viewModelScope.launch {
            container.sources.setValues("planned_workout", workout.id, mapOf("is_complete" to target))
        }
    }

    /**
     * Move a workout to [date]. Shown moved at once; the write is one field,
     * so a rename made elsewhere meanwhile still merges in.
     */
    fun move(workout: PlannedWorkout, date: LocalDate) {
        if (workout.scheduledDate.take(10) == date.toString()) return
        _state.update { s ->
            s.copy(workouts = s.workouts.map { if (it.id == workout.id) it.copy(scheduledDate = date.toString()) else it })
        }
        viewModelScope.launch { planning.reschedule(workout.id, date.civil()) }
    }

    /**
     * The race dragged to [date]: the event goal's date moves, then the plan
     * is rebuilt around it — what changing the date in the goal sheet does.
     * No confirmation: the drag is the confirmation, and it is undone by
     * dragging back. A day that is not after today is refused, since a plan
     * cannot be built for a race that has happened (LocalPlanning.Refusal.Past).
     */
    fun moveRace(date: LocalDate, today: LocalDate = LocalDate.now()) {
        val goal = _state.value.activeGoal?.takeIf { it.goalType == "event" } ?: return
        if (!date.isAfter(today)) {
            _state.update { it.copy(error = "The race has to be after today.") }
            return
        }
        if (goal.eventDate == date.toString()) return
        _state.update { s ->
            s.copy(
                goals = s.goals.map { if (it.id == goal.id) it.copy(eventDate = date.toString()) else it },
                workouts = s.workouts.map { if (it.workoutType == "race") it.copy(scheduledDate = date.toString()) else it },
            )
        }
        editPlanGoal(mapOf("event_date" to date.toString()))
    }

    /**
     * Save a workout somebody wrote, new or edited. An edit writes only the
     * fields that changed, so it loses gracefully to a newer change made
     * elsewhere rather than winning because it arrived last.
     */
    fun saveWorkout(draft: WorkoutDraft) {
        viewModelScope.launch {
            val src = container.sources
            runCatching {
                val original = _state.value.workouts.firstOrNull { it.id == draft.id }
                when {
                    draft.isNew || original == null ->
                        src.create("planned_workout", draft.toCreate(), PlannedWorkoutCreate.serializer())
                    // Opened and closed unchanged: writing would stamp every
                    // field and beat a real change made elsewhere meanwhile.
                    draft.unchanged(original) -> Unit
                    else -> src.edit("planned_workout", original.id, draft.toUpdate(original), PlannedWorkoutUpdate.serializer())
                }
            }.onFailure {
                _state.update { s -> s.copy(error = "Could not save that workout.") }
                return@launch
            }
            load()
        }
    }

    /** Remove a workout from the calendar. A delete wins everywhere. */
    fun deleteWorkout(id: Int) {
        viewModelScope.launch {
            runCatching { container.sources.delete("planned_workout", id) }
            _state.update { s -> s.copy(workouts = s.workouts.filterNot { it.id == id }) }
        }
    }

    // ── The plan ─────────────────────────────────────────────────────────────

    /**
     * Build (or rebuild) the plan for the active event or fitness goal, on this phone.
     * It replaces the generated sessions and leaves anything written by hand,
     * and anything moved by hand, alone.
     *
     * Not a button any more: called by every change that leaves the plan
     * stale (PlanStaleness) — a new goal, an edit, an activation, a race
     * dragged — the phone's side of what the server does on the same edits.
     */
    private fun rebuild() {
        // A second edit while a build runs (days, then intensity, in quick
        // succession) must not be dropped — that plan would be built for the
        // first edit only. It is queued and runs once the first finishes,
        // from the goal as then stored.
        if (_state.value.generating) { rebuildAgain = true; return }
        val goal = _state.value.planGoal ?: return
        _state.update { it.copy(generating = true, error = null, message = null) }
        viewModelScope.launch {
            val result = runCatching {
                planning.regenerate(goal, LocalDate.now().civil(), System.currentTimeMillis()).getOrThrow()
            }
            _state.update { s ->
                result.fold(
                    { s.copy(generating = false) },
                    { e ->
                        s.copy(
                            generating = false,
                            error = (e as? LocalPlanning.Refused)?.refusal?.message ?: "Could not build a plan.",
                        )
                    },
                )
            }
            load()
            if (rebuildAgain) {
                rebuildAgain = false
                generatePlanAfterLoad()
            }
        }
    }


    /** Days per week, as the web's selector: saved to the goal, then the plan rebuilt to match. */
    fun setDaysPerWeek(days: Int) = editPlanGoal(mapOf("days_per_week" to days))

    /** Plan intensity (0.5–1.5): saved, then the plan rebuilt. Called when the slider is let go. */
    fun setIntensity(value: Double) = editPlanGoal(mapOf("plan_intensity" to value))

    private fun editPlanGoal(values: Map<String, Any?>) {
        val goal = _state.value.planGoal ?: return
        viewModelScope.launch {
            container.sources.setValues("goal", goal.id, values)
            load()
            generatePlanAfterLoad()
        }
    }

    private suspend fun generatePlanAfterLoad() {
        // The edit above changed the goal; rebuild from the goal as now stored.
        // Only a goal with a live plan: activating a race already run is not
        // an error to show, just a goal with nothing left to plan.
        val fresh = container.sources.goals()
            .firstOrNull { it.isActive && planning.isPlannable(it, LocalDate.now().civil()) } ?: return
        _state.update { it.copy(goals = it.goals.map { g -> if (g.id == fresh.id) fresh else g }) }
        rebuild()
    }

    /** The calendar feed URL, fetched from the server (it is the server a calendar app polls). */
    fun fetchIcsUrl() {
        viewModelScope.launch {
            val url = runCatching { container.client().userIcsToken().icsUrl }.getOrNull()
            _state.update {
                if (url != null) it.copy(icsUrl = url, message = "Subscription link copied.")
                else it.copy(error = "Could not reach the server for the subscription link.")
            }
        }
    }

    fun consumeIcsUrl() = _state.update { it.copy(icsUrl = null) }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    // ── Goals ────────────────────────────────────────────────────────────────

    /**
     * Make a goal the active one, and rebuild its plan: built whenever it
     * was last active, it plans from the fitness of then (and, for a race,
     * the wrong phase). The server does the same on the same edit.
     */
    fun activate(goal: TrainingGoal) {
        viewModelScope.launch {
            planning.activate(goal.id, _state.value.goals)
            load()
            generatePlanAfterLoad()
        }
    }

    /** Create or edit a goal from the goal form; a new goal becomes the active one, as on the web. */
    fun saveGoal(draft: GoalDraft) {
        viewModelScope.launch {
            val src = container.sources
            val id = draft.id
            val rebuild: Boolean
            if (id == null) {
                val newId = src.createValues("goal", draft.values() + ("is_active" to true))
                planning.activate(newId, src.goals())
                rebuild = true
            } else {
                val original = _state.value.goals.firstOrNull { it.id == id }
                val changed = draft.changedValues(original)
                if (changed.isNotEmpty()) src.setValues("goal", id, changed)
                // Anything the plan reads — days, intensity, strength, the
                // date, the sport, the ramp — rebuilds it, as the server does
                // for the same edit; a renamed race or a new note does not.
                rebuild = original?.isActive == true && PlanStaleness.goalEditStalesPlan(changed)
            }
            load()
            if (rebuild) generatePlanAfterLoad()
        }
    }

    fun deleteGoal(goal: TrainingGoal) {
        viewModelScope.launch {
            planning.deleteGoal(goal, System.currentTimeMillis())
            load()
        }
    }

    private companion object {
        /** A year either side of today — as far as the calendar can page. */
        const val HORIZON_DAYS = 365L
    }
}

internal fun LocalDate.civil(): CivilDate = CivilDate(year, monthValue, dayOfMonth)
