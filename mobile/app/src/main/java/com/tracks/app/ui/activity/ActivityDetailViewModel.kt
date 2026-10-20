// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.spec.ZoneRange
import com.tracks.core.spec.displayHrZones
import com.tracks.core.spec.sportType
import com.tracks.core.api.ActivityDetail
import com.tracks.core.api.ClimbSplit
import com.tracks.core.api.Lap
import com.tracks.core.api.StrengthSet
import com.tracks.core.api.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val data: ActivityDetailData? = null,
    val error: String? = null,
    /**
     * The map styles for the route, one per theme, or null when there is not
     * one to hand.
     *
     * The plain backdrop the web draws its activity maps over
     * (`/maps/backdrop.json`), not the Map tab's planning style: that one has
     * no dark palette, and a white map in the middle of a dark detail screen
     * was what the desktop had already stopped doing. The screen picks the
     * one matching the app's theme.
     *
     * Read from the on-disk cache before anything is fetched: the detail
     * screen must open instantly and offline, and a style download per
     * activity would make it do neither. Null for both means no style has
     * ever been seen on this install, and the route falls back to the
     * projection-only drawing.
     */
    val backdropLight: String? = null,
    val backdropDark: String? = null,
)

/**
 * Loads one activity, in three independent pieces.
 *
 * The pieces have genuinely different failure modes and the screen should not
 * lose all three to the weakest: the detail row needs only a token, laps need
 * the same, and the GPS track needs an *open vault* because lat/lng are
 * encrypted columns. On a phone whose crypto session has lapsed — which
 * happens weekly by design, and on every Redis restart — demanding all three
 * would turn "your map is unavailable" into "this activity failed to load".
 *
 * So the track is awaited separately and its failure is recorded rather than
 * thrown, and laps degrade to an empty list.
 *
 * ## Offline
 *
 * Each piece is cached under the activity's own id as it arrives, and read back
 * before anything is requested. Opening an activity once makes it readable
 * forever — the detail, the laps, the climbs, the sets and the GPS track — which
 * is what someone reviewing yesterday's ride from a hut actually needs.
 *
 * Caching on open rather than mirroring everything is the deliberate part.
 * Every lap, climb, set and GPS point of a decade of training is hundreds of
 * megabytes, nearly all of it for activities nobody will look at again.
 */
