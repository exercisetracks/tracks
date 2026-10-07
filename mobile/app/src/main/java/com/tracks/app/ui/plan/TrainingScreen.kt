// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tracks.app.ui.tour.TourAnchor
import com.tracks.app.ui.tour.tourAnchor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.OptionGrid
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.components.dayLabel
import com.tracks.app.ui.components.shortDay
import com.tracks.app.ui.goals.GoalDraft
import com.tracks.app.ui.goals.GoalEditorSheet
import com.tracks.app.ui.goals.GoalOptions
import com.tracks.app.ui.goals.rampColor
import com.tracks.app.ui.goals.IntensitySlider
import com.tracks.app.ui.goals.intensityColor
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.TrainingGoal
import com.tracks.core.format.distance
import com.tracks.core.plan.PlanPhases
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The Training page — goals, the active goal's plan, and its calendar.
 *
 * ## Why a week agenda first, and a month second
 *
 * The web shows a month grid with a titled chip per workout, which works at
 * 1440 px and does not at 400: a month of seven narrow columns leaves each
 * chip three characters wide, which is why the phone used to draw dots. A
 * week as rows keeps every chip's full title and the day's context readable,
 * and it is the question a phone is opened to answer ("what am I doing this
 * week?"). The month is one tap away for the overview, with truncated chips
 * and the tapped day expanded below it.
 *
 * ## Moving a workout
 *
 * Long-press a chip and drag it to another day (ScheduleDrag.kt): the long
 * press is what keeps a plain swipe scrolling the page. Held and let go
 * without moving, it opens a sheet of the surrounding weeks instead, for the
 * week a workout moves to that is not on screen. The race drags the same way
 * and moves the goal's date.
 */
@Composable
fun TrainingScreen(
    vm: PlanViewModel,
    onOpenWorkout: (Int) -> Unit,
    onSyncWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(state.icsUrl) {
        state.icsUrl?.let { clipboard.setText(AnnotatedString(it)); vm.consumeIcsUrl() }
    }
    TrainingContent(
        state = state,
        actions = TrainingActions(
            onOpenWorkout = onOpenWorkout,
            onToggle = vm::toggleComplete,
            onLogFuel = vm::logFuel,
            onMove = vm::move,
            onMoveRace = { vm.moveRace(it) },
            onSaveWorkout = vm::saveWorkout,
            onDeleteWorkout = vm::deleteWorkout,
            onGoalSheet = vm::loadEventLoad,
            onDaysPerWeek = vm::setDaysPerWeek,
            onIntensity = vm::setIntensity,
            onSyncWatch = onSyncWatch,
            onCopyLink = vm::fetchIcsUrl,
            onActivate = vm::activate,
            onSaveGoal = vm::saveGoal,
            onDeleteGoal = vm::deleteGoal,
            onDismissMessage = vm::dismissMessage,
        ),
        modifier = modifier,
    )
}

/** Everything the page can ask for — a plain holder so the content renders without a view model. */
data class TrainingActions(
    val onOpenWorkout: (Int) -> Unit = {},
    val onToggle: (PlannedWorkout) -> Unit = {},
    /** Carbs taken (g), GI comfort 1–5, notes. */
    val onLogFuel: (PlannedWorkout, Double, Int, String?) -> Unit = { _, _, _, _ -> },
    val onMove: (PlannedWorkout, LocalDate) -> Unit = { _, _ -> },
    /** The race dragged to another day: the goal's event date moves, and the plan with it. */
    val onMoveRace: (LocalDate) -> Unit = {},
    val onSaveWorkout: (WorkoutDraft) -> Unit = {},
    val onDeleteWorkout: (Int) -> Unit = {},
    /** The goal sheet opened: read the recent training its recommended date needs. */
    val onGoalSheet: () -> Unit = {},
    val onDaysPerWeek: (Int) -> Unit = {},
    val onIntensity: (Double) -> Unit = {},
    val onSyncWatch: () -> Unit = {},
    val onCopyLink: () -> Unit = {},
    val onActivate: (TrainingGoal) -> Unit = {},
    val onSaveGoal: (GoalDraft) -> Unit = {},
    val onDeleteGoal: (TrainingGoal) -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

private enum class PlanView { Week, Month }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingContent(
    state: PlanUiState,
    actions: TrainingActions,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    if (state.loading && state.workouts.isEmpty() && state.goals.isEmpty()) {
        Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    var view by rememberSaveable { mutableStateOf(PlanView.Week) }
    var weekOf by rememberSaveable { mutableStateOf(weekStart(today).toString()) }
    var month by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedDay by rememberSaveable { mutableStateOf(today.toString()) }
    var editing by remember { mutableStateOf<WorkoutDraft?>(null) }
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var moving by remember { mutableStateOf<PlannedWorkout?>(null) }
    var editingGoal by remember { mutableStateOf<GoalDraft?>(null) }

    // Dragging a workout (or the race) to another day. The race is the event
    // goal's own day, so dropping it moves the goal's date — never into the
    // past, where there is nothing left to plan for.
    val drag = rememberScheduleDrag()
    val raceGoal = state.activeGoal?.takeIf { it.goalType == "event" }
    fun isRace(w: PlannedWorkout) = raceGoal != null && w.workoutType == "race"
    drag.accepts = { w, day -> !isRace(w) || day.isAfter(today) }
    val onDrop: (ScheduleDrag.Drop) -> Unit = { d ->
        val race = isRace(d.workout)
        when {
            race && (d.day ?: d.refused) != null -> actions.onMoveRace(d.day ?: d.refused!!)
            d.day != null -> actions.onMove(d.workout, d.day)
            // Held without moving: the sheet, which reaches weeks off screen.
            d.held && !race -> moving = d.workout
        }
    }
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s3),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
    ) {
        state.message?.let { Notice(it, isError = false, onDismiss = actions.onDismissMessage) }
        state.error?.let { Notice(it, isError = true, onDismiss = actions.onDismissMessage) }

        val active = state.activeGoal
        TourAnchor("goals-active") {
            when {
                active == null -> NoGoalCard(onNew = { editingGoal = GoalDraft() })
                active.goalType == "event" -> EventGoalCard(
                    goal = active,
                    phase = state.phase,
                    predicted = state.predicted,
                    generating = state.generating,
                    actions = actions,
                    onEdit = { editingGoal = GoalDraft.of(active) },
                    today = today,
                )
                active.goalType == "fitness" -> FitnessGoalCard(
                    goal = active,
                    actions = actions,
                    onEdit = { editingGoal = GoalDraft.of(active) },
                )
                else -> TargetGoalCard(active, onEdit = { editingGoal = GoalDraft.of(active) })
            }
        }

        Row(Modifier.tourAnchor("plan-view"), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TRAINING PLAN",
                style = Tokens.SectionHeader.style,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            ViewToggle(view, onPick = { view = it })
        }

        // The plan builds itself — on a new goal, an edit, an activation, a
        // settings change — so there is nothing to press; while it builds,
        // say so rather than show an empty week as if that were the plan.
        if (state.generating) {
            Text(
                "Building the plan…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TourAnchor("plan-calendar") {
            when (view) {
                PlanView.Week -> WeekAgenda(
                    monday = LocalDate.parse(weekOf),
                    today = today,
                    workouts = state.workouts,
                    drag = drag,
                    onDrop = onDrop,
                    onWeek = { weekOf = it.toString() },
                    onOpenDay = { openDay = it },
                    onAdd = { editing = WorkoutDraft.blank(it.toString()) },
                )
                PlanView.Month -> MonthChips(
                    month = YearMonth.parse(month),
                    today = today,
                    selected = LocalDate.parse(selectedDay),
                    byDate = state.byDate,
                    drag = drag,
                    onDrop = onDrop,
                    onMonth = { month = it.toString() },
                    onSelect = { selectedDay = it.toString() },
                ) {
                    DayDetails(
                        workouts = state.byDate[selectedDay].orEmpty(),
                        onToggle = actions.onToggle,
                        gutTargets = state.gutTargets,
                        onLogFuel = actions.onLogFuel,
                        onOpen = actions.onOpenWorkout,
                        onEdit = { editing = WorkoutDraft.of(it) },
                        onAdd = { editing = WorkoutDraft.blank(selectedDay) },
                    )
                }
            }
        }

        // Below the plan: the page opens on this week, and the other goals
        // are what is looked at least.
        TourAnchor("goals-list") {
            GoalsList(
                goals = state.goals,
                onNew = { editingGoal = GoalDraft() },
                onEdit = { editingGoal = GoalDraft.of(it) },
                onActivate = actions.onActivate,
            )
        }

        Spacer(Modifier.height(Tokens.Space.s4))
    }
    DragOverlay(drag, origin)
    }

    openDay?.let { day ->
        ModalBottomSheet(onDismissRequest = { openDay = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = Tokens.Space.s4).padding(bottom = Tokens.Space.s6)) {
                DayCard(
                    date = day,
                    workouts = state.byDate[day.toString()].orEmpty(),
                    onToggle = actions.onToggle,
                    gutTargets = state.gutTargets,
                    onLogFuel = actions.onLogFuel,
                    onOpen = { openDay = null; actions.onOpenWorkout(it) },
                    onEdit = { openDay = null; editing = WorkoutDraft.of(it) },
                    onAdd = { openDay = null; editing = WorkoutDraft.blank(day.toString()) },
                )
            }
        }
    }
    moving?.let { w ->
        MoveSheet(
            workout = w,
            today = today,
            onPick = { actions.onMove(w, it); moving = null },
            onDismiss = { moving = null },
        )
    }
    editing?.let { draft ->
        WorkoutEditorSheet(
            initial = draft,
            onSave = { actions.onSaveWorkout(it); editing = null },
            onDelete = draft.id?.let { id -> { actions.onDeleteWorkout(id); editing = null } },
            onDismiss = { editing = null },
        )
    }
    editingGoal?.let { draft ->
        LaunchedEffect(Unit) { actions.onGoalSheet() }
        GoalEditorSheet(
            initial = draft,
            onSave = { actions.onSaveGoal(it); editingGoal = null },
            onDelete = draft.id?.let { id ->
                { state.goals.firstOrNull { it.id == id }?.let(actions.onDeleteGoal); editingGoal = null }
            },
            onDismiss = { editingGoal = null },
            eventLoad = state.eventLoad,
            // The feed is a URL on the server that a calendar app polls; with
            // no server there is nothing to subscribe to, so there is no button.
            onCopyLink = actions.onCopyLink.takeIf { state.linked },
            today = today,
        )
    }
}

