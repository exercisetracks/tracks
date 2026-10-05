// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.map.MapDownloadService
import com.tracks.app.map.OfflineEstimate
import com.tracks.app.map.OfflineRoutingData
import com.tracks.app.map.OfflineTiles
import com.tracks.app.map.PendingRegions
import com.tracks.core.api.MapRegion
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Where a download has got to, across both halves.
 *
 * The two are genuinely sequential and each can fail on its own, so this is one
 * ordered progression rather than two independent states — the UI reads it as a
 * single sentence about one area.
 */
sealed interface DownloadPhase {
    /** The server is extracting the area out of the planet archives. */
    data class Extracting(val fraction: Float, val detail: String?) : DownloadPhase

    /**
     * Built on the server, not stored here, and nobody asked for it to be.
     *
     * A real and now perfectly good state rather than a half-finished one: the
     * server serves this ground at full detail to anything with a connection,
     * so an area in this phase is a better map than the phone had before — it
     * is only the no-signal case it does not answer.
     */
    data object ServerOnly : DownloadPhase

    /** The server is done; the phone is pulling the tiles into its own store. */
    data class Storing(
        val fraction: Float,
        val bytes: Long,
        /** Set while MapLibre is retrying something: no connection, usually. */
        val waiting: String? = null,
    ) : DownloadPhase

    /**
     * Started on the phone and stopped part-way, with nothing working on it.
     *
     * Almost always a process that was killed mid-download. Kept distinct from
     * [Storing] because the two look identical on a progress bar and mean
     * opposite things about whether waiting will help.
     */
    data class Paused(val fraction: Float, val bytes: Long) : DownloadPhase

    data class Ready(val bytes: Long) : DownloadPhase
    data class Failed(val reason: String) : DownloadPhase
}

/**
 * What an outline on the map is promising.
 *
 * The distinction the border colour exists to draw. Ground the phone holds
 * works with the radio off; ground only the server holds is full detail while
 * there is signal and the coarse basemap without it. Those are different
 * promises and a trip is planned differently around each, so they are not the
 * same colour.
 */
enum class Coverage {
    /** Stored on this phone. Works with no signal. */
    Phone,

    /** The server serves it at full detail. Needs a connection. */
    Server,

    /** Being built or being fetched — not yet a promise about anything. */
    Building,
}

/** One area, as the downloads sheet shows it. */
data class RegionRow(
    val name: String,
    val phase: DownloadPhase,
    /** Null for a region that exists only on the server. */
    val storedId: Long? = null,
    /** Null for one the phone holds but the server has since forgotten. */
    val serverId: Int? = null,
    /** Where it is, for the outline drawn on the map. */
    val bounds: LatLngBounds? = null,
    /**
     * The area's true shape as GeoJSON, when it is not simply its box.
     *
     * Only merged areas have one — see [MapRegion.geometry]. Carried as the
     * raw document because the only thing that ever reads it is the map.
     */
    val shape: String? = null,
    /** What that outline claims — see [Coverage]. */
    val coverage: Coverage = Coverage.Building,
)

data class RegionsUiState(
    val loading: Boolean = true,
    val rows: List<RegionRow> = emptyList(),
    val storedBytes: Long = 0,
    /** Size of the currently framed area, from the server, before committing. */
    val estimateBytes: Long? = null,
    val suggestedName: String? = null,
    val estimating: Boolean = false,
    /** The framed area is beyond what is sensible to extract or store. */
    val tooLarge: Boolean = false,
    /**
     * What the framed area would cost *this phone*, worked out locally.
     *
     * Answered instantly and without the network, because it is arithmetic —
     * see [OfflineEstimate]. It appears while the server's byte estimate is
     * still being computed, which matters: the phone's number is the one that
     * decides whether a box is a sensible thing to ask for.
     */
    val phoneCost: OfflineEstimate.Weight? = null,
    /**
     * Fraction of the routing-data pull, or null when none is running.
     *
     * Its own field rather than a [DownloadPhase], because it is not per
     * region: BRouter's segments are 5° cells shared between every region that
     * touches them, so this belongs to the sheet, not to a row.
     */
    val routingProgress: Float? = null,
    val routingBytes: Long = 0,
    /**
     * Something worth saying that is not a failure.
     *
     * Separate from [error] because the cases it exists for — the server
     * already had this area, or an area too large for the phone that the server
     * will serve instead — are good outcomes shown in red if they borrow the
     * error slot.
     */
    val notice: String? = null,
    val error: String? = null,
)