class ActivityDetailViewModel(
    private val container: AppContainer,
    private val activityId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
        loadMapStyle()
    }

    private fun loadMapStyle() {
        viewModelScope.launch {
            fun read(cache: com.tracks.app.MapStyleCache) = runCatching { cache.read() }.getOrNull()
            val (cachedLight, cachedDark, cachedPlanning) = withContext(Dispatchers.IO) {
                Triple(
                    read(container.backdropStyleCache(false)),
                    read(container.backdropStyleCache(true)),
                    read(container.mapStyleCache()),
                )
            }
            // Shown immediately when there is one, so the route draws on a
            // basemap without waiting for the network — the whole point of
            // caching it. The planning style stands in for a palette not yet
            // cached: light in a dark screen, but a map.
            publish(cachedLight ?: cachedPlanning, cachedDark ?: cachedPlanning)

            // Then refreshed anyway, rather than returning here. The style is
            // not a constant: the server narrows each source's zoom range to
            // the tile archives that exist (`services/tile_coverage.py`), so
            // downloading a region changes it. A cache that is only ever
            // written when empty pins a phone to whatever style it happened to
            // see first, which is exactly how a device ended up requesting
            // z13-15 basemap tiles that 404 and drawing a blank map.
            //
            // Best-effort: failing costs the refresh, not the cached copy.
            // The same caches the dashboard's heatmap writes, so either screen
            // warms the other.
            suspend fun fetch(dark: Boolean): String? =
                runCatching { container.client().backdropStyleJson(dark) }.getOrNull()
                    ?.also { fresh -> withContext(Dispatchers.IO) { container.backdropStyleCache(dark).write(fresh) } }
            val light = async { fetch(dark = false) }
            val dark = async { fetch(dark = true) }
            publish(light.await(), dark.await())
        }
    }

    private fun publish(light: String?, dark: String?) {
        _state.update {
            it.copy(backdropLight = light ?: it.backdropLight, backdropDark = dark ?: it.backdropDark)
        }
    }

    /**
     * Rename this activity.
     *
     * Written through and then merged into the state in hand rather than
     * refetched: the response carries the whole updated activity, and a reload
     * would re-pull the track, the laps, the climbs and the sets to change one
     * string.
     *
     * A blank name is a delete, not a rename, and this does not do deletes —
     * the server would take it and leave the activity with no title at all.
     *
     * Queued when there is no signal, and applied locally either way — to the
     * screen, to this activity's cached detail, and to the row the list reads
     * from — so the new name is the name everywhere, now, and the server is
     * told when it can be.
     *
     * There is no longer a "could not rename" dialog, because there is no
     * longer a case for one: a rename either goes out or waits, and a modal
     * saying the server did not take it would be telling the user about the
     * network rather than about their activity.
     */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed == _state.value.data?.detail?.name) return
        viewModelScope.launch {
            // A source edit on a derived row (spec/sync.yaml, `activity`):
            // the measurements are the file's, the name is the person's.
            container.sources.editActivity(activityId, mapOf("name" to trimmed))
            _state.update { state ->
                state.copy(data = state.data?.let { it.copy(detail = it.detail.copy(name = trimmed)) })
            }
        }
    }

    /**
     * Save the activity's notes.
     *
     * A field of the activity's synced row (spec/sync.yaml, `activity`), so
     * the web reads the same note and an edit there comes back here. Written
     * only when it changed — writing an unchanged note would stamp it and let
     * this phone's copy beat an edit made on the desktop since.
     *
     * Blank is "no note", stored as null, so clearing the field removes the
     * snippet from the bar rather than leaving an empty one.
     */
    fun saveNotes(notes: String) {
        val current = _state.value.data?.detail ?: return
        val trimmed = notes.trim().ifBlank { null }
        if (trimmed == current.notes?.trim()?.ifBlank { null }) return
        viewModelScope.launch {
            container.sources.editActivity(activityId, mapOf("notes" to trimmed))
            _state.update { state ->
                state.copy(data = state.data?.let { it.copy(detail = it.detail.copy(notes = trimmed)) })
            }
        }
    }

    /**
     * Hide this activity from the list, the dashboard and training load.
     *
     * The contract's `hidden` field rather than a delete: a hidden activity
     * keeps its data and can be shown again, where a delete is final (delete
     * wins everywhere, by design) — so hiding is the undoable choice.
     */
    fun hide(onDone: () -> Unit) {
        viewModelScope.launch {
            container.sources.editActivity(activityId, mapOf("hidden" to true))
            onDone()
        }
    }

    /** Delete this activity for good. Its file is kept, but no copy of it ever brings the activity back. */
    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            container.sources.deleteActivity(activityId)
            onDone()
        }
    }

    /**
     * Everything here is read from what the phone parsed out of the activity's
     * own FIT file — laps, climbs, sets and the GPS track included — so it
     * opens in full with no server at all.
     */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val pieces = withContext(Dispatchers.Default) {
                runCatching { container.activityDetail.load(activityId) }.getOrNull()
            }
            if (pieces == null) {
                _state.update { it.copy(loading = false, error = "This activity is not on this phone.") }
                return@launch
            }
            // The user's own max HR (set, or worked out from history), so
            // zones mean the same thing on every activity; the activity's own
            // peak only when the user has none yet.
            val userMaxHr = runCatching { container.sources.importThresholds().maxHr?.toInt() }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    error = null,
                    data = ActivityDetailData(
                        detail = pieces.detail,
                        sportType = sportType(pieces.detail.sport, pieces.detail.subSport),
                        laps = pieces.laps,
                        climbs = pieces.climbs,
                        sets = pieces.sets,
                        hrZones = zonesFor(pieces.detail, userMaxHr),
                        track = pieces.track,
                    ),
                )
            }
        }
    }

    /**
     * The athlete's heart-rate zones for this activity.
     *
     * Derived from max HR because that is the only threshold an activity row
     * carries — the user's configured LTHR lives in settings this screen does
     * not fetch. [displayHrZones] is the generated spec's own max-HR model, so
     * the boundaries match the browser's even though the input differs from the
     * server-side LTHR model.
     *
     * Empty when the activity records no max HR, which correctly hides the card
     * rather than inventing zones from a guess.
     */
    private fun zonesFor(detail: com.tracks.core.api.ActivityDetail, userMaxHr: Int?): List<ZoneRange> {
        val maxHr = userMaxHr ?: detail.maxHeartRate ?: return emptyList()
        if (maxHr <= 0) return emptyList()
        return displayHrZones(maxHr)
    }


}
