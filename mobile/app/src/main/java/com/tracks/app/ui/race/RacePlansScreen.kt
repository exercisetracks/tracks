// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.components.dayLabel
import com.tracks.app.ui.goals.GoalOptions
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.TrainingGoal
import com.tracks.core.format.Units
import com.tracks.core.format.distance
import com.tracks.core.local.LocalRacePlans
import com.tracks.core.local.str
import com.tracks.core.race.RacePredictor
import com.tracks.core.spec.computeFuelingParams
import com.tracks.core.spec.defaultCarbsPerHour
import com.tracks.app.ui.plan.civil
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Race Plans — the web's /race-plans list and /race-plans/:id page.
 *
 * One card per upcoming event goal; opening one shows its race-day strategy
 * and the pacing it implies. Strategy edits are synced `race_plan` fields;
 * the prediction and lap paces are worked out on the phone every time
 * ([LocalRacePlans]), with a GPX course read on the phone too. What it
 * cannot do yet — fetch race-day weather, build the race's watch file — is
 * said on the page rather than hidden.
 */
data class RacePlanCard(
    val goal: TrainingGoal,
    val strategy: LocalRacePlans.Strategy,
    val vdot: Double?,
    val prediction: LocalRacePlans.Prediction?,
    val fuel: LocalRacePlans.Fuel? = null,
)

data class RacePlansState(
    val loading: Boolean = true,
    val cards: List<RacePlanCard> = emptyList(),
    val openGoalId: Int? = null,
    val maxHr: Int? = null,
    /** Why the last GPX import produced no course, until the next attempt. */
    val courseError: String? = null,
    /** Saved map tracks that can become the course: uid to name. */
    val tracks: List<Pair<String, String>> = emptyList(),
    /** Drawing a course needs the map, and the map needs a server. */
    val linked: Boolean = false,
) {
    val open: RacePlanCard? get() = cards.firstOrNull { it.goal.id == openGoalId }
}

class RacePlansViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(RacePlansState())
    val state: StateFlow<RacePlansState> = _state.asStateFlow()
    private val plans by lazy { LocalRacePlans(container.sources) }

    init {
        load()
        viewModelScope.launch { container.localData.revision.drop(1).collect { load() } }
    }

    fun load() {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val maxHr = runCatching { plans.maxHr() }.getOrNull()
            // The marathon's volume correction reads the last 8 weeks of runs.
            val indices = runCatching { plans.trainingIndices(LocalDate.now().civil()) }.getOrNull()
            val cards = runCatching {
                container.sources.goals()
                    .filter { it.goalType == "event" && (it.eventDate ?: "") >= today }
                    .sortedBy { it.eventDate }
                    .map { g ->
                        val s = plans.strategy(g)
                        val vdot = plans.vdot(g)
                        val dist = g.eventDistanceMeters?.takeIf { it > 0 }
                        val running = (g.eventSport ?: "running") == "running"
                        val pred = if (running && vdot != null && dist != null) {
                            LocalRacePlans.running(vdot, dist, s.courseType, s.splitSpread, Units.imperial,
                                maxHr.takeIf { s.paceHrMode == "pace_hr" }, s.segments, s.useGpxDistance, indices)
                        } else null
                        RacePlanCard(g, s, vdot, pred, runCatching { plans.fuel(g, pred) }.getOrNull())
                    }
            }.getOrDefault(emptyList())
            val tracks = runCatching {
                container.sources.replica.rows("track").map { it.uid to (it.str("name") ?: "Track") }
                    .sortedBy { it.second.lowercase() }
            }.getOrDefault(emptyList())
            val linked = runCatching { container.isLinked() }.getOrDefault(false)
            _state.update { it.copy(loading = false, cards = cards, maxHr = maxHr, tracks = tracks, linked = linked) }
        }
    }

    fun open(goalId: Int?) = _state.update { it.copy(openGoalId = goalId) }

    fun set(goal: TrainingGoal, field: String, value: Any?) {
        viewModelScope.launch {
            plans.set(goal, field, value)
            load()
        }
    }

    /** Load a GPX course read from a file the user picked. Says so when it holds no track. */
    fun importCourse(goal: TrainingGoal, gpx: String?) {
        viewModelScope.launch {
            val ok = gpx != null && plans.importCourse(goal, gpx)
            _state.update { it.copy(courseError = if (ok) null else "That file has no track to use as a course.") }
            load()
        }
    }

    /** A saved map track as the course — the same parse as a GPX file. */
    fun useTrack(goal: TrainingGoal, trackUid: String) {
        viewModelScope.launch {
            val ok = plans.importTrack(goal, trackUid)
            _state.update { it.copy(courseError = if (ok) null else "That track has no usable points.") }
            load()
        }
    }

    /**
     * Open the map to draw this race's course. The map's next saved track
     * becomes the course (see MapToolsViewModel.saveTrack), then the app
     * returns here.
     */
    fun drawOnMap(goal: TrainingGoal) {
        container.pendingRaceCourse.value = goal.uid
        container.pendingRoute.value = com.tracks.app.ui.Destination.Map.route
    }

    fun addProduct(name: String, kind: String, carbsG: Double, sodiumMg: Int, caffeineMg: Int, fluidMl: Int, goal: TrainingGoal) {
        viewModelScope.launch {
            plans.addProduct(name, kind, carbsG, sodiumMg, caffeineMg, fluidMl)
            val uid = plans.products().lastOrNull { it.name == name }?.uid
            if (uid != null) plans.set(goal, "fuel_product_uids", plans.strategy(goal).fuelProductUids + uid)
            load()
        }
    }

    fun toggleProduct(goal: TrainingGoal, uid: String) {
        viewModelScope.launch {
            val current = plans.strategy(goal).fuelProductUids
            plans.set(goal, "fuel_product_uids", if (uid in current) current - uid else current + uid)
            load()
        }
    }

    fun removeCourse(goal: TrainingGoal) {
        viewModelScope.launch {
            plans.removeCourse(goal)
            load()
        }
    }
}