/**
 * Downloading an area for offline use, in the two halves it really takes.
 *
 * The server extracts a bbox out of the planet archives into the master files
 * its tile endpoint serves; the phone then pulls that area into MapLibre's own
 * store. Both are needed and the order matters — see [OfflineTiles] for why
 * doing only the first looks like it works right up until there is no signal.
 *
 * The server half is polled rather than pushed. It is minutes of tippecanoe and
 * the API is a status endpoint, so there is nothing to subscribe to; polling
 * stops as soon as every region is settled, so an idle sheet costs nothing.
 *
 * ## What this now does that it did not
 *
 * Three things, all of them about a download surviving contact with a real
 * phone: it [resumes][OfflineTiles.resumeIncomplete] anything a killed process
 * left half-finished, it remembers across restarts which areas were *asked*
 * for so the hand-off from server to phone cannot be lost in a dead coroutine
 * (see [PendingRegions]), and it notices when the server's own coverage changes
 * so the map picks up detail that arrived while nobody was looking.
 */
class RegionsViewModel(
    private val container: AppContainer,
    private val offline: OfflineTiles,
    /**
     * Called when the ground the server can serve at full detail changes, so
     * the map can pick up the new style.
     *
     * The served style is narrowed to the archives on disk, so a region landing
     * does not merely add tiles — it adds whole *sources and layers*: contours
     * and the OSM overlay simply are not in the style until the first region
     * exists. A map holding the previous document renders the new area with
     * none of the detail that was just downloaded, which looks like the
     * download failed.
     *
     * Fires for regions built anywhere, not only for downloads started here. An
     * area cut from the browser is the case that used to go unnoticed on the
     * phone entirely.
     */
    private val onCoverageChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(RegionsUiState())
    val state: StateFlow<RegionsUiState> = _state.asStateFlow()

    private var poller: Job? = null
    private var estimateJob: Job? = null

    /** Server region ids whose phone half this view model is already running. */
    private val storing = mutableSetOf<Int>()

    /** Per-region retry notes from MapLibre, merged into the phase on reload. */
    private val waiting = mutableMapOf<Int, String>()

    /**
     * Whether the map is actually on screen, set by [MapScreen] from the
     * lifecycle. Read at the top of each poll rather than latched, so backing
     * out mid-download slows the next wait rather than the one after it.
     */
    private var onScreen = true

    /** See [BACKGROUND_POLL_MS] — this is the whole of the power discipline. */
    private val pollMs: Long
        get() = if (onScreen) POLL_MS else BACKGROUND_POLL_MS

    /** Whether this screen has been shown once already — see [setOnScreen]. */
    private var shownBefore = false

    /** Called with `true` on ON_START and `false` on ON_STOP. */
    fun setOnScreen(value: Boolean) {
        onScreen = value
        if (!value) return
        // Coming *back* is the interesting case: an area may have been cut in
        // the browser, or a download killed with the app, while this was away.
        // The first appearance is already covered by the constructor, and
        // refreshing twice on one open would resume every region twice.
        if (shownBefore) refresh()
        shownBefore = true
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // Before the first listing, so what it reports is already the
            // resumed state rather than a snapshot of the interruption.
            runCatching { offline.resumeIncomplete() }
                .onSuccess { if (it > 0) MapDownloadService.start(container.appContext) }
            reload()
            _state.update { it.copy(loading = false) }
            startPollingIfBusy()
        }
    }

    /**
     * What the visible area would cost, and what to call it.
     *
     * Asked as the user frames a download rather than after: the answer spans
     * tens of megabytes to several gigabytes depending on terrain, which on a
     * phone is the difference between "go ahead" and "wait for wifi". The name
     * comes from the server's geocoder so nobody has to type one.
     */
    fun estimate(bounds: LatLngBounds) {
        // Cancelled and debounced, because this fires on every frame of a
        // corner drag. Without it a single resize queues a dozen requests that
        // each take about four seconds, they finish out of order, and the size
        // shown ends up describing a box the user has already moved on from.
        estimateJob?.cancel()

        // Not debounced, because it costs nothing: a few hundred multiplications
        // against the tile pyramid, with no network in sight. It is also the
        // number that decides whether this box is sensible at all, so it should
        // never be the one that arrives last.
        val cost = OfflineEstimate.weigh(bounds)

        if (bounds.tooLargeToDownload()) {
            _state.update {
                it.copy(
                    estimating = false, estimateBytes = null, tooLarge = true,
                    phoneCost = cost, error = null,
                )
            }
            return
        }

        estimateJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    estimating = true, estimateBytes = null, tooLarge = false,
                    phoneCost = cost, error = null,
                )
            }
            delay(ESTIMATE_DEBOUNCE_MS)
            val client = container.client()
            val bbox = bounds.toBbox()

            // Together, not one after the other. The size is a dry run across
            // three archives and the name is a call out to a geocoder; they
            // have nothing to do with each other, and running them in sequence
            // made choosing an area feel broken for five seconds.
            val size = async { runCatching { client.regionEstimate(bbox).bytes }.getOrNull() }
            val name = async { runCatching { client.suggestRegionName(bbox).name }.getOrNull() }

            _state.update {
                it.copy(
                    estimating = false,
                    estimateBytes = size.await(),
                    suggestedName = name.await()?.takeIf(String::isNotBlank),
                    error = null,
                )
            }
        }
    }

    fun clearEstimate() {
        _state.update {
            it.copy(
                estimateBytes = null, suggestedName = null, tooLarge = false,
                phoneCost = null, error = null,
            )
        }
    }

    /**
     * Download the framed area, server first and then onto the phone.
     *
     * Returns immediately; both halves are watched from here so the sheet can
     * be closed and reopened without losing a download.
     */
    fun download(bounds: LatLngBounds, name: String) {
        // Refused here rather than trusted to the UI. The server applies no
        // size limit of its own — `parse_bbox` checks only that the corners are
        // the right way round — so a request framed at world zoom would set it
        // extracting the planet out of the archives and filling its disk. That
        // is a destructive operation reachable from one tap, and the guard
        // belongs on the side that knows what was framed.
        if (bounds.tooLargeToDownload()) {
            _state.update { it.copy(tooLarge = true) }
            return
        }
        val cost = OfflineEstimate.weigh(bounds)
        viewModelScope.launch {
            val client = container.client()
            val started = runCatching {
                client.downloadRegion(bounds.toBbox(), name)
            }.getOrElse { e ->
                _state.update {
                    it.copy(notice = null, error = "Could not start the download: ${e.message}")
                }
                return@launch
            }

            clearEstimate()
            val serverId = started.region?.id
            val serverName = started.region?.name ?: "That area"
            // The server answers "exists" when an area it already holds covers
            // what was framed. Nothing is extracted a second time, so the only
            // work left is the phone's half — which the rest of this function
            // does unchanged, because an already-installed region satisfies
            // awaitServerReady immediately.
            _state.update {
                it.copy(
                    error = null,
                    notice = when {
                        cost.level == OfflineEstimate.Level.BeyondPhone -> beyondPhoneNotice(cost)
                        started.status == "exists" ->
                            "$serverName is already on the server — copying it to this phone."
                        else -> null
                    },
                )
            }

            // Written before the wait, not after it. This is the whole point of
            // the record: the process may not survive the extraction, and the
            // intention has to.
            if (serverId != null && cost.level != OfflineEstimate.Level.BeyondPhone) {
                PendingRegions.want(container.appContext, serverId)
            }
            reload()
            startPollingIfBusy()

            // The phone's half waits for the server's. Pulling now would cache
            // the empty tiles that exist before the extraction has run — and
            // cache them as legitimately empty, which is indistinguishable from
            // a downloaded area with nothing in it.
            if (serverId == null) return@launch
            val ready = awaitServerReady(serverId) ?: return@launch
            // Before the tile pull, not after: MapLibre re-fetches the style
            // itself to work out what to download, so the map and the offline
            // store should both be looking at the version that includes the new
            // sources.
            coverageChanged()
            if (cost.level == OfflineEstimate.Level.BeyondPhone) {
                reload()
                return@launch
            }
            storeOnPhone(ready, bounds)
        }
    }

    /**
     * Pull a region the server already has onto the phone, or pick up one that
     * stopped part-way.
     *
     * Needed more often than it sounds. A region downloaded from the browser
     * has no phone copy at all; so does one whose phone half was interrupted,
     * or one built before this client knew what "installed" meant. Without this
     * the only way to get those tiles was to delete the region and download it
     * again from scratch, re-running minutes of server work for data that was
     * already sitting there.
     */
    fun storeExisting(row: RegionRow) {
        val serverId = row.serverId
        val bounds = row.bounds
        if (serverId == null || bounds == null) {
            // Reachable in principle — a region whose bbox this client could
            // not parse — but silently doing nothing looked exactly like a
            // broken button, found the hard way testing this on a real area.
            _state.update { it.copy(error = "That area has no known bounds to save — try re-adding it.") }
            return
        }
        viewModelScope.launch {
            val region = runCatching { container.client().mapRegion(serverId) }
                .getOrElse { e ->
                    _state.update { it.copy(error = "Could not reach the server: ${e.message}") }
                    return@launch
                }
            if (!region.isReady) {
                _state.update {
                    it.copy(error = "${region.name} is not finished building on the server yet.")
                }
                return@launch
            }
            PendingRegions.want(container.appContext, serverId)
            storeOnPhone(region, bounds)
        }
    }

    /** Forget an area: the phone's tiles, and the server's extraction with it. */
    fun delete(row: RegionRow) {
        viewModelScope.launch {
            row.storedId?.let { offline.delete(it) }
            row.serverId?.let { id ->
                PendingRegions.settled(container.appContext, id)
                waiting.remove(id)
                runCatching { container.client().deleteRegion(id) }
                    .onFailure { e ->
                        _state.update { it.copy(error = "Could not remove it from the server: ${e.message}") }
                    }
            }
            reload()
        }
    }

    /** Take the phone's copy off, leaving the area on the server. */
    fun removeFromPhone(row: RegionRow) {
        val storedId = row.storedId ?: return
        viewModelScope.launch {
            offline.delete(storedId)
            row.serverId?.let { PendingRegions.settled(container.appContext, it) }
            reload()
        }
    }

    fun dismissMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /**
     * Poll the server until this region is built.
     *
     * Null when it fails or vanishes — the caller must not then start the
     * phone's half, which is the whole reason this is awaited rather than
     * fired alongside.
     */
    private suspend fun awaitServerReady(serverId: Int): MapRegion? {
        while (true) {
            val region = runCatching { container.client().mapRegion(serverId) }.getOrNull()
            when {
                region == null -> return null
                region.isReady -> return region
                region.isFailed -> return null
                // A build with no worker left behind it will never finish, and
                // waiting on it forever would leave the row spinning.
                region.stale -> return null
            }
            reload()
            delay(pollMs)
        }
    }

    /**
     * Run the phone's half for one region, start to finish.
     *
     * Guarded against running twice for the same area, because there are now
     * three doors into it — a fresh download, the button on a server-only row,
     * and the automatic pick-up of a request that outlived its process — and
     * two of them firing together would have MapLibre downloading one region
     * against two observers.
     */
    private suspend fun storeOnPhone(region: MapRegion, bounds: LatLngBounds) {
        // The guard belongs here rather than only at the download button,
        // because there are three ways in and the other two carry the *server
        // region's* box rather than a framed one. "Save to phone" on an area
        // covering a state is one tap away from a million tile requests, and
        // the whole area is by definition already served at full detail.
        val cost = OfflineEstimate.weigh(bounds)
        if (cost.level == OfflineEstimate.Level.BeyondPhone) {
            PendingRegions.settled(container.appContext, region.id)
            _state.update { it.copy(error = null, notice = beyondPhoneNotice(cost)) }
            return
        }
        if (!storing.add(region.id)) return
        MapDownloadService.start(container.appContext)
        try {
            val styleUrl = container.client().mapStyleUrl()
            offline.download(
                name = region.name,
                serverRegionId = region.id,
                bounds = bounds,
                styleUrl = styleUrl,
            ).collect { progress ->
                when (progress) {
                    // Deliberately not a reload: MapLibre reports progress every
                    // few dozen resources, and a listing request per report was
                    // thousands of calls to the server over one download. The
                    // poller already re-reads the local status on its own.
                    is OfflineTiles.Progress.Running -> waiting.remove(region.id)

                    is OfflineTiles.Progress.Waiting -> {
                        waiting[region.id] = progress.reason
                        _state.update { it.copy(rows = it.rows.map(::redecorate)) }
                    }

                    is OfflineTiles.Progress.Failed -> {
                        waiting.remove(region.id)
                        _state.update {
                            it.copy(error = "Could not store it on the phone: ${progress.reason}")
                        }
                        reload()
                    }

                    is OfflineTiles.Progress.Done -> {
                        waiting.remove(region.id)
                        PendingRegions.settled(container.appContext, region.id)
                        reload()
                    }
                }
            }
            storeRoutingData(bounds)
            // Best-effort and silent, unlike routing data: a missing offline
            // search index costs nothing the user would notice as a failure —
            // the search box just falls back to asking the server, which is
            // exactly what it already did before this existed.
            runCatching {
                container.offlinePoiData().download(
                    client = container.client(),
                    west = bounds.longitudeWest,
                    south = bounds.latitudeSouth,
                    east = bounds.longitudeEast,
                    north = bounds.latitudeNorth,
                )
            }
            // As silent as the search index, and for the same reason: a gap
            // here costs a tap that says nothing back rather than a wrong
            // number, and the point-elevation feature already falls back to
            // asking the server when it has signal to.
            runCatching {
                container.offlineDem().prefetch(
                    client = container.client(),
                    west = bounds.longitudeWest,
                    south = bounds.latitudeSouth,
                    east = bounds.longitudeEast,
                    north = bounds.latitudeNorth,
                )
            }
        } finally {
            storing.remove(region.id)
        }
    }

    /**
     * Bring down the routing segments for the area too.
     *
     * After the tiles rather than alongside, and non-fatal if it fails. The map
     * is the thing the user asked for and it is complete without this; routing
     * data is an extra tens of megabytes on a 5° grid that may already be on
     * the phone from a neighbouring region. A failure here leaves a perfectly
     * good offline map that falls back to server routing, so it is reported as
     * a note and not as "the download failed".
     */
    private suspend fun storeRoutingData(bounds: LatLngBounds) {
        val routing = container.offlineRoutingData()
        routing.download(
            client = container.client(),
            west = bounds.longitudeWest,
            south = bounds.latitudeSouth,
            east = bounds.longitudeEast,
            north = bounds.latitudeNorth,
        ).collect { progress ->
            when (progress) {
                is OfflineRoutingData.Progress.Running ->
                    _state.update { it.copy(routingProgress = progress.fraction) }
                is OfflineRoutingData.Progress.Done ->
                    _state.update { it.copy(routingProgress = null, routingBytes = progress.bytes) }
                is OfflineRoutingData.Progress.Failed ->
                    _state.update {
                        it.copy(
                            routingProgress = null,
                            error = "The map is saved, but offline routing data " +
                                "is not: ${progress.reason}",
                        )
                    }
            }
        }
    }

    /**
     * Rebuild the list from both sides.
     *
     * A region can exist on the server and not the phone (downloaded in the
     * browser), or on the phone and not the server (removed there since). Both
     * are legitimate and both are shown — joined on the server id the phone
     * stores in its own metadata.
     */
    private suspend fun reload() {
        val stored = runCatching { offline.stored() }.getOrDefault(emptyList())
        // Kept as a Result, not flattened to a list. An empty listing and a
        // failed one look identical afterwards, and treating "the phone has no
        // signal" as "the server has no regions" would erase the remembered
        // coverage and then announce it as new again the moment the signal came
        // back — a style reload every time somebody walked out of a tunnel.
        val listing = runCatching { container.client().mapRegions() }
        val server = listing.getOrDefault(emptyList())
        val storedByServerId = stored.associateBy { it.metadata.serverRegionId }
        val wanted = PendingRegions.wanted(container.appContext)

        val rows = buildList {
            server.forEach { region ->
                val phone = storedByServerId[region.id]
                val phase = phaseFor(region, phone, region.id in wanted)
                add(
                    RegionRow(
                        name = region.name,
                        serverId = region.id,
                        storedId = phone?.id,
                        bounds = region.bbox.toBounds() ?: phone?.bounds,
                        shape = region.shapeGeoJson,
                        phase = phase,
                        coverage = coverageOf(phase, serverReady = region.isReady),
                    )
                )
            }
            stored.filter { it.metadata.serverRegionId == null ||
                server.none { region -> region.id == it.metadata.serverRegionId } }
                .forEach { orphan ->
                    val phase = when {
                        orphan.complete -> DownloadPhase.Ready(orphan.bytes)
                        orphan.downloading -> DownloadPhase.Storing(orphan.fraction, orphan.bytes)
                        else -> DownloadPhase.Paused(orphan.fraction, orphan.bytes)
                    }
                    add(
                        RegionRow(
                            name = orphan.metadata.name,
                            storedId = orphan.id,
                            bounds = orphan.bounds,
                            phase = phase,
                            coverage = coverageOf(phase, serverReady = false),
                        )
                    )
                }
        }
        _state.update {
            it.copy(
                rows = rows,
                storedBytes = stored.sumOf { s -> s.bytes },
                routingBytes = runCatching {
                    container.offlineRoutingData().storedBytes()
                }.getOrDefault(0),
            )
        }

        if (listing.isSuccess) {
            noticeCoverage(server)
            pickUpUnfinished(server, storedByServerId, wanted)
        }
    }

    /**
     * Start the phone's half for an area that was asked for and never got one.
     *
     * The recovery half of [PendingRegions]. If the process died during the
     * server's extraction — which takes long enough that this is ordinary
     * rather than unlucky — nothing else would ever start the pull, and the
     * area would sit on the server looking like a finished download.
     */
    private fun pickUpUnfinished(
        server: List<MapRegion>,
        phoneByServerId: Map<Int?, OfflineTiles.Stored>,
        wanted: Set<Int>,
    ) {
        server.forEach { region ->
            if (region.id !in wanted || !region.isReady) return@forEach
            if (region.id in storing) return@forEach
            val phone = phoneByServerId[region.id]
            if (phone?.complete == true) {
                PendingRegions.settled(container.appContext, region.id)
                return@forEach
            }
            // Already moving under its own steam: MapLibre is downloading it
            // after a resume, and a second observer would add nothing.
            if (phone?.downloading == true) return@forEach
            val bounds = region.bbox.toBounds() ?: phone?.bounds ?: return@forEach
            viewModelScope.launch { storeOnPhone(region, bounds) }
        }
    }

    /**
     * Notice when the ground the server serves at full detail has changed.
     *
     * Compared against what this phone last saw rather than against the last
     * poll, so an area cut from the browser while the app was closed counts as
     * a change the first time the app looks again. That is the case worth
     * catching: the phone is then holding a style with no contour or overlay
     * source in it and a cache full of "there is nothing there" for exactly the
     * ground that just gained detail.
     */
    private fun noticeCoverage(server: List<MapRegion>) {
        val installed = server.filter { it.isReady }.map { it.id }.toSet()
        val previous = PendingRegions.lastServerCoverage(container.appContext)
        if (installed == previous) return
        PendingRegions.rememberServerCoverage(container.appContext, installed)
        coverageChanged()
    }

    /** Refetch the style, and stop believing the old answers about those tiles. */
    private fun coverageChanged() {
        onCoverageChanged()
        viewModelScope.launch { offline.invalidateAmbientCache() }
    }

    /** Re-apply the current retry notes to rows already built. */
    private fun redecorate(row: RegionRow): RegionRow {
        val phase = row.phase
        if (phase !is DownloadPhase.Storing) return row
        return row.copy(phase = phase.copy(waiting = row.serverId?.let(waiting::get)))
    }

    private fun phaseFor(
        region: MapRegion,
        phone: OfflineTiles.Stored?,
        wanted: Boolean,
    ): DownloadPhase = when {
        region.isFailed -> DownloadPhase.Failed(region.error ?: "The server could not build this area")
        region.stale -> DownloadPhase.Failed("The server stopped working on this area")
        // The server counts in percent and this phase carries a fraction. Handed
        // over undivided, every download past 1% drew as a full bar — the work
        // looked finished the moment it started, for the several minutes it
        // actually took.
        region.isWorking ->
            DownloadPhase.Extracting(percentToFraction(region.progress), region.detail)
        // Server done. Whether it is stored here is now the phone's answer, and
        // an area the phone was never asked to hold is not an unfinished
        // download — it is one the server covers, which since the server serves
        // full detail over the network is a perfectly good state to be in.
        phone == null -> if (wanted) DownloadPhase.Storing(0f, 0) else DownloadPhase.ServerOnly
        phone.complete -> DownloadPhase.Ready(phone.bytes)
        phone.downloading ->
            DownloadPhase.Storing(phone.fraction, phone.bytes, waiting[region.id])
        else -> DownloadPhase.Paused(phone.fraction, phone.bytes)
    }

    private fun startPollingIfBusy() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            // Any unfinished row, not just server-side ones. Stopping at the
            // hand-off left the phone's own progress bar frozen at whatever it
            // read when the server finished — the half that actually takes the
            // time on a slow connection.
            while (_state.value.rows.any { it.phase is DownloadPhase.Extracting ||
                    it.phase is DownloadPhase.Storing }) {
                delay(pollMs)
                reload()
            }
        }
    }

    private fun beyondPhoneNotice(cost: OfflineEstimate.Weight): String =
        "That area is too big to store on the phone — about " +
            "${cost.resources / 1000}k tiles. The server holds it either way, " +
            "and serves it at full detail whenever you have signal."

    private companion object {
        /**
         * Slow enough not to hammer a server doing heavy work, fast enough that
         * a progress bar moves. The pipeline reports in coarse stages anyway.
         */
        const val POLL_MS = 3000L

        /**
         * The same poll, once the screen nobody is looking at.
         *
         * A region download is tens of minutes of tippecanoe, and the phone is
         * in a pocket for most of it. The poll cannot simply stop — it is what
         * hands the download over to the phone's own tile pull when the server
         * finishes — but at three seconds it holds the radio up roughly twenty
         * times an hour for a progress bar on a screen that is off. Waking a
         * cellular radio is among the most expensive things a phone does, and it
         * stays up for seconds after each request whether or not there was
         * anything to say. Ten times fewer wakeups; the download still lands.
         */
        const val BACKGROUND_POLL_MS = 30_000L

        /** Long enough to outlast a drag, short enough not to feel laggy. */
        const val ESTIMATE_DEBOUNCE_MS = 400L
    }
}

