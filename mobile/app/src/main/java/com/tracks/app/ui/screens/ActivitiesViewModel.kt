// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.api.ActivitySummary
import com.tracks.core.api.TrackShape
import com.tracks.core.spec.sportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How the list is ordered.
 *
 * Date is the default and always will be — a training log is a chronology, and
 * the question "what did I do yesterday" is the one asked most. Name answers
 * the one a chronology cannot: "where is that ride called Ridge Loop".
 *
 * ## Where sorting by type went
 *
 * It was the third option here and it was the wrong tool. "Show me my swims"
 * is not an ordering question — sorting by type answers it by putting the swims
 * next to each other somewhere in a list of hundreds, and then you scroll to
 * find where. Filtering answers it by showing the swims. Same intent, and one
 * of the two actually finishes the job, so the type control became a filter and
 * the ordering stayed chronological underneath it. See [ActivitiesUiState.types].
 */
/**
 * What the list is ordered by -- the desktop list's columns, and the
 * direction each starts in (newest, longest, fastest first; names A to Z).
 */
enum class ActivitySort(val label: String, val descendingFirst: Boolean = true) {
    Date("Date"),
    Name("Name", descendingFirst = false),
    Distance("Distance"),
    Duration("Time"),
    Speed("Speed"),
    MaxHr("Max HR"),
    AvgHr("Avg HR"),
    Elevation("Climb"),
    Calories("Calories"),
}

/** How far back the list goes. */
enum class ActivityWindow(val label: String, val days: Long?) {
    All("Any time", null),
    Week("Last 7 days", 7),
    Month("Last 30 days", 30),
    Year("Last 12 months", 365),
}

data class ActivitiesUiState(
    val sort: ActivitySort = ActivitySort.Date,
    /** Biggest first unless flipped; see [ActivitySort.descendingFirst]. */
    val descending: Boolean = true,
    /** Name search, case-insensitive, matching anywhere in the name. */
    val query: String = "",
    val window: ActivityWindow = ActivityWindow.All,
    /**
     * Sport types to show. Empty is "all of them", not "none of them".
     *
     * Keyed by the taxonomy's type rather than the raw FIT sport, so picking
     * "Cycling" catches the rides the watch filed as `biking` and `cycling`
     * alike — which is the entire reason the taxonomy exists.
     */
    val types: Set<String> = emptySet(),
    /** Track outlines by activity id. Absent means "no GPS" or "not fetched yet". */
    val shapes: Map<Int, TrackShape> = emptyMap(),
    val loadingShapes: Boolean = false,
    /** Multi-day trips, with totals derived from their visible members. */
    val trips: List<com.tracks.core.local.TripSummary> = emptyList(),
    /**
     * Activities picked for a new trip. Non-empty means the list is in
     * selection mode: a tap toggles rather than opens.
     */
    val selected: Set<Int> = emptySet(),
)

/** One entry in the type filter: what it is, and how much of it there is. */
data class SportTypeCount(val type: String, val count: Int)

/**
 * The types actually present, commonest first.
 *
 * Built from the list rather than from the taxonomy's ~120 possible types: a
 * filter offering sports the user has never done is a menu to scroll past, and
 * the count beside each one is what makes it obvious which line is the swims.
 */
fun sportTypeCounts(activities: List<ActivitySummary>): List<SportTypeCount> =
    activities.groupingBy { sportType(it.sport, it.subSport) }
        .eachCount()
        .map { (type, count) -> SportTypeCount(type, count) }
        .sortedWith(compareByDescending<SportTypeCount> { it.count }.thenBy { it.type })