@Composable
fun RacePlansScreen(vm: RacePlansViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.open != null) { vm.open(null) }
    val context = LocalContext.current
    // Any file: GPX has no MIME type that file managers agree on, and a
    // filter on application/gpx+xml hides most people's downloads.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val goal = state.open?.goal ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        vm.importCourse(goal, text)
    }
    RacePlansContent(
        state, onOpen = vm::open, onSet = vm::set, modifier = modifier,
        onImportCourse = { pick.launch(arrayOf("*/*")) },
        onRemoveCourse = { goal -> vm.removeCourse(goal) },
        course = CourseActions(onUseTrack = vm::useTrack, onDrawOnMap = vm::drawOnMap),
        fuel = FuelActions(onAddProduct = vm::addProduct, onToggleProduct = vm::toggleProduct),
    )
}

/** The course's other two sources: a saved track, or one drawn now. */
data class CourseActions(
    val onUseTrack: (TrainingGoal, String) -> Unit = { _, _ -> },
    val onDrawOnMap: (TrainingGoal) -> Unit = {},
)

data class FuelActions(
    val onAddProduct: (String, String, Double, Int, Int, Int, TrainingGoal) -> Unit = { _, _, _, _, _, _, _ -> },
    val onToggleProduct: (TrainingGoal, String) -> Unit = { _, _ -> },
)