// ── The goal ─────────────────────────────────────────────────────────────────

@Composable
private fun PlanCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Card.radius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Tokens.Card.padding),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
    ) { content() }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = Tokens.Pill.style,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(Tokens.Pill.radius))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = Tokens.Pill.paddingX, vertical = Tokens.Pill.paddingY),
    )
}

@Composable
private fun Label(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun NoGoalCard(onNew: () -> Unit) = PlanCard {
    Text("No goal yet", style = MaterialTheme.typography.titleMedium)
    Text(
        "Set a goal and Tracks builds your training around it — a race on a date, a fitness " +
            "target, a weekly volume, or keeping what you have.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryButton("New goal", onClick = onNew, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventGoalCard(
    goal: TrainingGoal,
    phase: PlanPhases.Info?,
    predicted: String?,
    generating: Boolean,
    actions: TrainingActions,
    onEdit: () -> Unit,
    today: LocalDate,
) = PlanCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                Text(goal.planName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Pill("Active", MaterialTheme.colorScheme.primary)
            }
            Text(
                listOfNotNull(
                    goal.eventDistanceMeters?.let { distance(it) },
                    GoalOptions.SPORTS[goal.eventSport] ?: goal.eventSport,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TonalButton("Edit", onClick = onEdit)
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        goal.eventDate?.let { date ->
            Text(dayLabel(date, today), style = MaterialTheme.typography.bodyMedium)
            val days = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(date)) }.getOrNull()
            if (days != null && days >= 0) Pill(if (days == 1L) "1 day" else "$days days", Color(0xFFD97706))
        }
        predicted?.let { Pill("Predicted $it", Color(0xFF2563EB)) }
    }

    Label("Workout days per week")
    val days = goal.daysPerWeek ?: 5
    OptionGrid(
        (2..7).map { it to "$it" },
        isSelected = { it == days },
        onPick = { if (it != days) actions.onDaysPerWeek(it) },
        enabled = !generating,
    )

    // The slider moves freely and only writes on release: every write
    // rebuilds the plan, and a drag would otherwise rebuild it forty times.
    var intensity by remember(goal.planIntensity) { mutableFloatStateOf((goal.planIntensity ?: 1.0).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Intensity")
        Spacer(Modifier.width(Tokens.Space.s2))
        Text(
            "${GoalOptions.intensityLabel(intensity.toDouble())} (${"%.2f".format(intensity)}×)",
            style = MaterialTheme.typography.labelMedium,
            color = intensityColor(intensity.toDouble()),
        )
        Spacer(Modifier.width(Tokens.Space.s1_5))
        InfoTip(Explain.PlanIntensity)
    }
    IntensitySlider(
        value = intensity,
        onValueChange = { intensity = it },
        onValueChangeFinished = { actions.onIntensity(intensity.toDouble()) },
        enabled = !generating,
    )

    phase?.let { PhaseTimeline(it) }

    // No Regenerate: every change that would leave the plan stale rebuilds
    // it (PlanStaleness). The subscription link lives in the goal sheet.
    if (com.tracks.app.ui.components.LocalHasDevice.current) {
        ButtonRow {
            TonalButton("Sync to watch", onClick = actions.onSyncWatch)
        }
    }
}

/** The web's phase colours: base in the accent, then blue, amber, red. */
@Composable
private fun phaseColor(p: PlanPhases.Phase): Color = when (p) {
    PlanPhases.Phase.BASE -> MaterialTheme.colorScheme.primary
    PlanPhases.Phase.BUILD -> Color(0xFF3B82F6)
    PlanPhases.Phase.PEAK -> Color(0xFFF59E0B)
    PlanPhases.Phase.TAPER -> Color(0xFFEF4444)
}

@Composable
private fun PhaseTimeline(info: PlanPhases.Info) {
    Label("Training phase")
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlanPhases.Phase.entries.forEachIndexed { i, p ->
            val on = p == info.current
            val passed = p.ordinal < info.current.ordinal
            val color = phaseColor(p)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(if (on) 14.dp else 10.dp)
                        .clip(CircleShape)
                        .background(if (on || passed) color else MaterialTheme.colorScheme.surface)
                        .border(1.5.dp, if (on || passed) color else MaterialTheme.colorScheme.outline, CircleShape),
                )
                Text(
                    p.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${info.weeks[p] ?: 0} wk",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            if (i < PlanPhases.Phase.entries.size - 1) {
                Box(
                    Modifier
                        .weight(1f)
                        .padding(bottom = 30.dp)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                )
            }
        }
    }
    Text(
        "${info.current.label}: ${info.current.description}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The active fitness goal: its sport and ramp in one line, and the plan's
 * buttons. Ramp and days are changed in the goal sheet, where the slider and
 * its "?" live — one place to set them rather than two.
 */
@Composable
private fun FitnessGoalCard(goal: TrainingGoal, actions: TrainingActions, onEdit: () -> Unit) = PlanCard {
    val ramp = goal.ctlRampPerWeek ?: 0.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text("Fitness", style = MaterialTheme.typography.titleMedium)
                Pill("Active", MaterialTheme.colorScheme.primary)
            }
            Text(
                listOfNotNull(GoalOptions.SPORTS[goal.eventSport] ?: goal.eventSport, goal.daysPerWeek?.let { "$it days / week" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TonalButton("Edit", onClick = onEdit)
    }
    Text(
        "${GoalOptions.rampLabel(ramp)} · ${GoalOptions.rampWord(ramp)}",
        style = MaterialTheme.typography.labelLarge,
        color = rampColor(ramp),
    )
    if (com.tracks.app.ui.components.LocalHasDevice.current) {
        ButtonRow {
            TonalButton("Sync to watch", onClick = actions.onSyncWatch)
        }
    }
}

@Composable
private fun TargetGoalCard(goal: TrainingGoal, onEdit: () -> Unit) = PlanCard {
    val (title, line) = when (goal.goalType) {
        "volume_target" -> "Weekly Volume" to
            "${goal.targetWeeklyKm?.let { distance(it * 1000) } ?: "—"} a week · ${GoalOptions.SPORTS[goal.volumeSport] ?: goal.volumeSport}"
        else -> goal.planName to ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Pill("Active", MaterialTheme.colorScheme.primary)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TonalButton("Edit", onClick = onEdit, small = true)
    }
    Text(
        "Coaching steers your daily recommendations toward this target. Training plans are built for Race / Event and Fitness goals.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GoalsList(
    goals: List<TrainingGoal>,
    onNew: () -> Unit,
    onEdit: (TrainingGoal) -> Unit,
    onActivate: (TrainingGoal) -> Unit,
) {
    // With no goals at all the card above already offers "New goal".
    if (goals.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("GOALS", style = Tokens.SectionHeader.style, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        TonalButton("New goal", onClick = onNew, icon = Icons.Default.Add)
    }
    val others = goals.filterNot { it.isActive }.sortedBy { it.planEnd ?: "9999" }
    if (others.isEmpty()) return
    PlanCard {
        others.forEachIndexed { i, g ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(g) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(g.planName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            // The type, unless the goal's name already is it ("Maintain").
                            GoalOptions.TYPES.firstOrNull { it.first == g.goalType }?.second
                                ?.takeUnless { it.equals(g.planName, ignoreCase = true) },
                            g.planEnd?.let { shortDay(it) },
                        ).joinToString(" · ").ifEmpty { "No date" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TonalButton("Activate", onClick = { onActivate(g) }, small = true)
            }
        }
    }
}

// ── The week ─────────────────────────────────────────────────────────────────

private val RANGE = DateTimeFormatter.ofPattern("d MMM")

/**
 * The Week / Month switch beside the section title: two small pills rather
 * than Material's 40 dp segmented row, which made the header the tallest
 * thing on the line and pushed the calendar down for no information.
 */
@Composable
private fun ViewToggle(view: PlanView, onPick: (PlanView) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp),
    ) {
        PlanView.entries.forEach { v ->
            val on = v == view
            Text(
                v.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onPick(v) })
                    .padding(horizontal = Tokens.Space.s3, vertical = Tokens.Space.s1),
            )
        }
    }
}