/**
 * The activity list's own state: how it is sorted, and the route thumbnails.
 *
 * ## Why the thumbnails come one at a time
 *
 * Each is worked out from that activity's own parsed file, newest first, and
 * shown the moment it is ready, then kept between launches — see [load].
 *
 * ## And why they are drawn, not mapped
 *
 * A thumbnail is the *shape* of the route on a dark panel — no basemap, no
 * tiles, no renderer. Rendering the real map was tried and is the wrong
 * picture: at a stamp's size a basemap is a smear that competes with the line
 * rather than framing it, quite apart from what a renderer per row costs a
 * battery. The shape is what makes a row recognisable anyway — nobody
 * identifies a ride by the colour of the fields it went past.
 *
 * The list and its thumbnails both come from the local mirror and render with
 * no network at all.
 */
class ActivitiesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ActivitiesUiState())
    val state: StateFlow<ActivitiesUiState> = _state.asStateFlow()

    init {
        load()
        // Redraw the thumbnails when the phone imports something. They were
        // read once, when the screen was first built, so a run recorded on
        // the phone with no server showed up in the list (which does follow
        // imports) without its route until the app was restarted — while its
        // detail page, reading the same parsed track, drew the map fine.
        // Latest wins: an import burst cancels the reads it makes stale.
        viewModelScope.launch {
            container.localData.revision.drop(1).collectLatest { loadShapes() }
        }
    }

    /** A new sort starts in its natural direction; the same sort again flips it. */
    fun setSort(sort: ActivitySort) = _state.update {
        if (it.sort == sort) it.copy(descending = !it.descending)
        else it.copy(sort = sort, descending = sort.descendingFirst)
    }

    fun flipDirection() = _state.update { it.copy(descending = !it.descending) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setWindow(window: ActivityWindow) = _state.update { it.copy(window = window) }

    /**
     * Add or remove one sport type from the filter.
     *
     * Narrowing the list resets the order to Date, because the two controls
     * answer one question between them: having asked for the swims, what you
     * want is the swims in the order everything else is in. Sorting by name is
     * still one tap away and is kept if it is chosen afterwards.
     */
    fun toggleType(type: String) = _state.update {
        val types = if (type in it.types) it.types - type else it.types + type
        it.copy(types = types, sort = if (types.isEmpty()) it.sort else ActivitySort.Date)
    }

    fun clearTypes() = _state.update { it.copy(types = emptySet()) }

    fun toggleSelected(id: Int) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    /** Merge the picked activities into a new trip, then leave selection mode. */
    fun mergeSelected(name: String) {
        val ids = _state.value.selected.toList()
        viewModelScope.launch {
            container.sources.createTrip(name, ids)
            _state.update { it.copy(selected = emptySet(), trips = container.sources.trips()) }
        }
    }

    /** Delete a trip. Its activities are untouched — a trip is only a grouping. */
    fun deleteTrip(id: Int) {
        viewModelScope.launch {
            container.sources.delete("trip", id)
            _state.update { it.copy(trips = container.sources.trips()) }
        }
    }

    /**
     * Cached outlines at once, then the rest one by one, newest first.
     *
     * An outline means decoding that activity's detail file, and it used to be
     * all of them in one pass ([com.tracks.core.local.LocalLibrary.routesGeoJson])
     * before any thumbnail appeared — seconds on a long history, during which
     * the list had no pictures at all. Now each outline is published the
     * moment its own file is read, so they fill in down the list as the user
     * looks at it, and each is kept ([com.tracks.app.local.TrackShapeCache]) so
     * the next visit draws them all straight away: a track never changes once
     * recorded. The list itself never waits on any of this.
     */
    fun load() {
        viewModelScope.launch { loadShapes() }
    }

    private suspend fun loadShapes() {
        _state.update { it.copy(trips = container.sources.trips()) }
        _state.update { it.copy(loadingShapes = true) }
        val cache = container.trackShapes
        val candidates = withContext(Dispatchers.IO) {
            runCatching { container.library.shapeCandidates() }.getOrDefault(emptyList())
        }
        val known = withContext(Dispatchers.IO) { cache.snapshot() }
        _state.update { st ->
            st.copy(shapes = candidates.mapNotNull { (id, uid) -> known[uid]?.let { id to it } }.toMap())
        }
        var fresh = 0
        for ((id, uid) in candidates) {
            if (uid in known) continue
            val shape = withContext(Dispatchers.Default) {
                runCatching { container.library.trackShape(uid) }.getOrNull()
            }
            cache.put(uid, shape)
            fresh++
            if (shape != null) _state.update { it.copy(shapes = it.shapes + (id to shape)) }
            // Written every so often rather than at the end, so a first visit
            // cut short still saves what it worked out.
            if (fresh % 25 == 0) withContext(Dispatchers.IO) { cache.flush() }
        }
        if (fresh > 0) withContext(Dispatchers.IO) { cache.flush() }
        _state.update { it.copy(loadingShapes = false) }
    }

    /**
     * Filtered, then sorted, for display.
     *
     * In that order, and it matters: sorting the whole log and then dropping
     * most of it does the same work for a list nobody sees. Date descending;
     * name ascending, as reading order.
     */
    fun visible(
        activities: List<ActivitySummary>,
        state: ActivitiesUiState,
    ): List<ActivitySummary> {
        val q = state.query.trim().lowercase()
        val since = state.window.days?.let { java.time.LocalDate.now().minusDays(it).toString() }
        val kept = activities.filter { a ->
            (state.types.isEmpty() || sportType(a.sport, a.subSport) in state.types) &&
                (q.isEmpty() || (a.name ?: "").lowercase().contains(q)) &&
                (since == null || (a.startedAt ?: "") >= since)
        }
        return sortActivities(kept, state.sort, state.descending)
    }
}