@Composable
fun RacePlansContent(
    state: RacePlansState,
    onOpen: (Int?) -> Unit,
    onSet: (TrainingGoal, String, Any?) -> Unit,
    modifier: Modifier = Modifier,
    onImportCourse: () -> Unit = {},
    onRemoveCourse: (TrainingGoal) -> Unit = {},
    course: CourseActions = CourseActions(),
    fuel: FuelActions = FuelActions(),
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tokens.Space.s4, vertical = Tokens.Space.s3),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4),
    ) {
        val open = state.open
        if (open == null) {
            com.tracks.app.ui.components.InfoHeading(
                "Upcoming events",
                com.tracks.app.ui.components.MetricInfo(
                    "Race plans",
                    "Predicted finish, lap targets and fuelling for each upcoming event.",
                    "Add a Race / Event goal on the Training page to plan one.",
                ),
            )
            if (!state.loading && state.cards.isEmpty()) {
                Card { Text("No upcoming events", style = MaterialTheme.typography.titleMedium) }
            }
            state.cards.forEach { c -> GoalRow(c) { onOpen(c.goal.id) } }
        } else {
            Detail(
                open, state.maxHr, onBack = { onOpen(null) }, onSet = { f, v -> onSet(open.goal, f, v) },
                courseError = state.courseError,
                onImportCourse = onImportCourse, onRemoveCourse = { onRemoveCourse(open.goal) },
                tracks = state.tracks, linked = state.linked,
                onUseTrack = { course.onUseTrack(open.goal, it) },
                onDrawOnMap = { course.onDrawOnMap(open.goal) },
                onAddProduct = { n, k, c, s, caf, f -> fuel.onAddProduct(n, k, c, s, caf, f, open.goal) },
                onToggleProduct = { fuel.onToggleProduct(open.goal, it) },
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Card.radius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Tokens.Card.padding),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2),
    ) { content() }
}

@Composable
private fun Badge(text: String, color: Color) = Text(
    text,
    style = Tokens.Pill.style,
    color = color,
    modifier = Modifier
        .clip(RoundedCornerShape(Tokens.Pill.radius))
        .background(color.copy(alpha = 0.15f))
        .padding(horizontal = Tokens.Pill.paddingX, vertical = Tokens.Pill.paddingY),
)