/**
 * The period a calendar card shows, with its arrows: the first line of both
 * the week and the month card, so the two views read as the same section.
 */
@Composable
private fun PeriodHeader(
    title: String,
    what: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous $what") }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(horizontal = Tokens.Space.s1),
        )
        extra()
        IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.KeyboardArrowRight, "Next $what") }
    }
}

/** The tint a day takes while a dragged workout is over it. */
@Composable
private fun dropTint(drag: ScheduleDrag, day: LocalDate): Color =
    if (drag.hover == day && drag.item?.scheduledDate?.take(10) != day.toString()) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else Color.Transparent

@Composable
private fun WeekAgenda(
    monday: LocalDate,
    today: LocalDate,
    workouts: List<PlannedWorkout>,
    drag: ScheduleDrag,
    onDrop: (ScheduleDrag.Drop) -> Unit,
    onWeek: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    val days = remember(monday, workouts) { agendaWeek(monday, workouts) }
    // The week's selector sits inside the card, as the month's does: one
    // section, not a floating row of arrows over a card.
    PlanCard {
        PeriodHeader(
            "${monday.format(RANGE)} – ${monday.plusDays(6).format(RANGE)}",
            what = "week",
            onPrev = { onWeek(monday.minusWeeks(1)) },
            onNext = { onWeek(monday.plusWeeks(1)) },
        ) {
            if (weekStart(today) != monday) TonalButton("Today", onClick = { onWeek(weekStart(today)) }, small = true)
        }
        // The days in a column of their own with half the card's spacing:
        // spaced by the card's, each divider took a gap either side and the
        // week ran a screen and a half.
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        days.forEach { day ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .dropTarget(drag, day.date)
                    .clip(RoundedCornerShape(Tokens.Radius.md))
                    .background(dropTint(drag, day.date)),
                verticalAlignment = Alignment.Top,
            ) {
                DayBadge(day.date, isToday = day.date == today, modifier = Modifier.clickable { onOpenDay(day.date) })
                Spacer(Modifier.width(Tokens.Space.s2))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1)) {
                    if (day.workouts.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Rest",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.weight(1f),
                            )
                            TonalButton("Add", onClick = { onAdd(day.date) }, icon = Icons.Default.Add, small = true)
                        }
                    }
                    day.workouts.forEach { w ->
                        WorkoutChip(
                            w,
                            full = true,
                            modifier = Modifier
                                // Closes up while it is dragged over another day.
                                .collapseWhileAway(drag, w)
                                .clickable(onClickLabel = "Open day") { onOpenDay(day.date) }
                                .draggableWorkout(drag, w, full = true, onDrop = onDrop),
                        )
                    }
                    // Opens while a workout is dragged over this day: the days
                    // below slide down to make room for it (ScheduleDrag).
                    DropGap(drag, day.date)
                }
            }
        }
        }
    }
}