/**
 * What an area's outline should claim, from the phase it is in.
 *
 * A plain function of two facts so it can be tested without a phone, a server
 * or a map: this is the mapping the border colour is drawn from, and getting it
 * wrong paints a promise of offline coverage over ground that has none.
 */
internal fun coverageOf(phase: DownloadPhase, serverReady: Boolean): Coverage = when (phase) {
    is DownloadPhase.Ready -> Coverage.Phone
    is DownloadPhase.ServerOnly -> Coverage.Server
    // Partly here, fully on the server. The honest claim is the server's: the
    // phone cannot yet promise this ground with the radio off.
    is DownloadPhase.Storing, is DownloadPhase.Paused ->
        if (serverReady) Coverage.Server else Coverage.Building
    is DownloadPhase.Extracting, is DownloadPhase.Failed -> Coverage.Building
}

/**
 * The server's `[west, south, east, north]` as bounds, or null if malformed.
 *
 * Null rather than throwing: a region whose bbox the server cannot express is
 * one that should go undrawn, not one that takes the map down.
 */
internal fun List<Double>.toBounds(): LatLngBounds? {
    if (size < 4) return null
    val (west, south, east, north) = this
    if (north <= south || east <= west) return null
    return LatLngBounds.Builder()
        .include(LatLng(north, east))
        .include(LatLng(south, west))
        .build()
}