@Composable
private fun GoalRow(c: RacePlanCard, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.Card.radius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(Tokens.Card.padding),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            Text(c.goal.eventName ?: "Unnamed event", style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(GoalOptions.SPORTS[c.goal.eventSport] ?: c.goal.eventSport.orEmpty(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (com.tracks.app.ui.components.LocalHasDevice.current && c.strategy.watchUploadedAt != null) Badge("Synced to watch", MaterialTheme.colorScheme.primary)
        }
        Text(
            listOfNotNull(c.goal.eventDistanceMeters?.let { distance(it) }, c.goal.eventDate?.let { dayLabel(it) })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        c.prediction?.let {
            Badge("Predicted ${RacePredictor.formatTime(it.seconds)}", Color(0xFF2563EB))
        }
    }
}

private val COURSE = listOf(
    "flat" to "Flat", "rolling" to "Rolling", "hilly" to "Hilly", "mountainous" to "Mountainous",
)

@Composable
private fun Detail(
    c: RacePlanCard,
    maxHr: Int?,
    onBack: () -> Unit,
    onSet: (String, Any?) -> Unit,
    courseError: String? = null,
    onImportCourse: () -> Unit = {},
    onRemoveCourse: () -> Unit = {},
    tracks: List<Pair<String, String>> = emptyList(),
    linked: Boolean = false,
    onUseTrack: (String) -> Unit = {},
    onDrawOnMap: () -> Unit = {},
    onAddProduct: (String, String, Double, Int, Int, Int) -> Unit = { _, _, _, _, _, _ -> },
    onToggleProduct: (String) -> Unit = {},
) {
    NeutralButton("Race Plans", onClick = onBack, icon = Icons.Default.ArrowBack)
    Card {
        Text(c.goal.eventName ?: "Unnamed event", style = MaterialTheme.typography.titleLarge)
        Text(
            listOfNotNull(
                GoalOptions.SPORTS[c.goal.eventSport],
                c.goal.eventDistanceMeters?.let { distance(it) },
                c.goal.eventDate?.let { dayLabel(it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        c.prediction?.let {
            // No forecast on the phone: say nothing about one rather than
            // point at what is missing.
            Text("Predicted ${RacePredictor.formatTime(it.seconds)}", style = Tokens.Stat.value)
        } ?: Text(
            when {
                (c.goal.eventSport ?: "running") != "running" ->
                    "Pacing for this sport is on the web."
                c.goal.eventDistanceMeters == null -> "Add a distance to predict a finish."
                c.vdot == null -> "Build a training plan to predict a finish."
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Section("Course", com.tracks.app.ui.components.MetricInfo("Course", "A GPX course gives each lap its real hills. Without one, pick the terrain.")) {
        if (c.strategy.hasCourse) {
            val totals = remember(c.strategy.segments) {
                c.strategy.segments.takeIf { it.isNotEmpty() }?.let(RacePredictor::courseTotals)
            }
            Text(
                totals?.let { "GPX course · ${distance(it.distanceM.toDouble())} · +${it.elevationGainM} m" }
                    ?: "GPX course loaded.",
                style = MaterialTheme.typography.bodySmall,
            )
            ButtonRow {
                TonalButton("Replace course", onClick = onImportCourse)
                DangerButton("Remove course", onClick = onRemoveCourse)
            }
        } else {
            Choice(COURSE, c.strategy.courseType) { onSet("course_type", it) }
            TonalButton("Import a GPX course", onClick = onImportCourse, modifier = Modifier.fillMaxWidth())
        }
        // Drawing needs the map, and basemaps need a server; a track already
        // saved can be used either way.
        if (linked) {
            ButtonRow {
                TonalButton("Draw on map", onClick = onDrawOnMap)
                if (tracks.isNotEmpty()) SavedCourseMenu(tracks, onUseTrack)
            }
        } else if (tracks.isNotEmpty()) {
            SavedCourseMenu(tracks, onUseTrack)
        }
        courseError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }

    Section(
        "Pacing strategy",
        com.tracks.app.ui.components.MetricInfo(
            "Pacing strategy",
            "The slider sets how the second half compares to the first.",
            *listOfNotNull(
                "Targets are what the watch coaches you to on each lap."
                    .takeIf { com.tracks.app.ui.components.LocalHasDevice.current },
            ).toTypedArray(),
        ),
    ) {
        // Moves freely, writes on release: every write re-derives the laps.
        var spread by remember(c.strategy.splitSpread) { mutableFloatStateOf(c.strategy.splitSpread.toFloat()) }
        SplitSlider(
            spread = spread,
            totalSec = c.prediction?.seconds,
            onChange = { spread = it },
            onDone = { onSet("split_spread", snapSplit(spread)) },
        )
        // What the race's watch workout carries; nothing reads it without a watch.
        if (com.tracks.app.ui.components.LocalHasDevice.current) {
            Text("Garmin workout targets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Choice(listOf("pace" to "Pace only", "pace_hr" to "Pace + HR ceiling"), c.strategy.paceHrMode) {
                onSet("pace_hr_mode", it)
            }
            if (c.strategy.paceHrMode == "pace_hr" && maxHr == null) {
                Text("Set your max HR in Settings for HR ceilings.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    c.prediction?.let { p ->
        Section("Lap targets") {
            val unit = if (Units.imperial) "mi" else "km"
            Row {
                Head("Lap", Modifier.width(40.dp))
                Head("Pace /$unit", Modifier.weight(1f))
                Head("Distance", Modifier.weight(1f))
                if (p.laps.any { it.hrCeiling != null }) Head("HR ≤", Modifier.width(56.dp))
            }
            p.laps.forEach { lap ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("${lap.lap}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                    Text(
                        RacePredictor.formatTime(lap.targetSecPerKm * if (Units.imperial) 1.60934 else 1.0),
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        distance(lap.cumulativeKm * 1000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    lap.hrCeiling?.let {
                        Text("$it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                    }
                }
            }
        }
        c.fuel?.let { f ->
            Section("Fuelling") {
                FuelSection(c.strategy, f, onSet, onAddProduct, onToggleProduct)
            }
        }
    }
}

@Composable
private fun Head(text: String, modifier: Modifier) = Text(
    text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier,
)

@Composable
private fun Section(title: String, info: com.tracks.app.ui.components.MetricInfo? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            Text(title.uppercase(), style = Tokens.SectionHeader.style, color = MaterialTheme.colorScheme.primary)
            info?.let { com.tracks.app.ui.components.InfoTip(it) }
        }
        Card(content)
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Tokens.Radius.lg))
                    .border(
                        1.dp,
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        RoundedCornerShape(Tokens.Radius.lg),
                    )
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                    .clickable { onPick(key) }
                    .padding(vertical = Tokens.Space.s2, horizontal = Tokens.Space.s1),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