private val WEEKDAY = DateTimeFormatter.ofPattern("EEE")

@Composable
private fun DayBadge(date: LocalDate, isToday: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            date.format(WEEKDAY),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.titleSmall,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * A workout as the web draws it: a tinted chip in its type's colour with the
 * title. [full] adds the duration/distance line the agenda has room for.
 */
@Composable
internal fun WorkoutChip(workout: PlannedWorkout, full: Boolean, modifier: Modifier = Modifier) {
    // From the theme actually applied, not the system setting: the user's
    // theme mode can override the system either way.
    val dark = MaterialTheme.colorScheme.surface.luminanceBelowHalf()
    val tone = workoutTone(workout.workoutType, workout.sport, dark, MaterialTheme.colorScheme.surfaceVariant)
    val shape = RoundedCornerShape(if (full) Tokens.Radius.lg else Tokens.Chip.radius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.fill)
            .border(1.dp, tone.border, shape)
            .padding(horizontal = if (full) Tokens.Space.s2_5 else Tokens.Space.s1, vertical = if (full) Tokens.Space.s1 else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                workout.title.ifBlank { workout.workoutType },
                style = if (full) MaterialTheme.typography.bodyMedium else Tokens.Pill.style,
                color = tone.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (full) {
                val meta = listOfNotNull(
                    workout.durationMinutes?.let { "$it min" },
                    workout.distanceMeters?.takeIf { it > 0 }?.let { distance(it) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = tone.text.copy(alpha = 0.8f))
                }
            }
        }
        if (workout.isComplete) {
            Icon(Icons.Filled.Check, "Done", tint = tone.text, modifier = Modifier.size(if (full) 18.dp else 10.dp))
        }
    }
}

