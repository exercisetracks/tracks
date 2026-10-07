// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.api.ActivityCalendarPoint
import com.tracks.core.api.DailyCoaching
import com.tracks.core.api.MetricsSummary
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.ReadinessHistoryPoint
import com.tracks.core.api.SportBreakdown
import com.tracks.core.api.TrainingLoadPoint
import com.tracks.core.api.Vo2MaxPoint
import com.tracks.core.api.VaultLockedException
import com.tracks.core.api.WeeklyVolumePoint
import com.tracks.core.time.ZoneOffsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import com.tracks.core.local.ScreenSnapshots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class DashboardUiState(
    val period: Period = Period.default,
    /** Cross-filter: null is every sport. Drives the calendar and volume only. */
    val selectedSport: String? = null,
    val loading: Boolean = true,
    val summary: MetricsSummary? = null,
    /** The figures the server does not send — always the phone's own arithmetic. */
    val extras: DashboardExtras = DashboardExtras(),
    val bySport: List<SportBreakdown> = emptyList(),
    val calendar: List<ActivityCalendarPoint> = emptyList(),
    val vo2max: List<Vo2MaxPoint> = emptyList(),
    val readiness: List<ReadinessHistoryPoint> = emptyList(),
    val weeklyVolume: List<WeeklyVolumePoint> = emptyList(),
    /** All-time, unfiltered. See [windowedLoad] for why. */
    val trainingLoad: List<TrainingLoadPoint> = emptyList(),
    val coaching: DailyCoaching? = null,
    val upcoming: List<PlannedWorkout> = emptyList(),
    /** The basemap for the training-locations card, from the on-disk cache. */
    val mapStyleJson: String? = null,
    /**
     * Every route as a GeoJSON FeatureCollection, raw.
     *
     * LineStrings rather than the point heatmap this used to fetch — see
     * [com.tracks.app.ui.map.addRoutes] for why the density layer was the wrong
     * shape for data that is already traces.
     */
    val routesGeoJson: String? = null,
    val error: String? = null,
) {
    /**
     * The training-load series clipped to the selected window.
     *
     * Clipped here rather than requested that way. CTL is an exponentially
     * weighted average with a 42-day time constant, so a series the server
     * built starting at the window's first day starts at zero and ramps up —
     * drawing a fitness collapse that never happened. Fetch everything, show a
     * slice. ISO dates compare lexicographically, which is the same filter the
     * web app runs (`allLoad.filter(p => p.date >= after)`).
     */
    val windowedLoad: List<TrainingLoadPoint>
        get() {
            val after = period.afterDate() ?: return trainingLoad
            return trainingLoad.filter { it.date >= after }
        }

    /** Today's form reading, which is the last day the model has. */
    val currentTsb: Double? get() = trainingLoad.lastOrNull()?.tsb

    val latestReadiness: ReadinessHistoryPoint? get() = readiness.lastOrNull()
}

/**
 * Loads the dashboard.
 *
 * ## Nine requests, and why none of them can fail the screen
 *
 * The web dashboard fires the same set and `.catch(() => {})`s every one, which
 * is the right instinct badly expressed: these are nine independent widgets and
 * the correct behaviour is that each renders or is absent. Here that is
 * explicit — every call is wrapped, and a failure means one card does not
 * appear rather than the dashboard reporting an error.
 *
 * The distinction earns itself on a locked vault. Encrypted columns make some
 * of these 401 while others answer normally, which happens weekly by design and
 * on every Redis restart; a screen that demanded all nine would go blank for a
 * user whose data is perfectly intact.
 *
 * ## Two clocks
 *
 * The all-time slices (training load, coaching, upcoming) are fetched once and
 * never refetched on a period change — they are not windowed server-side. The
 * windowed slices refetch when the period moves, and the two cross-filtered
 * ones refetch again when a sport is picked. Collapsing these into one reload
 * would put a nine-request storm behind every tap of the period selector.
 *
 * ## Offline — which is to say, always
 *
 * Every slice, the coaching note included, is computed on the phone from
 * what it holds: the activities it imported itself, the days it read from the watch's
 * health files, the plan in its replica. The arithmetic is the server's own,
 * ported and held to it by `spec/fixtures/metrics.json` (see
 * [com.tracks.core.local.LocalMetrics]), so the phone and the web app draw the
 * same dashboard from the same files and there is nothing to wait for.
 *
 * The independent-widgets rule holds throughout: a slice with no answer is an
 * absent card, not a failed screen.
 */
