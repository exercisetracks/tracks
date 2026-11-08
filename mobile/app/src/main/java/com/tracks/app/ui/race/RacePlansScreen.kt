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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tracks.app.ui.tour.TourAnchor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Race Plans — the web's /race-plans list and /race-plans/:id page.
 *
 * One card per upcoming event goal; opening one shows its race-day strategy
 * and the pacing it implies. Strategy edits are synced `race_plan` fields;
 * the prediction and lap paces are worked out on the phone every time
 * ([LocalRacePlans]), with a GPX course read on the phone too. The race's
 * watch workout is built here as well: it is the training calendar's race
 * day, sent with the calendar (com.tracks.core.sync.RaceDayWorkout). What it
 * cannot do yet — fetch race-day weather — it leaves out rather than guess.
 *
 * On race day a Start race button opens the guided race: the plan's laps,
 * paced and spoken as any guided run is (RaceGuide).
 */
data class RacePlanCard(
    val goal: TrainingGoal,
    val strategy: LocalRacePlans.Strategy,
    val vdot: Double?,
    val prediction: LocalRacePlans.Prediction?,
    val fuel: LocalRacePlans.Fuel? = null,
    /** Why there is no prediction: the threshold the sport needs (SportPlan.Missing). */
    val missing: String? = null,
    /**
     * Today is the race, and it is a run the phone can guide: the Start race
     * button shows, on the card and in the plan, and on no other day.
     */
    val raceDay: Boolean = false,
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
    /** The basemap the course preview draws on; null draws the shape alone (ActivityTrackMap). */
    val mapStyleJson: String? = null,
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
        // The course preview's basemap: the cached style at once, then a
        // refresh — as the activity screen does (ActivityDetailViewModel).
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { container.mapStyleCache().read() }
            if (cached != null) _state.update { it.copy(mapStyleJson = cached) }
            runCatching { container.client().mapStyleJson() }.onSuccess { fresh ->
                if (fresh == cached) return@onSuccess
                withContext(Dispatchers.IO) { container.mapStyleCache().write(fresh) }
                _state.update { it.copy(mapStyleJson = fresh) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val maxHr = runCatching { plans.maxHr() }.getOrNull()
            val cards = runCatching {
                container.sources.goals()
                    .filter { it.goalType == "event" && (it.eventDate ?: "") >= today }
                    .sortedBy { it.eventDate }
                    .map { g ->
                        val s = plans.strategy(g)
                        // Every sport, as the server plans it (SportPlan); a
                        // missing threshold says which one to set.
                        var missing: String? = null
                        val pred = try {
                            plans.plan(g, s, Units.imperial, LocalDate.now().civil())
                        } catch (e: com.tracks.core.race.SportPlan.Missing) {
                            missing = e.message
                            null
                        }
                        RacePlanCard(
                            g, s, pred?.projection?.vdotRaceDay, pred,
                            runCatching { plans.fuel(g, pred) }.getOrNull(), missing,
                            raceDay = isRaceDay(g, pred, today),
                        )
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

    /**
     * Race day's Start: the plan's own race session, so saving the run ticks
     * the race off the plan; with none (no plan, or one built before races
     * were scheduled), an unplanned race session on the event's day. Either
     * way the guided screen swaps in this plan's terrain legs, pace targets
     * and spoken cues (GuidedWorkoutViewModel.raceLegs, RaceGuide).
     */
    fun startRace(goal: TrainingGoal, onReady: (Int) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { plans.raceWorkoutId(goal) }.getOrNull() ?: run {
                val seconds = _state.value.cards.firstOrNull { it.goal.id == goal.id }?.prediction?.seconds
                com.tracks.app.ui.workout.AdHocWorkouts.add(
                    com.tracks.core.api.PlannedWorkout(
                        id = 0,
                        scheduledDate = goal.eventDate ?: LocalDate.now().toString(),
                        sport = goal.eventSport ?: "running",
                        workoutType = "race",
                        title = goal.eventName ?: "Race",
                        durationMinutes = seconds?.let { kotlin.math.round(it / 60).toInt() },
                        distanceMeters = goal.eventDistanceMeters,
                    ),
                )
            }
            onReady(id)
        }
    }

    fun set(goal: TrainingGoal, field: String, value: Any?) {
        viewModelScope.launch {
            plans.set(goal, field, value)
            load()
        }
    }

    /** Load a GPX course read from a file the user picked. Says so when it holds no track. */
    fun importCourse(goal: TrainingGoal, gpx: String?, leg: String? = null) {
        viewModelScope.launch {
            val ok = gpx != null && plans.importCourse(goal, gpx, leg)
            _state.update { it.copy(courseError = if (ok) null else "That file has no track to use as a course.") }
            load()
        }
    }

    /** A picked file that is not a GPX file. */
    fun rejectCourseFile() = _state.update { it.copy(courseError = "Pick a .gpx file.") }

    /** A saved map track as the course — the same parse as a GPX file. */
    fun useTrack(goal: TrainingGoal, trackUid: String, leg: String? = null) {
        viewModelScope.launch {
            val ok = plans.importTrack(goal, trackUid, leg)
            _state.update { it.copy(courseError = if (ok) null else "That track has no usable points.") }
            load()
        }
    }

    /**
     * Open the map to draw this race's course. The map's next saved track
     * becomes the course (see MapToolsViewModel.saveTrack), then the app
     * returns here. With [leg], the drawn track is that triathlon leg's own.
     */
    fun drawOnMap(goal: TrainingGoal, leg: String? = null) {
        container.pendingRaceLeg.value = leg
        container.pendingRaceCourse.value = goal.uid
        container.pendingRoute.value = com.tracks.app.ui.Destination.Map.route
    }

    fun addProduct(draft: LocalRacePlans.ProductDraft, goal: TrainingGoal) {
        viewModelScope.launch {
            plans.addProduct(draft)
            val uid = plans.products().lastOrNull { it.name == draft.name }?.uid
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

    fun removeCourse(goal: TrainingGoal, leg: String? = null) {
        viewModelScope.launch {
            plans.removeCourse(goal, leg)
            load()
        }
    }
}

@Composable
fun RacePlansScreen(vm: RacePlansViewModel, modifier: Modifier = Modifier, onStartRace: (Int) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.open != null) { vm.open(null) }
    val context = LocalContext.current
    // GPX has no MIME type file managers agree on: some say gpx+xml, some
    // plain XML, and many give a downloaded .gpx application/octet-stream.
    // Filtering on those types keeps PDFs and photos out of the picker while
    // every .gpx stays pickable; whatever still gets through is checked by
    // its name before it is read.
    // Which triathlon leg the picked file is for; null for the whole course.
    var pickLeg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val goal = state.open?.goal ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        if (!isGpxName(name)) {
            vm.rejectCourseFile()
            return@rememberLauncherForActivityResult
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        vm.importCourse(goal, text, pickLeg)
    }
    RacePlansContent(
        state, onOpen = vm::open, onSet = vm::set, modifier = modifier,
        onImportCourse = { leg -> pickLeg = leg; pick.launch(GPX_MIME_TYPES) },
        onRemoveCourse = { goal, leg -> vm.removeCourse(goal, leg) },
        course = CourseActions(onUseTrack = vm::useTrack, onDrawOnMap = vm::drawOnMap),
        fuel = FuelActions(onAddProduct = vm::addProduct, onToggleProduct = vm::toggleProduct),
        onStartRace = { goal -> vm.startRace(goal, onStartRace) },
    )
}

/** The types the course picker offers; see the launcher in [RacePlansScreen]. */
internal val GPX_MIME_TYPES = arrayOf(
    "application/gpx+xml", "application/xml", "text/xml", "application/octet-stream",
)

/**
 * Whether a picked file's name is a GPX file's. A name the provider will not
 * give is let through: the parse that follows refuses a file with no track.
 */
internal fun isGpxName(name: String?): Boolean = name == null || name.lowercase().endsWith(".gpx")

/**
 * The line under the race-day prediction (SportPlan.projection): the same
 * race at today's fitness and form, and what the race-day figure credits —
 * the web's header and its tooltip say the same. Null when there is nothing
 * to add: no projection, or race day already today.
 */
internal fun projectionLine(p: com.tracks.core.race.SportPlan.Projection?, raceTime: String): String? {
    if (p == null) return null
    val parts = mutableListOf<String>()
    p.todayTime?.takeIf { it != raceTime }?.let { parts += "Today $it" }
    val adherence = p.adherence
    if (adherence != null) {
        if (p.vdotRaceDay != p.vdotToday) parts += "VDOT ${p.vdotToday} → ${p.vdotRaceDay} by race day"
        parts += "${kotlin.math.round(adherence * 100).toInt()}% of the plan done"
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * Whether [goal] is raced today and the phone can guide it: a run (the
 * guided race paces running legs) with a plan to pace. Hidden every other
 * day — before, there is training to do; after, the race is history.
 */
internal fun isRaceDay(goal: TrainingGoal, prediction: LocalRacePlans.Prediction?, today: String): Boolean =
    goal.eventDate?.take(10) == today && prediction?.family == "running" && prediction.laps.isNotEmpty()

/** The course's other two sources: a saved track, or one drawn now. */
data class CourseActions(
    val onUseTrack: (TrainingGoal, String, String?) -> Unit = { _, _, _ -> },
    val onDrawOnMap: (TrainingGoal, String?) -> Unit = { _, _ -> },
)

data class FuelActions(
    val onAddProduct: (LocalRacePlans.ProductDraft, TrainingGoal) -> Unit = { _, _ -> },
    val onToggleProduct: (TrainingGoal, String) -> Unit = { _, _ -> },
)

@Composable
fun RacePlansContent(
    state: RacePlansState,
    onOpen: (Int?) -> Unit,
    onSet: (TrainingGoal, String, Any?) -> Unit,
    modifier: Modifier = Modifier,
    onImportCourse: (String?) -> Unit = {},
    onRemoveCourse: (TrainingGoal, String?) -> Unit = { _, _ -> },
    course: CourseActions = CourseActions(),
    fuel: FuelActions = FuelActions(),
    onStartRace: (TrainingGoal) -> Unit = {},
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
            TourAnchor("raceplans-intro") {
            com.tracks.app.ui.components.InfoHeading(
                "Upcoming events",
                com.tracks.app.ui.components.MetricInfo(
                    "Race plans",
                    "Predicted finish, lap targets and fuelling for each upcoming event.",
                    "Add a Race / Event goal on the Training page to plan one.",
                ),
            )
            }
            // The first event, or the empty card, is what the second tip points at.
            if (!state.loading && state.cards.isEmpty()) {
                TourAnchor("raceplans-card") {
                    Card { Text("No upcoming events", style = MaterialTheme.typography.titleMedium) }
                }
            }
            state.cards.forEachIndexed { i, c ->
                if (i == 0) TourAnchor("raceplans-card") { GoalRow(c, { onOpen(c.goal.id) }, { onStartRace(c.goal) }) }
                else GoalRow(c, { onOpen(c.goal.id) }, { onStartRace(c.goal) })
            }
        } else {
            Detail(
                open, state.maxHr, onSet = { f, v -> onSet(open.goal, f, v) },
                courseError = state.courseError,
                mapStyleJson = state.mapStyleJson,
                onImportCourse = onImportCourse, onRemoveCourse = { onRemoveCourse(open.goal, it) },
                tracks = state.tracks, linked = state.linked,
                onUseTrack = { uid, leg -> course.onUseTrack(open.goal, uid, leg) },
                onDrawOnMap = { course.onDrawOnMap(open.goal, it) },
                onAddProduct = { fuel.onAddProduct(it, open.goal) },
                onToggleProduct = { fuel.onToggleProduct(open.goal, it) },
                onStartRace = { onStartRace(open.goal) },
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
private fun GoalRow(c: RacePlanCard, onClick: () -> Unit, onStartRace: () -> Unit = {}) {
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
        if (c.raceDay) StartRaceButton(onStartRace)
    }
}

/** Race day's one action: run the race guided, with this plan's pacing and cues. */
@Composable
private fun StartRaceButton(onClick: () -> Unit) {
    com.tracks.app.ui.components.PrimaryButton(
        "Start race", onClick = onClick, modifier = Modifier.fillMaxWidth(),
        icon = Icons.Filled.PlayArrow,
    )
}

private val COURSE = listOf(
    "flat" to "Flat", "rolling" to "Rolling", "hilly" to "Hilly", "mountainous" to "Mountainous",
)

@Composable
private fun Detail(
    c: RacePlanCard,
    maxHr: Int?,
    onSet: (String, Any?) -> Unit,
    courseError: String? = null,
    mapStyleJson: String? = null,
    onImportCourse: (String?) -> Unit = {},
    onRemoveCourse: (String?) -> Unit = {},
    tracks: List<Pair<String, String>> = emptyList(),
    linked: Boolean = false,
    onUseTrack: (String, String?) -> Unit = { _, _ -> },
    onDrawOnMap: (String?) -> Unit = {},
    onAddProduct: (LocalRacePlans.ProductDraft) -> Unit = {},
    onToggleProduct: (String) -> Unit = {},
    onStartRace: () -> Unit = {},
) {
    if (c.raceDay) StartRaceButton(onStartRace)
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                Text("Predicted ${RacePredictor.formatTime(it.seconds)}", style = Tokens.Stat.value)
                if (it.projection != null) com.tracks.app.ui.components.InfoTip(com.tracks.app.ui.components.Explain.RacePrediction)
            }
            // Race day is the headline; today's fitness beside it shows how
            // much of it is still to be trained (SportPlan.projection).
            projectionLine(it.projection, RacePredictor.formatTime(it.seconds))?.let { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } ?: Text(
            c.missing ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // How hard to race against the prediction — every sport, the swim too.
    // Moves freely, writes on release: every write re-plans the race.
    Section("Race intensity", com.tracks.app.ui.components.Explain.RaceIntensity) {
        var intensity by remember(c.strategy.intensity) { mutableFloatStateOf(c.strategy.intensity.toFloat()) }
        Text(
            com.tracks.app.ui.goals.raceIntensityLabel(intensity.toDouble()),
            style = MaterialTheme.typography.labelMedium,
            color = com.tracks.app.ui.goals.raceIntensityColor(intensity.toDouble()),
        )
        com.tracks.app.ui.goals.RaceIntensitySlider(
            value = intensity,
            onValueChange = { intensity = it },
            onValueChangeFinished = { onSet("intensity", intensity.toDouble()) },
        )
    }

    Section("Course", com.tracks.app.ui.components.MetricInfo("Course", "A GPX course gives each lap its real hills. Without one, pick the terrain.")) {
        var choosing by remember { mutableStateOf(false) }
        // The leg a leg row opened the sheet for; null for the race's own button.
        var sheetLeg by remember { mutableStateOf<String?>(null) }
        // Where the profile is being read, marked on the course map.
        var held by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        val tri = com.tracks.core.plan.PlanBase.sportFamily((c.goal.eventSport ?: "running").lowercase()) == "triathlon"
        // Each leg's course — its own file, else its stretch of the whole (TriCourse).
        val triCourses = remember(c.strategy, tri) {
            if (!tri) null else com.tracks.core.race.TriCourse.legCourses(
                c.strategy.segments,
                c.strategy.path.zip(c.strategy.elevations) { (lat, lon), ele -> com.tracks.core.race.TriCourse.PathPoint(lat, lon, ele) },
                c.strategy.triLegFiles, c.strategy.triTransitionsM,
                c.strategy.triSplits ?: com.tracks.core.race.SportPlan.inferTriathlonSplits(c.goal.eventDistanceMeters ?: 51_500.0),
            )
        }
        if (tri && !c.strategy.hasCourse && c.strategy.triLegFiles.isNotEmpty()) {
            // Leg files alone: their map, without a whole course under it.
            com.tracks.app.ui.map.ActivityTrackMap(
                track = triCourses!!.legs.values.flatMap { l -> l.path.map { com.tracks.core.api.TrackPoint(lat = it.lat, lng = it.lon) } },
                styleJson = mapStyleJson, marker = held, legs = legTracks(triCourses),
            )
        }
        if (c.strategy.hasCourse) {
            val totals = remember(c.strategy.segments) {
                c.strategy.segments.takeIf { it.isNotEmpty() }?.let(RacePredictor::courseTotals)
            }
            Text(
                totals?.let { "GPX course · ${distance(it.distanceM.toDouble())} · +${it.elevationGainM} m" }
                    ?: "GPX course loaded.",
                style = MaterialTheme.typography.bodySmall,
            )
            // The course itself, on the map, as the web's race plan shows it.
            val preview = remember(c.strategy.path) {
                c.strategy.path.map { (lat, lon) -> com.tracks.core.api.TrackPoint(lat = lat, lng = lon) }
            }
            com.tracks.app.ui.map.ActivityTrackMap(track = preview, styleJson = mapStyleJson, marker = held,
                legs = triCourses?.let(::legTracks).orEmpty())
            ButtonRow {
                TonalButton(if (tri) "Add course" else "Replace course", onClick = { sheetLeg = null; choosing = true })
                DangerButton(if (tri) "Remove whole" else "Remove course", onClick = { onRemoveCourse(null) })
            }
        } else {
            Choice(COURSE, c.strategy.courseType) { onSet("course_type", it) }
            TonalButton("Add course", onClick = { sheetLeg = null; choosing = true }, modifier = Modifier.fillMaxWidth())
        }
        triCourses?.let { tc ->
            TriCourseRows(tc, onAdd = { sheetLeg = it; choosing = true }, onRemove = { onRemoveCourse(it) })
        }
        if (choosing) {
            CourseSheet(
                tracks = tracks, linked = linked, onDismiss = { choosing = false },
                onImport = { choosing = false; onImportCourse(it) },
                onDraw = { choosing = false; onDrawOnMap(it) },
                onTrack = { uid, leg -> choosing = false; onUseTrack(uid, leg) },
                triathlon = tri, fixedLeg = sheetLeg,
            )
        }
        courseError?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        // The plan's one view of its targets, course or not — it replaced the
        // lap table. A triathlon's legs each have their own (TriathlonLegs);
        // here it is the course's hills alone, which its legs are not fitted to.
        val family = c.prediction?.family
        if (family != "swimming") {
            val laps = if (family == "triathlon") emptyList() else c.prediction?.laps.orEmpty()
            val metric = remember(family, laps) { com.tracks.core.race.CourseProfile.metric(family, laps) }
            val profile = remember(c.strategy.path, c.strategy.elevations, laps, metric) {
                com.tracks.core.race.CourseProfile.points(c.strategy.path, c.strategy.elevations, laps, metric)
            }
            profile?.let { pts ->
                CourseProfileChart(
                    pts, metric,
                    // A triathlon's whole course: hold and drag T1 or T2 to
                    // where the transition really is; saved on release.
                    handles = triCourses?.transitionsM,
                    onHandles = { onSet("tri_transitions_m", it) },
                ) { p -> held = p?.lat?.let { lat -> lat to p.lon!! } }
            }
        }
    }

    // A swim has one pace and no laps to spread.
    if (c.prediction?.family != "swimming") Section(
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
        raceTargetsText(c.prediction)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        }
        // What the race's watch workout carries; nothing reads it without a watch.
        if (com.tracks.app.ui.components.LocalHasDevice.current) {
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
        if (p.family == "triathlon") Section("Race plan by leg") { TriathlonLegs(p) }
        if (p.family == "swimming") Section("Swim plan") { SwimPlan(p, c.strategy.intensity) }
        if (p.family != "swimming") c.fuel?.let { f ->
            Section("Fuelling") {
                FuelSection(c.strategy, f, onSet, onAddProduct, onToggleProduct, durationMin = p.seconds / 60.0)
            }
        }
    }
}

/**
 * The whole-race numbers a cycling or MTB plan sets beside its targets: the
 * average HR ceiling, and a hill climb's W/kg with the watts it means at the
 * rider's weight. The web's RaceTargets line; null when there are none.
 */
internal fun raceTargetsText(p: LocalRacePlans.Prediction?, weightKg: Double? = null): String? {
    p ?: return null
    val parts = listOfNotNull(
        p.targetHrCeiling?.let { "Average HR ≤ $it bpm" },
        p.climbTargetWPerKg?.let { w ->
            "Climb at ${"%.1f".format(w)} W/kg" + (weightKg?.let { " (≈ ${kotlin.math.round(w * it).toInt()} W)" } ?: "")
        },
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

/** The legs' colours, on the map and beside each row — the web's LEG_COLORS. */
private val LEG_COLORS = mapOf("swim" to 0xFF2A78D6.toInt(), "bike" to 0xFFEDA100.toInt(), "run" to 0xFFE87BA4.toInt())

private fun legTracks(tc: com.tracks.core.race.TriCourse.Courses) = tc.legs.map { (leg, c) ->
    c.path.map { com.tracks.core.api.TrackPoint(lat = it.lat, lng = it.lon) } to LEG_COLORS.getValue(leg)
}

/**
 * A triathlon's course, leg by leg: where each leg's course came from, and
 * its own add or replace — and, for an own file, remove, which puts the leg
 * back on the whole course. Named as well as coloured. The web's TriCourses.
 */
@Composable
private fun TriCourseRows(tc: com.tracks.core.race.TriCourse.Courses, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    Column {
        com.tracks.core.race.TriCourse.LEGS.forEach { leg ->
            val c = tc.legs[leg]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                androidx.compose.foundation.layout.Box(Modifier.size(12.dp, 4.dp)
                    .background(Color(LEG_COLORS.getValue(leg)), RoundedCornerShape(2.dp)))
                Column(Modifier.weight(1f)) {
                    Text(LEG_NAMES.getValue(leg), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            c?.segments?.takeIf { it.isNotEmpty() }?.let { distance(com.tracks.core.race.TriCourse.totalM(it)) },
                            when (c?.source) { "own" -> "own file"; "whole" -> "from the whole course"; else -> "no course" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TonalButton(if (c?.source == "own") "Replace" else "Add", onClick = { onAdd(leg) })
                if (c?.source == "own") DangerButton("Remove", onClick = { onRemove(leg) })
            }
        }
    }
}

/** A triathlon: each leg's time and target, then a graph of each leg's targets. */
@Composable
private fun TriathlonLegs(p: LocalRacePlans.Prediction) {
    val names = mapOf("swim" to "Swim", "bike" to "Bike", "run" to "Run")
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2), modifier = Modifier.fillMaxWidth()) {
        p.legs.forEach { leg ->
            Column(Modifier.weight(1f)) {
                Text(names[leg.leg] ?: leg.leg, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(RacePredictor.formatTime(leg.seconds), style = MaterialTheme.typography.titleMedium)
                leg.targetPace?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    Text("${RacePredictor.formatTime(p.seconds)} total, with transitions",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    p.legs.forEach { leg ->
        // The swim has one pace; drawn per 100 m from the leg's CSS pace.
        val laps = leg.laps ?: swimLap(leg)
        val metric = if (leg.leg == "swim") com.tracks.core.race.CourseProfile.Metric.SWIM
            else com.tracks.core.race.CourseProfile.metric(if (leg.leg == "bike") "cycling" else "running", laps)
        val pts = remember(leg) { com.tracks.core.race.CourseProfile.points(emptyList(), emptyList(), laps, metric) }
        pts?.let {
            Text((names[leg.leg] ?: leg.leg).uppercase(), style = Tokens.SectionHeader.style,
                color = MaterialTheme.colorScheme.primary)
            CourseProfileChart(it, metric) {}
        }
    }
}

/** A swim leg as one lap at its CSS pace, as the web draws it, in the sec-per-km the profile reads. */
private fun swimLap(leg: com.tracks.core.race.SportPlan.Leg): List<com.tracks.core.race.RaceLap> {
    val perKm = leg.swimSecPerKm ?: return emptyList()
    return listOf(com.tracks.core.race.RaceLap(1, leg.distanceM.toLong(), perKm, leg.targetPace ?: "", 0.0, 1.0,
        perKm, leg.targetPace ?: "", leg.distanceM / 1000, null))
}

/** A swim race: the one pace to hold. */
@Composable
private fun SwimPlan(p: LocalRacePlans.Prediction, intensity: Double = 1.0) {
    p.laps.firstOrNull()?.let {
        Text("Target pace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(it.targetPace, style = Tokens.Stat.value)
        Text(if (intensity == 1.0) "Your CSS, held for the whole distance."
            else "Your CSS at the race's intensity, held for the whole distance.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
