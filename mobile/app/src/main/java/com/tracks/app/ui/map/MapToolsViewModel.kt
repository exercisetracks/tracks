// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.device.WatchManager
import com.tracks.device.PulledFileKind
import com.tracks.core.api.PoiHit
import com.tracks.core.api.CourseCreate
import com.tracks.core.api.CourseDetail
import com.tracks.core.api.CourseSummary
import com.tracks.core.api.CourseUpdate
import com.tracks.core.api.Waypoint
import com.tracks.core.api.WaypointIn
import com.tracks.core.api.WaypointUpdate
import com.tracks.core.api.PointInfo
import com.tracks.core.api.TracksClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/** What the map is currently doing with a tap. */
enum class MapTool {
    /** Taps inspect a point. */
    Inspect,

    /** Taps add a waypoint to the route being built. */
    Route,
}

/** A route as the builder holds it: the taps, and what the server made of them. */
data class RouteDraft(
    /** In tap order. Fewer than two means there is nothing to snap yet. */
    val waypoints: List<LatLng> = emptyList(),
    /** GeoJSON of the snapped line, ready to hand to a source. */
    val geoJson: String? = null,
    val metres: Double? = null,
    val ascentMetres: Double? = null,
    val seconds: Double? = null,
    /**
     * The line's elevation against distance along it, for the profile chart.
     *
     * Taken from the snapped geometry's own third ordinate rather than sampled
     * again from a server: BRouter routes *on* elevation — it is why the
     * climbing figure exists at all — so the numbers are already in the answer,
     * and asking for them a second time could disagree with the route that was
     * planned.
     */
    val profile: List<RoutePoint> = emptyList(),
    /**
     * Each piece of the line between two handles, with its own length.
     *
     * A total distance answers "is this route too long"; the segments answer
     * "which part of it is the long part", which is the question somebody
     * drawing a line is actually asking as they place each tap.
     */
    val segments: List<RouteSegment> = emptyList(),
    val snapping: Boolean = false,
    /**
     * Whether taps are laid on the nearest trail or joined as drawn.
     *
     * On by default, because following the path is what people mean nearly
     * always. Off matters for the cases where it is actively wrong: a bushwhack
     * line, a ski descent, a boundary — anywhere the route is not a trail, where
     * snapping drags every point onto the nearest one and produces a shape
     * nobody asked for.
     */
    val snapToTrails: Boolean = true,
    val error: String? = null,
    /** Set while a save is in flight, so the button cannot be pressed twice. */
    val saving: Boolean = false,
) {
    /**
     * The line to save, as `[lng, lat, ele?]`.
     *
     * The snapped geometry when there is one, since that is what is drawn and
     * what the user agreed to; the raw taps otherwise. A track saved from
     * something other than the line on screen would be a small betrayal.
     */
    fun coordinates(): List<List<Double>> {
        geoJson?.let { json ->
            lineCoordinates(json).takeIf { it.size >= 2 }?.let { return it }
        }
        return waypoints.map { listOf(it.longitude, it.latitude) }
    }
}

/** One point along a snapped route: how far in, and how high. */
data class RoutePoint(val metres: Double, val elevationMetres: Double)

/**
 * A saved thing the user is looking at.
 *
 * A track arrives as [CourseDetail] rather than the list's summary because the
 * whole reason to open one is what the summary leaves out — the height profile.
 * A place needs no second fetch: the list row already holds everything there is
 * to know about a point.
 */
sealed interface Selection {
    data class Track(val course: CourseDetail) : Selection
    data class Place(val waypoint: Waypoint) : Selection
}

/** Where to put the camera. A box when there is one, else a point. */
sealed interface Focus {
    data class Point(val lat: Double, val lng: Double) : Focus

    /** `[west, south, east, north]`. */
    data class Area(val bounds: List<Double>) : Focus
}

data class MapToolsUiState(
    val tool: MapTool = MapTool.Inspect,
    val point: PointInfo? = null,
    /** Everything that can go on the watch, and how it is drawn. */
    val courses: List<CourseSummary> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    /** The tracks' own geometry, kept as text for the map source to swallow. */
    val courseGeoJson: String? = null,
    val libraryLoading: Boolean = false,
    /** Land and trails at the tapped point, read off the rendered tiles. */
    val surroundings: PointSurroundings = PointSurroundings(),
    val pointLoading: Boolean = false,
    /**
     * Whether the forecast is still in flight for the open point.
     *
     * Separate from [pointLoading] because the two now finish at different
     * times, and one flag would have to lie about one of them: the sheet's
     * facts arrive first and its weather later, and "unavailable" shown in the
     * gap reads as a final answer.
     */
    val weatherLoading: Boolean = false,
    val searchResults: List<PoiHit> = emptyList(),
    val searching: Boolean = false,
    val route: RouteDraft = RouteDraft(),
    /** The saved thing the user tapped or picked, if any. */
    val selected: Selection? = null,
    /**
     * Somewhere the map should move to, consumed once by the screen.
     *
     * Held as state rather than pushed at the camera directly because the map
     * object lives in the composition and the view model must not reach into
     * it — and because "go here" arriving while the style is reloading has to
     * be able to wait rather than throw.
     */
    val focus: Focus? = null,
    /**
     * One line about the last thing the user asked for — a save, a send, a
     * removal. Transient and cleared on the next action, because it is a
     * receipt rather than state: these operations all happen somewhere the user
     * cannot see (a server row, a watch), so silence is indistinguishable from
     * failure.
     */
    val notice: String? = null,
)

/**
 * Tapping the map: what is here, where is that, and how do I get there.
 *
 * Point info and place search lean on the server, which holds the DEM and the
 * gazetteer. That is a real limit on the offline story and an honest one:
 * failures say so rather than showing an empty result that reads like "nothing
 * here".
 *
 * Routing used to be in that list and no longer is. A region downloaded for a
 * trip now brings BRouter's own segments down with it, and the engine runs on
 * the phone — see [com.tracks.app.map.OfflineRoutingData]. Planning a route is
 * the thing you most want in a valley with no bars, so it is the one that got
 * fixed first.
 */