/**
 * The server's 0-100 progress as a 0-1 fraction.
 *
 * Clamped rather than trusted: a stage that overshoots slightly is a rounding
 * artefact of the builder, not a reason to draw a bar past its own end.
 */
internal fun percentToFraction(percent: Double): Float =
    (percent / 100.0).toFloat().coerceIn(0f, 1f)

/** `west,south,east,north` — the order every bbox in this API uses. */
internal fun LatLngBounds.toBbox(): String =
    "${longitudeWest},${latitudeSouth},${longitudeEast},${latitudeNorth}"

/**
 * Whether an area is beyond what is worth extracting or storing.
 *
 * ## Why this is an area and not a side length
 *
 * It used to cap each side at two degrees, which sounds like the same thing and
 * is not. A state is rarely square: a western one is often about 7 degrees across and 4
 * high, so a side cap tight enough to bound the *download* refused shapes that
 * were perfectly reasonable in size, and a cap loose enough to allow them would
 * have permitted a square twenty times their area. Bounding what actually costs
 * something — the ground covered — lets a long thin corridor along a mountain
 * range through while still refusing a hemisphere.
 *
 * ## Why the ceiling is where it is
 *
 * At roughly 400 MB per square degree of this terrain, [MAX_REGION_SQ_DEGREES]
 * is about forty gigabytes at the very top end — far past what anyone should
 * start without thinking, and that is the point: the estimate is the real
 * gate, shown before the download begins, and this only has to refuse the
 * requests that are not worth *measuring*. A dry run across the archives for a
 * continent is its own kind of slow.
 *
 * This bounds the *server's* work. What the phone can hold is a much smaller
 * number and a different calculation — see [OfflineEstimate].
 *
 * The side cap survives as a much looser sanity bound, so a band one degree
 * tall wrapped most of the way round the planet is still refused.
 */
internal fun LatLngBounds.tooLargeToDownload(): Boolean {
    val height = latitudeNorth - latitudeSouth
    val width = longitudeEast - longitudeWest
    return height * width > MAX_REGION_SQ_DEGREES ||
        height > MAX_REGION_DEGREES ||
        width > MAX_REGION_DEGREES
}

/**
 * Square degrees. Around 100 is several states — two such states together are
 * roughly 60 — which is the case this exists to allow.
 */
private const val MAX_REGION_SQ_DEGREES = 100.0

/** A sanity bound on either side, not a size limit. */
private const val MAX_REGION_DEGREES = 20.0