@OptIn(FlowPreview::class)
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        restoreSnapshot()
        loadAllTime()
        loadWindowed()
        loadFiltered()
        loadLocations()
        watchLocalImports()
        keepSnapshot()
    }

    /**
     * Show what the dashboard last showed, before anything is computed.
     *
     * A cold start used to draw every chart and figure empty for the moment
     * the computation took. The last state comes back from the sealed snapshot
     * (com.tracks.core.local.ScreenSnapshots) on the first frame, and each
     * slice is replaced as its fresh value arrives — the loads below keep a
     * slice's old value until they have a new one, so nothing blinks out.
     * Synchronous when the store has finished reading ahead, which it nearly
     * always has; otherwise moments later, and only onto a state still empty.
     */
    private fun restoreSnapshot() {
        fun apply(raw: String?) {
            val snap = ScreenSnapshots.decodeDashboard(raw) ?: return
            _state.update { st ->
                if (st.summary != null || st.trainingLoad.isNotEmpty()) return@update st
                val sameWindow = snap.period == st.period.name && st.selectedSport == null
                st.copy(
                    loading = !sameWindow,
                    trainingLoad = snap.trainingLoad,
                    coaching = snap.coaching,
                    upcoming = snap.upcoming,
                    summary = if (sameWindow) snap.summary else null,
                    extras = if (sameWindow) snap.extras.toExtras() else st.extras,
                    bySport = if (sameWindow) snap.bySport else emptyList(),
                    calendar = if (sameWindow) snap.calendar else emptyList(),
                    vo2max = if (sameWindow) snap.vo2max else emptyList(),
                    readiness = if (sameWindow) snap.readiness else emptyList(),
                    weeklyVolume = if (sameWindow) snap.weeklyVolume else emptyList(),
                )
            }
        }
        val ready = container.snapshots.peek(SNAPSHOT)
        if (ready != null) apply(ready)
        else viewModelScope.launch { apply(withContext(Dispatchers.IO) { container.snapshots.await(SNAPSHOT) }) }
    }

    /**
     * Save the state as it settles, for the next cold start. Only the default
     * window with no sport picked, since that is what a cold start opens on.
     */
    private fun keepSnapshot() {
        viewModelScope.launch {
            _state.debounce(SNAPSHOT_SETTLE_MS).collect { st ->
                if (st.loading || st.period != Period.default || st.selectedSport != null) return@collect
                if (st.summary == null && st.trainingLoad.isEmpty()) return@collect
                val text = withContext(Dispatchers.Default) {
                    ScreenSnapshots.encode(
                        ScreenSnapshots.Dashboard(
                            period = st.period.name,
                            summary = st.summary,
                            extras = st.extras.toSnapshot(),
                            bySport = st.bySport,
                            calendar = st.calendar,
                            vo2max = st.vo2max,
                            readiness = st.readiness,
                            weeklyVolume = st.weeklyVolume,
                            trainingLoad = st.trainingLoad,
                            coaching = st.coaching,
                            upcoming = st.upcoming,
                        ),
                    )
                }
                container.snapshots.write(SNAPSHOT, text)
            }
        }
    }

    /**
     * The training-locations heatmap — the dashboard owns it, as on the web.
     *
     * The style is read from disk first and only fetched when the cache is
     * empty. An earlier version read the cache and gave up if it missed, on the
     * reasoning that the Map tab would populate it; that made the card invisible
     * for anyone who had not happened to open the map yet, which is everyone on
     * a fresh install. A card that renders only after an unrelated tab has been
     * visited is indistinguishable from one that does not work.
     *
     * The fetch caches what it gets, so this costs one download per install
     * rather than one per dashboard load, and the Map tab benefits from it too.
     */
    private fun loadLocations() {
        viewModelScope.launch {
            val cache = container.mapStyleCache()
            // Refreshed rather than read-once. The served style narrows each
            // source to the tile archives that exist, so it changes when a
            // region is downloaded — and a cache only written when empty pins
            // the card to whatever it saw first. Falls back to the cached copy
            // so the card still draws with no signal.
            val style = attempt { container.client().mapStyleJson() }
                ?.also { fresh -> withContext(Dispatchers.IO) { cache.write(fresh) } }
                ?: withContext(Dispatchers.IO) { runCatching { cache.read() }.getOrNull() }
            // The routes are the phone's own: every track it imported, drawn
            // from the files it holds — so the card is right in a hut too.
            // Sport-filtered like the web app's.
            val sport = _state.value.selectedSport
            val routes = withContext(Dispatchers.Default) {
                runCatching {
                    val uids = sport?.let { s ->
                        container.sources.activities().filter { it.sport == s }
                            .mapNotNull { container.library.uidOf(it.id) }.toSet()
                    }
                    container.library.routesGeoJson(uids)
                }.getOrNull()
            }
            _state.update {
                it.copy(
                    mapStyleJson = style ?: it.mapStyleJson,
                    routesGeoJson = routes ?: it.routesGeoJson,
                )
            }
        }
    }

    fun setPeriod(period: Period) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period) }
        loadWindowed()
        loadFiltered()
    }

    /** Tapping the selected sport clears the filter, matching the web app. */
    fun toggleSport(sport: String) {
        _state.update { it.copy(selectedSport = if (it.selectedSport == sport) null else sport) }
        loadFiltered()
        loadLocations()
    }

    fun refresh() {
        loadAllTime()
        loadWindowed()
        loadFiltered()
        loadLocations()
    }

    /** Not windowed by the period selector — see the class note. */
    private fun loadAllTime() {
        viewModelScope.launch {
            val threshold = container.importThresholds().thresholdHr
            val today = LocalDate.now()
            val load = withContext(Dispatchers.Default) {
                runCatching { container.metrics.trainingLoad(today(), threshold) }.getOrNull()
            }
            val upcoming = runCatching {
                container.sources.plannedWorkouts(today.toString(), today.plusDays(UPCOMING_DAYS.toLong()).toString())
                    .filter { !it.isComplete }
            }.getOrNull()
            _state.update {
                it.copy(trainingLoad = load ?: it.trainingLoad, upcoming = upcoming ?: it.upcoming)
            }
            // Coached here, from the same readiness and load as the gauge — see
            // LocalMetrics.coaching for why this is no longer fetched.
            val coaching = withContext(Dispatchers.Default) {
                runCatching { container.metrics.coaching(today(), threshold) }.getOrNull()
            }
            if (coaching != null) _state.update { it.copy(coaching = coaching) }
        }
    }

    /**
     * The window's slices, all computed on the phone from what it holds.
     *
     * These used to be fetched and corrected; now the phone runs the server's
     * own arithmetic (com.tracks.core.metrics, held to it by fixtures) over the
     * same rows, so there is nothing to correct and nothing to wait for — a
     * window never opened before, with no signal, is still right.
     */
    private fun loadWindowed() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val after = _state.value.period.afterDate()
            val days = _state.value.period.days?.coerceAtMost(READINESS_MAX_DAYS) ?: READINESS_MAX_DAYS
            val threshold = container.importThresholds().thresholdHr
            val m = container.metrics
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    Windowed(
                        summary = m.summary(after),
                        bySport = m.bySport(after),
                        vo2max = m.vo2max(after),
                        readiness = m.readiness(today(), days, threshold),
                        extras = localDashboard(after, _state.value.selectedSport)?.extras,
                    )
                }.getOrNull()
            }
            _state.update {
                it.copy(
                    loading = false,
                    summary = result?.summary ?: it.summary,
                    bySport = result?.bySport ?: it.bySport,
                    vo2max = result?.vo2max ?: it.vo2max,
                    readiness = result?.readiness ?: it.readiness,
                    extras = result?.extras ?: it.extras,
                    error = if (result?.summary == null) "Nothing is saved on this phone yet." else null,
                )
            }
        }
    }

    private data class Windowed(
        val summary: MetricsSummary?,
        val bySport: List<SportBreakdown>,
        val vo2max: List<Vo2MaxPoint>,
        val readiness: List<ReadinessHistoryPoint>,
        val extras: DashboardExtras?,
    )

    /**
     * Redraw when the phone imports something or an edit lands — a ride pulled
     * off the watch in a hut is on the dashboard the moment the sync finishes.
     * The first value is dropped: it is the state init has already read.
     */
    private fun watchLocalImports() {
        viewModelScope.launch {
            container.localData.revision.drop(1).collect {
                loadAllTime()
                loadWindowed()
                loadFiltered()
            }
        }
    }

    /**
     * The figures the server's dashboard does not send — ascent, calories,
     * longest — worked out from the list. See [computeDashboard].
     */
    private suspend fun localDashboard(after: String?, sport: String?): LocalDashboard? {
        val activities = runCatching { container.sources.activities() }.getOrDefault(emptyList())
        if (activities.isEmpty()) return null
        // Days are the account's local days, as on the server; an unreadable
        // zone reads as UTC, as an unset one does.
        val zone = runCatching { container.sources.accountZone() }.getOrNull()
        return computeDashboard(activities, after, sport, ZoneOffsets.of(zone))
    }

    /** The two slices the sport cross-filter applies to. */
    private fun loadFiltered() {
        viewModelScope.launch {
            val after = _state.value.period.afterDate()
            val sport = _state.value.selectedSport
            val m = container.metrics
            val calendar = withContext(Dispatchers.Default) { runCatching { m.calendar(after, sport) }.getOrNull() }
            val volume = withContext(Dispatchers.Default) { runCatching { m.weeklyVolume(after, sport) }.getOrNull() }
            _state.update {
                it.copy(calendar = calendar ?: it.calendar, weeklyVolume = volume ?: it.weeklyVolume)
            }
        }
    }

    private fun today(): com.tracks.core.fit.decode.CivilDate {
        val d = LocalDate.now()
        return com.tracks.core.fit.decode.CivilDate(d.year, d.monthValue, d.dayOfMonth)
    }

    /**
     * Null on any failure, keeping whatever was there.
     *
     * [VaultLockedException] is caught alongside the rest deliberately. The
     * client has already tried the device key and failed by the time it reaches
     * here, and there is nothing this screen can do about it — Settings owns the
     * unlock prompt. A dashboard is a poor place to interrupt someone for a
     * password.
     */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        runCatching { block() }.getOrNull()

    private companion object {
        const val UPCOMING_DAYS = 14
        const val READINESS_MAX_DAYS = 365
    }
}

private const val SNAPSHOT = "dashboard"
/** Long enough that a burst of slices arriving is one write, not nine. */
private const val SNAPSHOT_SETTLE_MS = 1500L

private fun ScreenSnapshots.Extras.toExtras() = DashboardExtras(
    totalAscentM, totalCalories, longestDistanceM, longestDurationSec, activeDays, avgHeartRate, busiestSport,
)

private fun DashboardExtras.toSnapshot() = ScreenSnapshots.Extras(
    totalAscentM, totalCalories, longestDistanceM, longestDurationSec, activeDays, avgHeartRate, busiestSport,
)