class MapToolsViewModel(internal val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(MapToolsUiState())
    val state: StateFlow<MapToolsUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** The in-flight elevation sample for an un-snapped line, if any. */
    private var groundJob: Job? = null

    /** The in-flight lookup for the tapped point, cancelled by the next tap. */
    private var pointJob: Job? = null

    /**
     * Whether [loadLibrary] has ever been asked for.
     *
     * Guards [watchLocalChanges] so a watch sync that has nothing to do with
     * the map does not silently start fetching a library nobody has opened
     * yet — the sheet stays lazy, it just stops being able to go stale once
     * it has been opened once.
     */
    private var libraryRequested = false

    init {
        watchLocalChanges()
        // Opened from a race plan's "Draw on map": start in the route builder,
        // since drawing is the only reason the user came.
        viewModelScope.launch {
            container.pendingRaceCourse.collect { goal -> if (goal != null) selectTool(MapTool.Route) }
        }
    }

    /**
     * Redraw the library when the phone changes its own copy of it.
     *
     * The outbox flushing in the background — from [container]'s periodic
     * sync, or from another screen — is exactly the case this exists for: a
     * waypoint dropped offline gets its real id without anyone touching this
     * screen again, and without this, the library would keep showing the
     * temporary one until it happened to be reopened. The first value is
     * dropped: it is the state a load already read.
     */
    private fun watchLocalChanges() {
        viewModelScope.launch {
            container.localData.revision.drop(1).collect {
                if (libraryRequested) loadLibrary()
            }
        }
    }

    fun selectTool(tool: MapTool) {
        _state.update { it.copy(tool = tool, point = null) }
    }

    // ── Inspecting ───────────────────────────────────────────────────────────

    /**
     * What is at a tapped point.
     *
     * [surroundings] comes from the rendered tiles rather than the server, and
     * that is the same split the web app makes: the land a point sits on and
     * the trails around it are already drawn on screen, so asking a server for
     * them would be a round trip for something the device is looking at. The
     * server is asked only for what the tiles cannot know — the elevation under
     * the point and the weather over it.
     */
    fun inspect(position: LatLng, surroundings: PointSurroundings = PointSurroundings()) {
        val here = PointInfo(lat = position.latitude, lon = position.longitude)
        // On screen before anything is asked of the server. The sheet used to
        // wait for the whole response, which included a forecast fetched from
        // another company's API over the internet — so a tap on the map did
        // nothing visible for as long as that took, and on a slow link looked
        // like the tap had missed. What it can say immediately is where you
        // tapped, which is most of the question.
        _state.update {
            it.copy(
                pointLoading = true,
                weatherLoading = true,
                point = here,
                surroundings = surroundings,
            )
        }

        pointJob?.cancel()
        pointJob = viewModelScope.launch {
            val client = container.client()
            // Two requests, deliberately. Elevation and the nearest place are
            // local lookups on the server and come back in milliseconds; the
            // forecast is a call out of the building. Asking together made the
            // fast half arrive at the speed of the slow half.
            val info = runCatching {
                client.pointInfo(position.latitude, position.longitude, withWeather = false)
            }.getOrNull() ?: offlineElevationOnly(client, here)
            _state.update { state ->
                // Only if the user has not tapped somewhere else since.
                if (state.point?.samePlaceAs(here) != true) state
                else state.copy(pointLoading = false, point = info ?: state.point)
            }

            val forecast = runCatching {
                container.client().pointWeather(position.latitude, position.longitude)
            }.getOrNull()
            _state.update { state ->
                val open = state.point
                if (open == null || !open.samePlaceAs(here)) state
                else state.copy(
                    weatherLoading = false,
                    point = if (forecast != null) open.copy(weather = forecast) else open,
                )
            }
        }
    }

    /**
     * What [inspect] can still say about a point with no server to ask:
     * elevation alone, off the phone's own DEM tiles — see
     * [com.tracks.app.map.OfflineDem]. The nearest place needs the
     * gazetteer, which this does not have, and stays absent — an honest gap
     * rather than a guess. Null when even that cannot be found, same as a
     * plain failed fetch.
     */
    private suspend fun offlineElevationOnly(client: TracksClient, here: PointInfo): PointInfo? {
        val elevationMetres = container.offlineDem().elevation(client, here.lat, here.lon) ?: return null
        return here.copy(elevationMetres = elevationMetres)
    }

    // ── Tracks and waypoints ─────────────────────────────────────────────────

    /**
     * Load the library on demand — this phone's own tracks and places, read
     * from its replica. Not at start-up: the map opens to look at the map, and
     * neither list is visible until somebody asks for the sheet.
     */
    fun loadLibrary() {
        libraryRequested = true
        viewModelScope.launch {
            val items = container.mapItems
            _state.update { it.copy(libraryLoading = true) }
            val courses = runCatching { items.courses() }.getOrNull()
            val waypoints = runCatching { items.waypoints() }.getOrNull()
            val geometry = runCatching { items.coursesGeoJson() }.getOrNull()
            _state.update {
                it.copy(
                    courses = courses ?: it.courses,
                    waypoints = waypoints ?: it.waypoints,
                    courseGeoJson = geometry ?: it.courseGeoJson,
                    libraryLoading = false,
                )
            }
        }
    }

    /**
     * Save the tapped point as a place, and show it in the library at once.
     *
     * [SavedSpot.sendToWatch] is honoured here rather than left for a later
     * visit to the library: marking a spot and wanting it on the wrist are one
     * intention, and making somebody find the same place again in a list to
     * express the second half of it is the kind of step that gets skipped
     * before a trip.
     */
    fun saveWaypoint(spot: SavedSpot, lat: Double, lng: Double, elevationMetres: Double?) {
        viewModelScope.launch {
            // A local write: the pin exists the moment it is dropped, with or
            // without a server, and syncs later.
            val created = container.mapItems.createWaypoint(
                WaypointIn(
                    name = spot.name.ifBlank { "Waypoint" },
                    lat = lat,
                    lng = lng,
                    elevationMetres = elevationMetres,
                    color = spot.color,
                    icon = spot.icon,
                    loadToDevice = spot.sendToWatch,
                ),
            ) ?: return@launch

            _state.update {
                it.copy(waypoints = it.waypoints + created, notice = "Saved “${created.name}”")
            }
            if (spot.sendToWatch) {
                deliver("“${created.name}”") { container.watch.pushWaypointsNow(container.client()) }
            }
        }
    }

    fun setCourseColor(course: CourseSummary, hex: String) =
        patchCourse(course, CourseUpdate(color = hex))

    /**
     * Edit a track and reflect it everywhere it is shown.
     *
     * Delegates rather than writing its own update, and that is the fix for a
     * real bug: this used to refresh the *list* and leave [MapToolsUiState.selected]
     * alone, so recolouring a track from the sheet you opened by tapping it on
     * the map changed nothing you could see. The swatch only caught up when the
     * sheet was closed and opened again, which reads as the app ignoring you.
     */
    private fun patchCourse(course: CourseSummary, update: CourseUpdate) {
        viewModelScope.launch { patchCourseNow(course, update) }
    }

    fun setWaypointStyle(waypoint: Waypoint, color: String?, icon: String?) =
        patchWaypoint(waypoint, WaypointUpdate(color = color, icon = icon))

    /** Edit a place and reflect it everywhere — see [patchCourse]. */
    private fun patchWaypoint(waypoint: Waypoint, update: WaypointUpdate) {
        viewModelScope.launch { patchWaypointNow(waypoint, update) }
    }

    fun deleteWaypoint(waypoint: Waypoint) {
        viewModelScope.launch {
            val client = container.client()
            container.mapItems.deleteWaypoint(waypoint.id)
            _state.update { state ->
                state.copy(
                    waypoints = state.waypoints.filterNot { it.id == waypoint.id },
                    // A sheet describing something that no longer exists, with
                    // buttons that now do nothing, is worse than no sheet.
                    selected = state.selected.unless(waypointId = waypoint.id),
                    notice = "Deleted “${waypoint.name}”",
                )
            }
            // The watch does not find out on its own: deleting drops the row
            // from the cached list entirely, and a saved place the ordinary
            // push plan can no longer see is one it can never notice needs
            // removing from the file. Only worth doing at all when this one
            // was actually believed to be on the watch.
            if (waypoint.onWatch) container.watch.syncWaypointsAfterDelete(client)
        }
    }

    /**
     * Remove a track.
     *
     * Bluetooth is the primary way this happens, not a bonus on top of a
     * cable sync — Tracks treats USB as the fallback for when a phone is not
     * available, not the mechanism a feature is allowed to depend on, so a
     * course delete has to be able to complete on BLE alone. Deleting is
     * therefore awaited: a course whose removal the phone managed to record
     * and start a sync for is dropped from the list immediately, the same as
     * the always-local path below for a track that never reached the server.
     * One with no watch in range — or one on a connection that has not yet run
     * an Explore sync, so no UUID is known for it — stays visible with "coming
     * off" and the list is refetched instead. Guessing would show a track as
     * gone while the watch still had it, and the tombstone is durable, so the
     * next sync that does run carries it.
     */
    fun deleteCourse(course: CourseSummary) {
        viewModelScope.launch {
            container.mapItems.deleteCourse(course.id)
            val confirmedByWatch = container.watch.deleteCourseFromWatch(course.name)
            _state.update {
                it.copy(
                    selected = it.selected.unless(courseId = course.id),
                    notice = "Deleted “${course.name}”",
                )
            }
            if (confirmedByWatch) {
                _state.update { it.copy(courses = it.courses.filterNot { c -> c.id == course.id }) }
            } else {
                loadLibrary()
            }
        }
    }

    // ── Sending to the watch, one thing at a time ────────────────────────────

    /**
     * Put this track on the watch now, if the watch is in range.
     *
     * Two steps that look like one. Flagging it is what makes the server owe
     * the file, and is the part that survives a watch being in another room —
     * so it happens first and unconditionally. The push is opportunistic: it
     * delivers immediately when the link happens to be up, and when it is not,
     * the flag is already set and the next sync carries it.
     *
     * Which is why failure here is reported as "queued" rather than as an
     * error. Nothing was lost.
     */
    fun sendCourseToWatch(course: CourseSummary) {
        // Nothing to push: a track with no server id yet has no filename to
        // push under. The library already disables this button for such a
        // row; guarded again here since this view model, not the button, is
        // the one thing that must not be wrong.
        if (course.id < 0) return
        viewModelScope.launch {
            patchCourseNow(course, CourseUpdate(loadToDevice = true))
            deliver("“${course.name}”") { container.watch.pushCourseNow(container.client(), course.id) }
        }
    }

    /**
     * Put this place on the watch now.
     *
     * Sends every place that is flagged, not only this one, and the receipt
     * says so. That is not a shortcut: a watch keeps all its saved places in
     * one file, so there is no such thing as writing one of them — the file
     * that carries this waypoint carries the others or deletes them.
     */
    fun sendWaypointToWatch(waypoint: Waypoint) {
        // See the note on [sendCourseToWatch].
        if (waypoint.id < 0) return
        viewModelScope.launch {
            patchWaypointNow(waypoint, WaypointUpdate(loadToDevice = true))
            deliver("“${waypoint.name}”") { container.watch.pushWaypointsNow(container.client()) }
        }
    }

    /**
     * Take a track off the watch.
     *
     * Bluetooth can now do this. The watch names a course by a UUID of its own
     * and reads the phone's library during an Explore sync, so a removal is a
     * tombstone the phone serves rather than a message the watch replies to —
     * see `ExploreSyncHandler`. Flagging the server first is still
     * unconditional, because that is the part that survives the watch being in
     * another room.
     *
     * The two notices say different things on purpose. "Coming off now" means a
     * sync is running and carrying the removal. "Next time it connects" means
     * the tombstone is recorded but no watch is listening yet — no longer the
     * old promise of a cable sync, which was true then and would be a lie now.
     */
    fun removeCourseFromWatch(course: CourseSummary) {
        // See the note on [sendCourseToWatch]: nothing local can be on the
        // watch at all yet.
        if (course.id < 0) return
        viewModelScope.launch {
            patchCourseNow(course, CourseUpdate(loadToDevice = false))
            val started = container.watch.deleteCourseFromWatch(course.name)
            _state.update {
                it.copy(
                    notice = if (started) {
                        "Taking “${course.name}” off the watch"
                    } else {
                        "“${course.name}” will come off the watch next time it connects"
                    }
                )
            }
        }
    }

    /**
     * Take a place off the watch, now, over Bluetooth.
     *
     * Removing a place IS a write: the locations file is rebuilt without it and
     * pushed over the same link that put it there.
     *
     * Including the last one, which used to be the exception. Bluetooth has no
     * delete operation, so emptying the list was answered with "on the next
     * cable sync" — true, and no use to somebody who wants it gone now. The
     * server hands this transport an *empty* locations file instead, which the
     * watch reads as having no saved places. Same outcome, by a route the link
     * supports.
     */
    fun removeWaypointFromWatch(waypoint: Waypoint) {
        // See the note on [sendCourseToWatch].
        if (waypoint.id < 0) return
        viewModelScope.launch {
            patchWaypointNow(waypoint, WaypointUpdate(loadToDevice = false))
            deliver("“${waypoint.name}”", removed = true) { container.watch.pushWaypointsNow(container.client()) }
        }
    }

    /**
     * Push whatever the server now owes that matches, and say what happened.
     *
     * [WatchManager.Outcome.Nothing] is reported as already-done rather than as
     * a failure: it means the server had nothing owing, which for a send is
     * exactly "it is already on the watch".
     */
    private suspend fun deliver(
        what: String,
        removed: Boolean = false,
        push: suspend () -> WatchManager.Outcome,
    ) {
        // "Sending" for a removal was a small lie with a real cost: it is the
        // one moment somebody is watching to see whether the thing they just
        // deleted is going away, and the app appeared to be doing the opposite.
        _state.update {
            it.copy(
                notice = if (removed) "Taking $what off the watch…" else "Sending $what to the watch…",
            )
        }
        val outcome = push()
        val message = when (outcome) {
            is WatchManager.Outcome.Sent ->
                if (removed) "$what is off the watch" else "$what is on the watch"

            WatchManager.Outcome.Nothing ->
                if (removed) "$what was not on the watch" else "$what is already on the watch"

            // Not "the watch is not reachable": it frequently is, and refused —
            // which the watch says precisely and this now passes on. Either way
            // the server still owes the change, so it is queued rather than
            // lost, and saying so is the part that matters.
            is WatchManager.Outcome.Failed ->
                "Queued for the next sync — ${outcome.reason}"
        }
        _state.update { it.copy(notice = message) }
        loadLibrary()
    }

    /**
     * Patch and return the updated row.
     *
     * Never fails outward any more: offline, [OfflineRepository.updateCourse]
     * queues the write (or folds it into a create still waiting to send) and
     * hands back the row patched locally, so the caller always has something
     * to show.
     */
    private suspend fun patchCourseNow(course: CourseSummary, update: CourseUpdate): CourseSummary {
        val updated = container.mapItems.updateCourse(course.id, update) ?: return course
        _state.update { state ->
            state.copy(
                courses = state.courses.map { if (it.id == course.id) updated else it },
                // And the line on the map, which is drawn from this document
                // and not from the list above it. See [restyleCourse] — without
                // this the track keeps its old colour until the screen is
                // closed and reopened.
                courseGeoJson = restyleCourse(
                    state.courseGeoJson,
                    courseId = updated.id,
                    color = update.color,
                    name = update.name,
                ),
                selected = (state.selected as? Selection.Track)
                    ?.takeIf { it.course.id == course.id }
                    ?.let { open -> Selection.Track(open.course.mergedWith(updated)) }
                    ?: state.selected,
            )
        }
        return updated
    }

    /** As [patchCourseNow], for a place. */
    private suspend fun patchWaypointNow(waypoint: Waypoint, update: WaypointUpdate): Waypoint {
        val updated = container.mapItems.updateWaypoint(waypoint.id, update) ?: return waypoint
        _state.update { state ->
            state.copy(
                waypoints = state.waypoints.map { if (it.id == waypoint.id) updated else it },
                // The selection sheet shows the same row; leaving it stale
                // would have a place claim it was still off the watch straight
                // after the button that put it on.
                selected = (state.selected as? Selection.Place)
                    ?.takeIf { it.waypoint.id == waypoint.id }
                    ?.let { Selection.Place(updated) }
                    ?: state.selected,
            )
        }
        return updated
    }

    fun renameCourse(course: CourseSummary, name: String) =
        patchCourse(course, CourseUpdate(name = name))

    fun renameWaypoint(waypoint: Waypoint, name: String) =
        patchWaypoint(waypoint, WaypointUpdate(name = name))

    fun dismissPoint() {
        pointJob?.cancel()
        _state.update { it.copy(point = null, pointLoading = false, weatherLoading = false) }
    }

    // ── Searching ────────────────────────────────────────────────────────────

    /**
     * Debounced, because this runs per keystroke and each one is a query
     * against the gazetteer. Cancelling the previous job also means an
     * in-flight search for "lak" cannot land after "lake" and replace it.
     */
    /**
     * The server's gazetteer first, and the phone's own downloaded copy —
     * see [com.tracks.app.map.OfflinePoiData] — only when that could not be
     * reached at all. Live full-text and trigram search beats a substring
     * match whenever there is a network to ask, so the fallback triggers on
     * failure rather than on an empty result: a query that genuinely has no
     * match should say so, not quietly repeat itself against a smaller index
     * and still find nothing.
     */
    fun search(query: String, near: LatLng?) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            val hits = runCatching {
                container.client().searchPoi(query, near?.latitude, near?.longitude)
            }.getOrNull() ?: container.offlinePoiData().search(query)
            _state.update { it.copy(searching = false, searchResults = hits) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchResults = emptyList(), searching = false) }
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    fun addWaypoint(position: LatLng) {
        val waypoints = _state.value.route.waypoints + position
        _state.update { it.copy(route = it.route.copy(waypoints = waypoints, error = null)) }
        snap(waypoints)
    }

    /**
     * Drop one handle, wherever it sits in the line.
     *
     * This replaced an undo button, and is not the same thing. Undo could only
     * take back the most recent tap, so fixing the third point of a nine-point
     * route meant destroying six good ones to reach it — which in practice
     * meant living with the bad point. Reaching the same tool through the
     * handle itself also puts it where the mistake is.
     */
    fun removeWaypointAt(index: Int) {
        val waypoints = _state.value.route.waypoints.toMutableList()
        if (index !in waypoints.indices) return
        waypoints.removeAt(index)
        _state.update {
            it.copy(
                route = it.route.copy(
                    waypoints = waypoints,
                    // Cleared rather than left in place: a line from the old
                    // waypoints drawn under the new set is worse than no line.
                    geoJson = if (waypoints.size < 2) null else it.route.geoJson,
                    segments = if (waypoints.size < 2) emptyList() else it.route.segments,
                    error = null,
                )
            )
        }
        if (waypoints.size >= 2) snap(waypoints)
    }

    /**
     * Move a handle that is already down.
     *
     * [settled] is the difference between the frames of a drag and the moment
     * the finger lifts. Every intermediate position redraws the handles, which
     * is free; only the last one re-routes, because snapping is a request and
     * one per frame would be a hundred requests for one adjustment — and every
     * one of them would land out of order.
     */
    fun moveWaypoint(index: Int, position: LatLng, settled: Boolean) {
        val waypoints = _state.value.route.waypoints.toMutableList()
        if (index !in waypoints.indices) return
        if (waypoints[index].sameAs(position) && !settled) return
        waypoints[index] = position
        _state.update { it.copy(route = it.route.copy(waypoints = waypoints, error = null)) }
        if (settled && waypoints.size >= 2) snap(waypoints)
    }

    fun clearRoute() {
        groundJob?.cancel()
        _state.update { it.copy(route = RouteDraft(snapToTrails = it.route.snapToTrails)) }
    }

    /**
     * Put the builder away, discarding the line.
     *
     * The bar used to carry a "Done" button beside this one, which was a third
     * answer to a question that has two: a drawn line either becomes a track or
     * it does not. Done left the draft alive but the tool closed, so the line
     * stayed on the map with nothing to save it — a state nobody wanted and
     * everybody could reach.
     */
    fun cancelRoute() {
        groundJob?.cancel()
        _state.update {
            it.copy(
                tool = MapTool.Inspect,
                route = RouteDraft(snapToTrails = it.route.snapToTrails),
            )
        }
    }

    /**
     * Follow the trails, or join the taps as drawn.
     *
     * Re-runs against the points already down rather than applying from the
     * next tap onward: the switch is pressed *because* the line on screen is
     * wrong, so leaving that line in place until another tap would answer the
     * wrong question.
     */
    fun setSnapToTrails(wanted: Boolean) {
        _state.update { it.copy(route = it.route.copy(snapToTrails = wanted, error = null)) }
        val waypoints = _state.value.route.waypoints
        if (waypoints.size >= 2) snap(waypoints)
    }

    /**
     * Keep the drawn line as a track.
     *
     * Saved on the server rather than the phone, because a track is one of the
     * things both apps write to and the desktop has to see it. The draft is
     * cleared on success — the line is now a track in the library, and leaving
     * a live builder over the top of it invites saving the same thing twice.
     */
    fun saveTrack(name: String, color: String, sport: String = "hiking") {
        val draft = _state.value.route
        val coordinates = draft.coordinates()
        if (coordinates.size < 2) {
            _state.update { it.copy(notice = "A track needs at least two points") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(route = it.route.copy(saving = true), notice = null) }
            // Saved locally, stats and all (see LocalMapItems.createCourse):
            // the track is whole the moment it exists, server or not.
            val created = container.mapItems.createCourse(
                CourseCreate(
                    name = name.ifBlank { "New track" },
                    color = color,
                    sport = sport,
                    coords = coordinates,
                ),
            )
            if (created == null) {
                _state.update { it.copy(route = it.route.copy(saving = false), notice = "A track needs at least two points") }
                return@launch
            }

            _state.update {
                it.copy(
                    // Back to inspecting. The builder's bar covers a third of a
                    // phone screen, and leaving it up over a finished track
                    // reads as "not saved yet" — the one thing it is not.
                    tool = MapTool.Inspect,
                    route = RouteDraft(snapToTrails = it.route.snapToTrails),
                    courses = it.courses + created,
                    notice = "Saved “${created.name}”",
                )
            }
            loadLibrary()
            // Drawn for a race plan: it becomes that race's course (the same
            // GPX parse as a file), and the user goes back to the plan.
            container.pendingRaceCourse.value?.let { goalUid ->
                container.pendingRaceCourse.value = null
                val goal = container.sources.goals().firstOrNull { it.uid == goalUid }
                val trackUid = container.sources.row("track", created.id)?.uid
                if (goal != null && trackUid != null) {
                    com.tracks.core.local.LocalRacePlans(container.sources).importTrack(goal, trackUid)
                }
                container.pendingRoute.value = com.tracks.app.ui.Destination.RacePlans.route
            }
        }
    }

    /**
     * Take in a GPX or FIT from the phone's storage.
     *
     * The one route into the library that does not start on this map — a track
     * someone emailed you, or an export from another app — and its absence is
     * why a phone was previously the worst place to receive one.
     *
     * The library is reloaded rather than the result appended, because an
     * import also has geometry the map has to draw, and that comes from a
     * different endpoint than the row.
     */
    fun importTrack(filename: String, bytes: ByteArray) {
        viewModelScope.launch {
            _state.update { it.copy(notice = "Reading $filename…") }
            val imported = runCatching { container.client().importCourse(filename, bytes) }
                .getOrElse { e ->
                    _state.update { it.copy(notice = "Could not import $filename: ${e.message}") }
                    return@launch
                }
            // Parsed by the server (GPX and course-FIT reading is not ported
            // yet), so the new row reaches this phone the way every row does —
            // by pull — before it can be shown.
            runCatching { container.syncWithServer(withHistory = false) }
            _state.update { it.copy(notice = "Imported “${imported.name}”") }
            loadLibrary()
            container.mapItems.courses().firstOrNull { it.name == imported.name }?.let(::showOnMap)
        }
    }

    /**
     * Ask the watch what it is already carrying.
     *
     * The gap this closes: a watch loaded with routes from Garmin Connect, or
     * places marked on the wrist, was invisible here. Tracks only ever learned
     * about them over the USB agent, and a phone had no way to ask at all —
     * courses and locations are marked as files a companion app does not fetch,
     * which is the right default for a sync that runs every hour and the wrong
     * answer to "show me my stuff".
     *
     * Deliberately a button rather than something that happens on its own.
     * It needs the watch awake and in range, it takes as long as the files are
     * large, and it is the kind of thing somebody does once after setting the
     * app up.
     */
    fun readFromWatch() {
        viewModelScope.launch {
            _state.update { it.copy(notice = "Asking the watch what it holds…") }
            val outcome = runCatching { container.watchSync.readSavedItems() }
                .getOrElse { e ->
                    _state.update { it.copy(notice = "Could not reach the watch: ${e.message}") }
                    return@launch
                }

            val message = when {
                outcome.alreadyRunning -> "A sync is already running"
                !outcome.paired -> "No watch is paired yet"
                outcome.notRegistered -> "Signed out — the files could not be sent to the server"
                // Counted rather than assumed: the watch answers with whatever
                // it has, and "nothing new" is a real and common answer that
                // should not read as a failure.
                else -> {
                    val saved = outcome.pulled.count { file ->
                        file.kind == PulledFileKind.COURSE || file.kind == PulledFileKind.PLACES
                    }
                    if (saved == 0) "The watch had nothing new to hand over"
                    else "Read $saved file${if (saved == 1) "" else "s"} off the watch"
                }
            }
            _state.update { it.copy(notice = message) }
            loadLibrary()
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    // ── Selecting a saved thing ──────────────────────────────────────────────

    /**
     * Open a track, fetching what the list does not carry.
     *
     * The summary is shown immediately and the profile fills in behind it. A
     * spinner over the name and distance we already have would be a slower way
     * to say nothing.
     */
    fun selectCourse(course: CourseSummary) {
        _state.update { it.copy(selected = Selection.Track(course.detailShell()), notice = null) }
        viewModelScope.launch {
            val full = runCatching { container.mapItems.course(course.id) }.getOrNull() ?: return@launch
            _state.update { state ->
                val open = state.selected
                if (open is Selection.Track && open.course.id == full.id) state.copy(selected = Selection.Track(full)) else state
            }
        }
    }

    fun selectWaypoint(waypoint: Waypoint) {
        _state.update { it.copy(selected = Selection.Place(waypoint), notice = null) }
    }

    /** Open whichever saved thing sits at a tapped position, if one does. */
    fun selectAt(courseId: Int?, waypointId: Int?): Boolean {
        waypointId?.let { id ->
            _state.value.waypoints.firstOrNull { it.id == id }?.let {
                selectWaypoint(it)
                return true
            }
        }
        courseId?.let { id ->
            _state.value.courses.firstOrNull { it.id == id }?.let {
                selectCourse(it)
                return true
            }
        }
        return false
    }

    fun clearSelection() {
        _state.update { it.copy(selected = null) }
    }

    /** Point the map at something, and close whatever was covering it. */
    fun showOnMap(course: CourseSummary) {
        val bounds = course.bounds?.takeIf { it.size == 4 }
        _state.update {
            it.copy(
                focus = bounds?.let(Focus::Area),
                selected = null,
                // A track with no stored bounds cannot be framed, and silently
                // doing nothing reads as a broken button.
                notice = if (bounds == null) "That track has no saved extent yet" else null,
            )
        }
    }

    fun showOnMap(waypoint: Waypoint) {
        _state.update {
            it.copy(focus = Focus.Point(waypoint.lat, waypoint.lng), selected = null)
        }
    }

    /** The screen has moved the camera; do not move it again on recomposition. */
    fun focusHandled() {
        _state.update { it.copy(focus = null) }
    }

    /**
     * Ask the server to lay the line on real ground.
     *
     * BRouter follows paths, so the drawn line is not the tapped one: two taps
     * either side of a ridge become the trail that actually goes over it, which
     * is the whole reason for snapping rather than drawing straight segments.
     */
    private fun snap(waypoints: List<LatLng>) {
        if (waypoints.size < 2) return

        // Snapping off means the line IS the taps. Built here rather than left
        // to the drawing code so that everything downstream — the distance
        // readout, the saved track, the layer on the map — reads one geometry
        // and cannot disagree about what was drawn.
        if (!_state.value.route.snapToTrails) {
            _state.update { it.copy(route = it.route.withStraightLine(waypoints)) }
            sampleGround(waypoints)
            return
        }

        // Refused before it is sent. `POST /maps/route/snap` calls
        // `ensure_segments` for the bounding box of the request first, and
        // BRouter's segments are 5-degree tiles it fetches on demand — so two
        // taps a continent apart do not fail, they hang while the server pulls
        // routing data for everything in between. Found exactly that way, with
        // waypoints in the Atlantic and central Africa.
        if (waypoints.spansTooFar()) {
            _state.update {
                it.copy(
                    route = it.route.copy(
                        snapping = false,
                        error = "Those points are too far apart to route between",
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(route = it.route.copy(snapping = true, error = null)) }
            val coordinates = waypoints.map { listOf(it.longitude, it.latitude) }

            // The phone first, whenever it holds the data.
            //
            // Not "offline as a fallback": trying the network first would mean
            // a trip with one weak bar waits out a 15-second connect timeout
            // before using segments already on disk, which is the worst of both
            // and precisely the case this exists for. The phone and the server
            // run the same engine over the same segments, so preferring the
            // local one costs nothing when both are available.
            val local = container.offlineRouter().snap(coordinates)
            if (local != null) {
                _state.update { it.copy(route = it.route.withSnapped(local, waypoints)) }
                return@launch
            }

            val result = runCatching { container.client().snapRoute(coordinates) }
            _state.update { current ->
                val draft = result.fold(
                    onSuccess = { geoJson -> current.route.withSnapped(geoJson, waypoints) },
                    onFailure = { e ->
                        current.route.copy(
                            snapping = false,
                            // Two different failures, and the difference is
                            // actionable: one is fixed by moving, the other by
                            // downloading the area before leaving.
                            error = if (offlineDataMissing(waypoints)) {
                                "No routing data for this area — download it with the " +
                                    "region to plan routes offline"
                            } else {
                                "Could not reach the routing service: ${e.message}"
                            },
                        )
                    },
                )
                current.copy(route = draft)
            }
        }
    }

    /**
     * Ask the server how high the ground is under a line it did not route.
     *
     * A snapped route arrives with elevation already in it, because BRouter
     * routes on elevation. A line joined straight through the taps has none at
     * all — those points came off a touchscreen — so the profile used to simply
     * vanish the moment snapping was switched off, which is exactly backwards:
     * the un-snapped line is the bushwhack, the ski descent, the ridge, and
     * whether it climbs is the first thing you want to know about it.
     *
     * Best-effort by design. Failure leaves the distance and the line intact
     * and costs only the chart, so it is not reported as an error — there is
     * nothing for the user to do about a DEM that has no tile here.
     *
     * Offline, or when the bulk endpoint alone fails, falls back to
     * [com.tracks.app.map.OfflineDem] sampling every point itself — the same
     * tiles [inspect] uses, one request or disk read per point rather than
     * one for the whole line. [sampledProfile] already drops points with no
     * elevation instead of drawing them at sea level, so a partially-covered
     * line still profiles the part that is.
     */
    private fun sampleGround(waypoints: List<LatLng>) {
        groundJob?.cancel()
        val samples = densify(waypoints)
        if (samples.size < 2) return

        groundJob = viewModelScope.launch {
            val client = container.client()
            val elevations = runCatching {
                client.routeElevation(samples.map { listOf(it.longitude, it.latitude) })
            }.getOrNull() ?: container.offlineDem().elevations(client, samples.map { it.latitude to it.longitude })

            val profile = sampledProfile(samples, elevations)
            if (profile.isEmpty()) return@launch
            _state.update { state ->
                // Only if the line has not moved on. A slow sample landing after
                // another tap would draw the previous route's climbing under the
                // new one's distance.
                if (state.route.waypoints != waypoints) {
                    state
                } else {
                    state.copy(
                        route = state.route.copy(
                            profile = profile,
                            ascentMetres = filteredAscent(profile),
                        )
                    )
                }
            }
        }
    }

    /** Whether the phone lacks the segments for these points, as opposed to
     *  having them and the search having simply failed. */
    private fun offlineDataMissing(waypoints: List<LatLng>): Boolean {
        val lngs = waypoints.map { it.longitude }
        val lats = waypoints.map { it.latitude }
        return !container.offlineRoutingData()
            .covers(lngs.min(), lats.min(), lngs.max(), lats.max())
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        val COURSE_LIST = ListSerializer(CourseSummary.serializer())
        val WAYPOINT_LIST = ListSerializer(Waypoint.serializer())
    }
}

/**
 * Read BRouter's summary off the feature it returns.
 *
 * The properties are strings in a loose bag whose keys vary by profile and
 * version, so every one is optional and parsed defensively: a missing or
 * unparseable `track-length` costs the distance readout, not the route.
 */
private fun RouteDraft.withSnapped(geoJson: String, handles: List<LatLng>): RouteDraft {
    val properties = runCatching {
        JSONObject(geoJson)
            .getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("properties")
    }.getOrNull()

    return copy(
        snapping = false,
        geoJson = geoJson,
        profile = elevationProfile(geoJson),
        segments = routeSegments(lineCoordinates(geoJson), handles),
        metres = properties?.optDouble("track-length")?.takeIf { !it.isNaN() },
        // BRouter's own name for it, and the one worth showing: "plain-ascend"
        // counts every metre of noise in the elevation model, while "filtered
        // ascend" is what a person would call the climbing.
        ascentMetres = properties?.optDouble("filtered ascend")?.takeIf { !it.isNaN() },
        seconds = properties?.optDouble("total-time")?.takeIf { !it.isNaN() },
        error = null,
    )
}

/**
 * Whether two point-info snapshots describe the same tap.
 *
 * Compared on position rather than by identity because the object is rebuilt as
 * each half of the answer lands. The tolerance is far below a pixel at any zoom
 * the map allows, so it cannot merge two deliberate taps.
 */
private fun PointInfo.samePlaceAs(other: PointInfo): Boolean =
    kotlin.math.abs(lat - other.lat) < 1e-9 && kotlin.math.abs(lon - other.lon) < 1e-9

/** The selection, unless it is the thing that just stopped existing. */
private fun Selection?.unless(courseId: Int? = null, waypointId: Int? = null): Selection? =
    when {
        this is Selection.Track && course.id == courseId -> null
        this is Selection.Place && waypoint.id == waypointId -> null
        else -> this
    }

/**
 * The list row as a detail, for the moment before the real one arrives.
 *
 * Everything here is already known; only the profile is missing, and that is
 * exactly the field the sheet renders as absent until it lands.
 */
private fun CourseSummary.detailShell() = CourseDetail(
    id = id, name = name, color = color, sport = sport,
    distanceMetres = distanceMetres, ascentMetres = ascentMetres,
    loadToDevice = loadToDevice, deviceStatus = deviceStatus,
    isExternal = isExternal, bounds = bounds,
)

/**
 * A detail refreshed from a newer summary, keeping what the summary lacks.
 *
 * Sending a track to the watch returns a summary with no profile in it; copying
 * that over the open sheet would blank the elevation chart the user is looking
 * at as a side effect of pressing an unrelated button.
 */
private fun CourseDetail.mergedWith(summary: CourseSummary) = copy(
    name = summary.name,
    color = summary.color,
    sport = summary.sport,
    distanceMetres = summary.distanceMetres,
    ascentMetres = summary.ascentMetres,
    loadToDevice = summary.loadToDevice,
    deviceStatus = summary.deviceStatus,
    isExternal = summary.isExternal,
    bounds = summary.bounds ?: bounds,
)

/**
 * The taps, joined as drawn.
 *
 * Shaped as the same one-feature FeatureCollection BRouter returns, so the
 * layer that draws a route needs no idea which of the two produced it.
 *
 * No elevation profile, and that is not an omission: these points came off a
 * touchscreen and carry no height at all. Sampling a DEM for them would need
 * the server, which is the one thing a straight line does not — and inventing a
 * flat profile would read as flat ground.
 */
private fun RouteDraft.withStraightLine(waypoints: List<LatLng>): RouteDraft {
    val coordinates = waypoints.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    var metres = 0.0
    waypoints.zipWithNext { from, to -> metres += from.metresTo(to) }

    return copy(
        snapping = false,
        geoJson = """{"type":"FeatureCollection","features":[{"type":"Feature",""" +
            """"properties":{},"geometry":{"type":"LineString",""" +
            """"coordinates":[$coordinates]}}]}""",
        // Cleared rather than kept: the old profile described a different line,
        // and [MapToolsViewModel.sampleGround] fills this in a moment later.
        profile = emptyList(),
        segments = routeSegments(
            waypoints.map { listOf(it.longitude, it.latitude) }, waypoints,
        ),
        metres = metres,
        ascentMetres = null,
        seconds = null,
        error = null,
    )
}

/**
 * The positions of a one-line GeoJSON document, `[lng, lat, ele?]` as stored.
 *
 * Kept whole rather than trimmed to two ordinates: BRouter's third one is a
 * real elevation, and the server writes it into the saved track's profile.
 */
internal fun lineCoordinates(geoJson: String): List<List<Double>> = runCatching {
    val positions = JSONObject(geoJson)
        .getJSONArray("features")
        .getJSONObject(0)
        .getJSONObject("geometry")
        .getJSONArray("coordinates")

    (0 until positions.length()).mapNotNull { index ->
        val position = positions.optJSONArray(index) ?: return@mapNotNull null
        (0 until position.length())
            .map { position.optDouble(it) }
            .takeIf { ordinates -> ordinates.size >= 2 && ordinates.none { it.isNaN() } }
    }
}.getOrDefault(emptyList())

/**
 * Whether a set of waypoints covers more ground than a route sensibly can.
 *
 * Three degrees is roughly 330 km — long for a multi-day route and nowhere near
 * the continental spans that make the server hang. A real limit is needed on
 * some side of this and the client is the side that knows what was tapped.
 */
private fun List<LatLng>.spansTooFar(): Boolean {
    if (size < 2) return false
    val latSpan = maxOf { it.latitude } - minOf { it.latitude }
    val lngSpan = maxOf { it.longitude } - minOf { it.longitude }
    return latSpan > MAX_ROUTE_DEGREES || lngSpan > MAX_ROUTE_DEGREES
}

private const val MAX_ROUTE_DEGREES = 3.0


/**
 * Distance-and-height pairs along a snapped line.
 *
 * GeoJSON positions carry an optional third ordinate for altitude, and BRouter
 * fills it in. Points with no altitude are dropped rather than drawn at zero —
 * a profile that dives to sea level for a gap in the data is worse than a
 * shorter profile.
 *
 * Distance is accumulated with the equirectangular approximation, which over a
 * few metres between consecutive route points is accurate to well under the
 * width of the line being drawn, and costs no trigonometry per point beyond one
 * cosine.
 */
internal fun elevationProfile(geoJson: String): List<RoutePoint> = runCatching {
    val coordinates = JSONObject(geoJson)
        .getJSONArray("features")
        .getJSONObject(0)
        .getJSONObject("geometry")
        .getJSONArray("coordinates")

    val out = ArrayList<RoutePoint>(coordinates.length())
    var travelled = 0.0
    var previous: DoubleArray? = null

    for (i in 0 until coordinates.length()) {
        val position = coordinates.optJSONArray(i) ?: continue
        if (position.length() < 3) continue
        val lng = position.optDouble(0)
        val lat = position.optDouble(1)
        val ele = position.optDouble(2)
        if (lng.isNaN() || lat.isNaN() || ele.isNaN()) continue

        previous?.let { (plng, plat) ->
            val meanLat = Math.toRadians((lat + plat) / 2)
            val dx = Math.toRadians(lng - plng) * kotlin.math.cos(meanLat) * EARTH_RADIUS_M
            val dy = Math.toRadians(lat - plat) * EARTH_RADIUS_M
            travelled += kotlin.math.hypot(dx, dy)
        }
        previous = doubleArrayOf(lng, lat)
        out.add(RoutePoint(travelled, ele))
    }
    out
}.getOrDefault(emptyList())

private operator fun DoubleArray.component1() = this[0]
private operator fun DoubleArray.component2() = this[1]

private const val EARTH_RADIUS_M = 6_371_000.0