/** The strong colour of a workout's tone, for a dot or a stripe. */
@Composable
internal fun workoutDotColor(workout: PlannedWorkout): Color =
    workoutTone(
        workout.workoutType,
        workout.sport,
        MaterialTheme.colorScheme.surface.luminanceBelowHalf(),
        MaterialTheme.colorScheme.surfaceVariant,
    ).dot

private fun Color.luminanceBelowHalf(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

// ── The month ────────────────────────────────────────────────────────────────

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * The month grid, with the selected day's workouts under it in the same card —
 * [dayDetails] — so the details read as that day's and not as a separate panel.
 */
@Composable
private fun MonthChips(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    byDate: Map<String, List<PlannedWorkout>>,
    drag: ScheduleDrag,
    onDrop: (ScheduleDrag.Drop) -> Unit,
    onMonth: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
    dayDetails: @Composable () -> Unit,
) {
    PlanCard {
        PeriodHeader(
            month.format(MONTH_TITLE),
            what = "month",
            onPrev = { onMonth(month.minusMonths(1)) },
            onNext = { onMonth(month.plusMonths(1)) },
        )
        Row {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 2.dp))
            }
        }
        weeksOf(month).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { date ->
                    val inMonth = date.month == month.month
                    val list = byDate[date.toString()].orEmpty()
                    val isSel = date == selected
                    val tint = dropTint(drag, date)
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .dropTarget(drag, date)
                            .clip(RoundedCornerShape(Tokens.Radius.md))
                            .background(
                                when {
                                    tint != Color.Transparent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    isSel -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else -> Color.Transparent
                                },
                            )
                            .clickable { onSelect(date) }
                            .padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                date == today -> MaterialTheme.colorScheme.primary
                                inMonth -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        )
                        list.take(2).forEach {
                            WorkoutChip(it, full = false, modifier = Modifier.draggableWorkout(drag, it, full = false, onDrop = onDrop))
                        }
                        if (list.size > 2) {
                            Text("+${list.size - 2}", style = Tokens.Pill.style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        // A gap, not a divider: the details belong to the grid's selection,
        // and a rule across the card would cut them off from it again.
        Spacer(Modifier.height(Tokens.Space.s2))
        Text(dayLabel(selected.toString(), today), style = MaterialTheme.typography.titleSmall)
        dayDetails()
    }
}

// ── Moving ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoveSheet(workout: PlannedWorkout, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val from = runCatching { LocalDate.parse(workout.scheduledDate.take(10)) }.getOrDefault(today)
    // The week before, its own week, and the two after: where a session
    // actually moves to, in one tap.
    val start = weekStart(from).minusWeeks(1)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = Tokens.Space.s4).padding(bottom = Tokens.Space.s6),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3),
        ) {
            Text("Move “${workout.title}”", style = MaterialTheme.typography.titleMedium)
            Text("Now ${dayLabel(workout.scheduledDate, today)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            (0L until 4L).forEach { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1)) {
                    (0L until 7L).forEach { d ->
                        val date = start.plusWeeks(w).plusDays(d)
                        val current = date == from
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Tokens.Radius.md))
                                .background(
                                    when {
                                        current -> MaterialTheme.colorScheme.primary
                                        date == today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable(enabled = !current) { onPick(date) }
                                .padding(vertical = Tokens.Space.s1_5),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val fg = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            Text(date.format(WEEKDAY).take(2), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                            Text("${date.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = fg)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = Tokens.Space.s3, vertical = Tokens.Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
        NeutralButton("OK", onClick = onDismiss)
    }
}
