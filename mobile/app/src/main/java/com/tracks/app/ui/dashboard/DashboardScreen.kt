// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import com.tracks.core.format.distance
import com.tracks.app.ui.components.dayLabel
import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tracks.app.ui.tour.TourAnchor
import com.tracks.app.ui.tour.tourAnchor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.components.BarPill
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.StatValue
import com.tracks.app.ui.map.MapLibreView
import com.tracks.app.ui.map.addRoutes
import com.tracks.app.ui.map.frameToGeoJson
import com.tracks.app.ui.profile.SegmentedChoice
import com.tracks.core.metrics.HeatmapMode
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.tracks.core.api.DailyCoaching
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.spec.tsbBandFor
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * The home screen: what shape you are in, what you have done, what is next.
 *
 * A port of `frontend/src/pages/Dashboard.jsx` — overview totals, readiness and
 * VO₂max, upcoming work, fitness, weekly volume, the activity calendar, the
 * sport breakdown, and the training-location heatmap.
 *
 * Ordering differs from the web deliberately. The browser leads with totals
 * because it has the width to put six cards in a row; a phone leads with today
 * — readiness, form, and the next workout — because that is what someone
 * opening this before a session is actually asking. Totals scroll fine.
 *
 * Everything sits in a card on a plain background, rather than floating
 * directly on it. On a screen this narrow the cards are what separate one
 * widget from the next; without them a scroll of eight sections reads as one
 * continuous wall of numbers.
 */
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onOpenWorkout: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Above everything else — the backup reminder, when there is one. */
    banner: @Composable () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        banner()
        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Overview", modifier = Modifier.tourAnchor("dashboard-overview")) { OverviewGrid(state) }

        TourAnchor("dashboard-upcoming") { UpcomingCard(state, onOpenWorkout) }

        SectionCard("Fitness", info = Explain.Fitness, modifier = Modifier.tourAnchor("dashboard-fitness")) {
            FitnessChart(state.windowedLoad)
        }

        SectionCard(
            title = "Weekly volume",
            trailing = state.selectedSport?.let(::sportLabel),
            info = Explain.WeeklyVolume,
            modifier = Modifier.tourAnchor("dashboard-volume"),
        ) { WeeklyVolumeChart(state.weeklyVolume, after = state.period.afterDate()) }

        SectionCard("Activity history", modifier = Modifier.tourAnchor("dashboard-history")) {
            // Null for lifetime, deliberately — ActivityCalendar switches to its
            // year matrix on null, and `?: 365` was silently making Lifetime
            // draw exactly the same chart as This year.
            ActivityCalendar(state.calendar, days = state.period.days)
        }

        SectionCard(
            "Sports",
            subtitle = "Tap one to filter the charts above",
            modifier = Modifier.tourAnchor("dashboard-sports"),
        ) {
            SportBreakdownList(
                sports = state.bySport,
                selected = state.selectedSport,
                onSelect = vm::toggleSport,
            )
        }

        TrainingLocationsCard(
            backdropLight = state.backdropLight,
            backdropDark = state.backdropDark,
            routesGeoJson = state.routesGeoJson,
            routesFilter = state.routesFilter,
            routesEmpty = state.routesEmpty,
            mode = state.heatmapMode,
            onMode = vm::setHeatmapMode,
        )
    }
}

/**
 * The headline figures for the window: three, then the other nine on request.
 *
 * ## Why it folds
 *
 * Twelve numbers is the tallest thing on this screen and the least urgent.
 * Someone opening the app before a session is asking about today — readiness,
 * form, what is next — and having to scroll a block of lifetime totals to reach
 * it is the section costing more than it gives. Folded, it is a summary; opened,
 * it is the whole set, and the opened state is not remembered on purpose: the
 * default has to be the one that suits the common visit.
 *
 * ## Why the top row moves
 *
 * See [pickFront]. Fixed for the run of the app, so it varies between openings
 * and never mid-scroll.
 *
 * A figure with nothing behind it shows a dash rather than a zero: "0 m
 * climbed" is a claim about a flat month, "—" is the truth about a watch that
 * did not record it.
 */