/**
 * [activities] in [sort] order. An activity with no value for the field (a
 * swim with no heart rate, an indoor ride with no climb) goes last in either
 * direction rather than counting as zero; ties fall back to newest first.
 */
internal fun sortActivities(
    activities: List<ActivitySummary>,
    sort: ActivitySort,
    descending: Boolean,
): List<ActivitySummary> {
    if (sort == ActivitySort.Name) {
        val named = activities.filter { !it.name.isNullOrBlank() }.sortedBy { it.name!!.lowercase() }
        return (if (descending) named.reversed() else named) + activities.filter { it.name.isNullOrBlank() }
    }
    val key: (ActivitySummary) -> Double? = when (sort) {
        ActivitySort.Date -> { a -> a.startedAt?.let { 0.0 } }
        ActivitySort.Distance -> { a -> a.distanceMeters?.takeIf { it > 0 } }
        ActivitySort.Duration -> { a -> a.durationSeconds?.toDouble()?.takeIf { it > 0 } }
        ActivitySort.Speed -> { a -> a.avgSpeed?.takeIf { it > 0 } }
        ActivitySort.MaxHr -> { a -> a.maxHeartRate?.toDouble()?.takeIf { it > 0 } }
        ActivitySort.AvgHr -> { a -> a.avgHeartRate?.toDouble()?.takeIf { it > 0 } }
        ActivitySort.Elevation -> { a -> a.totalAscent?.takeIf { it > 0 } }
        ActivitySort.Calories -> { a -> a.totalCalories?.toDouble()?.takeIf { it > 0 } }
        ActivitySort.Name -> { _ -> null }
    }
    if (sort == ActivitySort.Date) {
        val dated = activities.filter { it.startedAt != null }.sortedBy { it.startedAt }
        return (if (descending) dated.reversed() else dated) + activities.filter { it.startedAt == null }
    }
    val newestFirst = compareByDescending<ActivitySummary> { it.startedAt ?: "" }
    val (with, without) = activities.partition { key(it) != null }
    val ordered = with.sortedWith(
        (if (descending) compareByDescending<ActivitySummary> { key(it)!! } else compareBy { key(it)!! })
            .then(newestFirst),
    )
    return ordered + without.sortedWith(newestFirst)
}