@Composable
private fun OverviewGrid(state: DashboardUiState) {
    val stats = statsOf(state)
    // Keyed on which stats have data rather than on their values, so the row
    // settles once when the window loads and does not re-draw itself every time
    // a figure changes by a kilometre.
    val front = remember(stats.map { it.value != null }) { pickFront(stats, OverviewSeed.value) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            StatRow(front.map { stats[it] })
            if (expanded) {
                stats.indices.filterNot { it in front }
                    .chunked(OVERVIEW_COLUMNS)
                    .forEach { row -> StatRow(row.map { stats[it] }) }
            }
        }
        // A line of text rather than a Button. Material sizes a TextButton for
        // a dialog's bottom bar — 36dp of its own plus an invisible expansion
        // to a 48dp touch target plus its content padding — which is over half
        // a stat row of nothing, spent on the least important control on the
        // screen. The row is full-width, so it is still an easy target at a
        // fraction of the height.
        Text(
            if (expanded) "Show less" else "Show more",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
        )
    }
}

/**
 * One row of the grid.
 *
 * A short final row is padded rather than spread, so the columns stay in the
 * same places whatever the row holds — a two-stat row that centred itself would
 * make the grid look broken rather than short.
 */
@Composable
private fun StatRow(stats: List<Stat>) {
    Row(Modifier.fillMaxWidth()) {
        stats.forEach { stat ->
            StatValue(stat.value ?: "—", stat.label, Modifier.weight(1f), centered = true)
        }
        repeat((OVERVIEW_COLUMNS - stats.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }
}

// ── Upcoming ─────────────────────────────────────────────────────────────────

/**
 * The plan if there is one, the recommendation if there is not.
 *
 * Same precedence as the web app, for the same reason: a generated
 * recommendation is what to do *in the absence of* a plan, so showing both
 * would offer two different answers to one question.
 */
@Composable
private fun UpcomingCard(state: DashboardUiState, onOpenWorkout: (Int) -> Unit) {
    val pending = state.upcoming.filter { !it.isComplete }.take(MAX_UPCOMING)
    val recommendations = state.coaching?.recommendations.orEmpty().take(MAX_UPCOMING)

    val gauges: @Composable () -> Unit = { ReadinessAndVo2(state) }

    when {
        pending.isNotEmpty() -> SectionCard("Upcoming", dense = true) {
            pending.forEachIndexed { index, workout ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
                PlanRow(
                    title = workout.title,
                    detail = buildString {
                        append(dayLabel(workout.scheduledDate))
                        workout.durationMinutes?.let { append(" · $it min") }
                        workout.distanceMeters?.takeIf { it > 0 }?.let {
                            append(" · ${distance(it)}")
                        }
                        if (workout.sport.isNotBlank()) {
                            append(" · ${sportLabel(workout.sport)}")
                        }
                    },
                    // The plan is the entry point for doing the thing. A row
                    // that says what today is and cannot start it is a row that
                    // makes the user go and find the same workout somewhere
                    // else. See [com.tracks.app.ui.workout.GuidedWorkoutScreen].
                    onClick = { onOpenWorkout(workout.id) },
                )
            }
            gauges()
        }

        recommendations.isNotEmpty() -> SectionCard(
            "Suggested",
            subtitle = "No plan — coached for today",
            dense = true,
        ) {
            recommendations.forEachIndexed { index, rec ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
                PlanRow(
                    title = rec.title ?: sportLabel(rec.sport),
                    detail = buildString {
                        append("${rec.durationMinutes} min · ${rec.intensity}")
                        rec.focus?.let { append(" · $it") }
                    },
                    body = rec.description.takeIf { it.isNotBlank() },
                )
            }
            gauges()
        }

        // Neither a plan nor a recommendation still leaves two readings worth
        // showing. Without this branch the gauges vanished entirely on any day
        // the coach had nothing to say.
        else -> SectionCard("Today", dense = true) { ReadinessAndVo2(state) }
    }
}

/**
 * Readiness and VO₂max, side by side under whatever the day's plan is.
 *
 * They used to be their own "Today" card above Upcoming. The web app puts them
 * in the Upcoming section as a right rail beside the plan, because the three
 * are one question — what should I do today, and am I in a state to do it. A
 * phone has no room for a rail, so they sit underneath instead, which keeps
 * them in the same glance.
 */
@Composable
private fun ReadinessAndVo2(state: DashboardUiState) {
    val vo2 = state.vo2max.lastOrNull()?.value
    // Which history is open, if any. Held here rather than in the ViewModel:
    // it is which sheet is showing, and nothing outside this Row cares.
    var open by remember { mutableStateOf<OpenDial?>(null) }

    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadialGauge(
            value = state.latestReadiness?.score,
            zones = READINESS_ZONES,
            label = "Readiness",
            onClick = { open = OpenDial.Readiness },
        )
        RadialGauge(
            value = vo2,
            zones = VO2MAX_ZONES,
            label = "VO\u2082max",
            // One decimal: VO2max moves by fractions, and rounding to whole
            // numbers makes a month of real change look like a flat line.
            decimals = 1,
            onClick = { open = OpenDial.Vo2Max },
        )
    }

    when (open) {
        OpenDial.Readiness -> MetricHistorySheet(
            title = "Readiness",
            points = state.readiness.map { HistoryPoint(it.date, it.score) },
            zones = READINESS_ZONES,
            // The three inputs the server scored the day on. Readiness is a
            // composite, and a 62 nobody can decompose is a number people learn
            // to ignore — seeing that sleep scored 40 and HRV 85 is what turns
            // it into a decision about tonight rather than about training.
            breakdown = state.latestReadiness?.let {
                // Scored from training alone (no watch): the three health
                // inputs are neutral placeholders then, and listing them as
                // scores would pass off defaults as readings.
                val health = listOf(
                    "Sleep" to it.sleepScore,
                    "HRV" to it.hrvScore,
                    "Resting HR" to it.restingHrScore,
                ).takeIf { _ -> it.primaryDriver != "training_load" && it.primaryDriver != "default" }.orEmpty()
                health + listOfNotNull(it.trainingScore?.let { t -> "Training load" to t })
            }.orEmpty(),
            info = Explain.Readiness,
            onDismiss = { open = null },
        )
        OpenDial.Vo2Max -> MetricHistorySheet(
            title = "VO\u2082max",
            points = state.vo2max.map { HistoryPoint(it.date, it.value) },
            zones = VO2MAX_ZONES,
            unit = "ml/kg/min",
            decimals = 1,
            info = Explain.Vo2Max,
            onDismiss = { open = null },
        )
        null -> Unit
    }
}

/** Which dial's history is showing. */
private enum class OpenDial { Readiness, Vo2Max }

private const val MAX_UPCOMING = 3

/**
 * One line of what is next.
 *
 * [onClick] is what separates a planned workout from a suggestion: a workout
 * exists on the plan and can be started, a recommendation is the coach talking
 * and has nothing to open. Rather than making both tappable and having one of
 * them do nothing, the row only claims to be a button when it is one — hence
 * the "Start" that appears with the handler.
 */
@Composable
private fun PlanRow(
    title: String,
    detail: String,
    body: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Tight, because the card's own spacing already separates one row
            // from the next and this padding was being paid twice.
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Text(
                "Start",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── Training locations ───────────────────────────────────────────────────────

/**
 * Where the user trains, in the dashboard's window and sport.
 *
 * Interactive, and deliberately not a link. It was a tap target that opened the
 * Map tab, on the reasoning that a pannable map inside a vertical scroll fights
 * the scroll for every drag. That is a real conflict and the wrong resolution —
 * it made the one place the routes are shown the one place they cannot be
 * examined. MapLibre consumes the drag it is under and the column scrolls
 * everywhere else, which is the behaviour anyone expects from a map embedded in
 * a page.
 *
 * Taller than a thumbnail for the same reason: at 180dp a state-sized region
 * was a few pixels of line. This is a chart, not a decoration.
 *
 * The camera opens on every route in the window, as the web's does. It used to
 * open on the last 30 days whatever the window said, because "lifetime" on a
 * phone can span continents; now the window *is* the user's answer to how much
 * to look at, and picking 7 days frames the week.
 */
@Composable
private fun TrainingLocationsCard(
    backdropLight: String?,
    backdropDark: String?,
    routesGeoJson: String?,
    routesFilter: String?,
    routesEmpty: Boolean,
    mode: HeatmapMode,
    onMode: (HeatmapMode) -> Unit,
) {
    // The app's theme, not the system's: the user can pick one in Settings,
    // and the card should match the screen it sits on.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val styleJson = if (dark) backdropDark else backdropLight
    if (styleJson == null || routesGeoJson == null) return

    SectionCard("Training locations", subtitle = "Drag to pan, pinch to zoom") {
        var loaded by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
        // The filter the camera was last framed for; see DashboardUiState.routesFilter.
        var framedFor by remember { mutableStateOf<String?>(null) }

        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(Tokens.Radius.xl)),
        ) {
            MapLibreView(
                styleJson = styleJson,
                modifier = Modifier.fillMaxSize(),
                onMapReady = { map, style ->
                    map.uiSettings.isAttributionEnabled = false
                    map.uiSettings.isLogoEnabled = false
                    // Rotation and tilt off: both are easy to trigger by
                    // accident with two fingers on a small map and neither
                    // helps read a route trace.
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    loaded = map to style
                },
            )
            if (routesEmpty) {
                Text(
                    "No GPS data in this window",
                    Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(Tokens.Radius.lg))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SegmentedChoice(
            options = HeatmapMode.entries.map { it.name to it.shortLabel() },
            selected = mode.name,
            onSelect = { onMode(HeatmapMode.valueOf(it)) },
        )
        HeatmapLegend(mode)

        // Applied outside onMapReady: that callback fires as soon as the style
        // parses, which is before the routes arrive, so a camera framed there
        // frames nothing and lands at null island. Keyed on the style too: a
        // theme switch swaps the document, which drops the routes layer.
        LaunchedEffect(loaded, routesGeoJson) {
            val (map, style) = loaded ?: return@LaunchedEffect
            addRoutes(style, routesGeoJson, valueColoured = mode != HeatmapMode.Frequency)
            if (routesFilter != framedFor) {
                // An empty window leaves the camera where it was rather than
                // flying to 0,0 — frameToGeoJson does nothing with no points.
                frameToGeoJson(map, routesGeoJson)
                framedFor = routesFilter
            }
        }
    }
}

/** Four of these share a row, so heart rate is the one that is shortened. */
private fun HeatmapMode.shortLabel(): String = if (this == HeatmapMode.HeartRate) "HR" else label

/** The colour ramp a value mode is drawn in, as the web's legend shows it. */
@Composable
private fun HeatmapLegend(mode: HeatmapMode) {
    val (labels, stops) = when (mode) {
        HeatmapMode.Frequency -> return
        HeatmapMode.Gradient -> listOf("Descent", "Flat", "Climb") to
            listOf(Color(0xFF10B981), Color.White, Color(0xFF8B5CF6))
        HeatmapMode.HeartRate -> listOf("Low HR", "High HR") to SPEED_RAMP
        HeatmapMode.Pace -> listOf("Slow", "Fast") to SPEED_RAMP
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(Tokens.Radius.lg))
                .background(Brush.horizontalGradient(stops)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val SPEED_RAMP = listOf(Color(0xFF2962FF), Color(0xFF10B981), Color(0xFFFBBF24), Color(0xFFEF4444))

// ── Pieces ───────────────────────────────────────────────────────────────────

/**
 * The window, for the app bar.
 *
 * It used to be a row of chips at the top of the page, which meant it scrolled
 * away — and the window is the one control that changes what every card below
 * means, so needing to fling back to the top to change it is exactly backwards.
 * In the bar it is always there, and it costs no page height at all.
 *
 * Short labels, because four controls share a line with the page title: see
 * [Period.short].
 */
@Composable
fun DashboardPeriodActions(vm: DashboardViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    Row(
        modifier.padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Period.entries.forEach { period ->
            BarPill(
                text = period.short,
                selected = period == state.period,
                onClick = { vm.setPeriod(period) },
            )
        }
    }
}

/**
 * One widget: a title, an optional subtitle, and a card.
 *
 * The title sits *outside* the card so it reads as a label for the group rather
 * than as a heading inside a box — the same relationship the web app's
 * `<Section>` has, and what keeps a long scroll scannable.
 */
@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    info: MetricInfo? = null,
    /**
     * Tighter padding and gaps, for a card made of rows rather than a chart.
     *
     * The default is sized for a chart, which needs air around it or the axis
     * labels collide with the card edge. A list of workouts needs the opposite:
     * the gaps are the tallest thing in it, and on a phone this card sits
     * directly above the thing somebody opened the app to read.
     */
    dense: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                info?.let { InfoTip(it) }
            }
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Tokens.Radius.xl),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                Modifier.padding(
                    horizontal = 16.dp,
                    vertical = if (dense) 8.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (dense) 4.dp else 14.dp),
            ) { content() }
        }
    }
}

// Presentation-only formatting, as in the activity detail cards: choices about
// this screen, not shared domain formatting the web app must match. `number`
// and `oneDecimal` live in Overview.kt, which needs them to build its stats.
private fun signed(value: Double): String =
    if (value >= 0) "+${oneDecimal(value)}" else oneDecimal(value)
