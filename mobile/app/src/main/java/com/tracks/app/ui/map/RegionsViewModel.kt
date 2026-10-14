// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.map.OfflineRoutingData
import com.tracks.app.map.packs.PackJobs
import com.tracks.app.map.packs.PackSaver
import com.tracks.app.map.packs.PackStore
import com.tracks.core.api.PackManifest
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
import com.tracks.core.api.ServerErrorException
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

    /** The server is done; the phone is fetching the area's map packs. */
    data class Storing(
        val fraction: Float,
        val bytes: Long,
        /** Why it is not moving right now: no Wi-Fi, usually. */
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
    /** This phone asked for it and is waiting to store it (see [PendingRegions]). */
    val requestedHere: Boolean = false,
    /** The saved area on this phone (see [PackStore.Area]), when it is one. */
    val areaId: String? = null,
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
    /** Map packs on this phone, and the most they may take (see [PackStore.budget]). */
    val usedBytes: Long = 0,
    val budgetBytes: Long = 0,
    val freeBytes: Long = 0,
    /** The volume's size, for the storage bar. */
    val totalBytes: Long = 0,
    /** Storage the packs can live on: the phone's own, then SD cards. */
    val volumes: Int = 1,
    val volume: Int = 0,
    val wifiOnly: Boolean = true,
    /** Bytes of newer packs the server has for saved areas; 0 when current. */
    val updateBytes: Long = 0,
    val checkingUpdates: Boolean = false,
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
 * Saving areas for offline use, in the two halves it really takes.
 *
 * The server builds the area into its map archives (minutes, polled — there is
 * nothing to subscribe to); the phone then fetches the area's map packs as
 * files (map/packs). A request is written down before the server's wait
 * ([PendingRegions]) so it survives the process; the packs are fetched by a
 * job ([PackJobs]) that survives the screen; and the list is rebuilt from the
 * pack registry as packs land, with no polling of the phone's own half.
 *
 * What the phone saves is exactly the box the person framed, however large the
 * server's area around it, within the storage limit they chose.
 */
class RegionsViewModel(
    private val container: AppContainer,
    /** MapLibre's offline regions from before map packs: read to move them over, then removed. */
    private val legacy: OfflineTiles,
    /**
     * Called when the ground the server serves at full detail changes, so the
     * map picks up the new style: the served style is narrowed to the archives
     * on disk, so the first area adds whole sources and layers to it.
     */
    private val onCoverageChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(RegionsUiState())
    val state: StateFlow<RegionsUiState> = _state.asStateFlow()

    private val packs: PackStore get() = container.packs

    private var poller: Job? = null
    private var estimateJob: Job? = null
    private var server: List<MapRegion> = emptyList()
    private var onScreen = true
    private var shownBefore = false
    private var lastUpdateCheck = 0L

    /** Called with `true` on ON_START and `false` on ON_STOP. */
    fun setOnScreen(value: Boolean) {
        onScreen = value
        if (!value) return
        if (shownBefore) refresh()
        shownBefore = true
    }

    init {
        // The list follows the packs as they land, with no polling of its own.
        viewModelScope.launch { packs.state.collect { rebuild() } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // Failed packs get another go each time the list is opened: most
            // failures (no network, a server hiccup) do not outlive the moment.
            packs.retryFailed()
            reloadServer()
            _state.update { it.copy(loading = false) }
            pickUpRequests()
            migrateLegacy()
            PackJobs.schedule(container.appContext, userInitiated = onScreen)
            startPollingIfBuilding()
            checkUpdatesIfDue()
        }
    }

    // ── Framing an area ──────────────────────────────────────────────────────

    /**
     * What the visible area would take on this phone, and what to call it.
     *
     * From the pack manifest: exact for ground the server already has, and for
     * the rest the server's extract estimate stands in. Asked as the person
     * frames, because tens of megabytes and tens of gigabytes are different
     * decisions.
     */
    fun estimate(bounds: LatLngBounds) {
        estimateJob?.cancel()
        if (bounds.tooLargeToDownload()) {
            _state.update { it.copy(estimating = false, estimateBytes = null, tooLarge = true, error = null) }
            return
        }
        estimateJob = viewModelScope.launch {
            _state.update { it.copy(estimating = true, estimateBytes = null, tooLarge = false, error = null) }
            delay(ESTIMATE_DEBOUNCE_MS)
            val client = container.client()
            val bbox = bounds.toBbox()
            val manifest = async { runCatching { client.packManifest(bbox) }.getOrNull() }
            val extract = async { runCatching { client.regionEstimate(bbox).bytes }.getOrNull() }
            val name = async { runCatching { client.suggestRegionName(bbox).name }.getOrNull() }
            val m = manifest.await()
            val fullyCovered = m != null && m.cells.all { it.covered }
            val bytes = when {
                m == null -> extract.await()
                fullyCovered -> newBytes(m)
                // Ground the server has not built yet: its packs do not exist,
                // so the extract size (terrain and detail, the bulk) stands in.
                else -> newBytes(m) + (extract.await() ?: 0)
            }
            _state.update {
                it.copy(estimating = false, estimateBytes = bytes,
                        suggestedName = name.await()?.takeIf(String::isNotBlank), error = null)
            }
        }
    }

    fun clearEstimate() {
        _state.update { it.copy(estimateBytes = null, suggestedName = null, tooLarge = false, error = null) }
    }

    /**
     * Save the framed area: built on the server first when it has to be, then
     * its packs fetched onto this phone.
     */
    fun download(bounds: LatLngBounds, name: String) {
        if (bounds.tooLargeToDownload()) {
            _state.update { it.copy(tooLarge = true) }
            return
        }
        val bbox = listOf(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
        viewModelScope.launch {
            val client = container.client()
            val started = runCatching { client.downloadRegion(bounds.toBbox(), name) }.getOrElse { e ->
                _state.update { it.copy(notice = null, error = "Could not start the download: ${e.message}") }
                return@launch
            }
            clearEstimate()
            val region = started.region
            if (region == null) {
                _state.update { it.copy(error = "The server did not say which area it is building") }
                return@launch
            }
            if (started.status == "exists" || region.isReady) {
                saveOnPhone(name, bbox, region.id)
            } else {
                // Written before the wait: the process may not outlive the
                // server's build, and the intention has to.
                PendingRegions.want(container.appContext, region.id, name, bbox)
                // The job waits for the build too, with a notification, so
                // closing the app does not stop the download (see PackJobs).
                PackJobs.schedule(container.appContext, userInitiated = onScreen)
                reloadServer()
                startPollingIfBuilding()
            }
        }
    }

    /** "Save to phone" on an area the server has: the whole area. */
    fun storeExisting(row: RegionRow) {
        val bounds = row.bounds ?: return
        val bbox = listOf(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
        viewModelScope.launch { saveOnPhone(row.name, bbox, row.serverId) }
    }

    /** Take an area off this phone (or stop waiting for one). Never the server's. */
    fun delete(row: RegionRow) = removeFromPhone(row)

    fun removeFromPhone(row: RegionRow) {
        viewModelScope.launch {
            row.areaId?.let { packs.removeArea(it) }
            row.storedId?.let { runCatching { legacy.delete(it) } }
            row.serverId?.let { PendingRegions.settled(container.appContext, it) }
            rebuild()
        }
    }

    fun retryFailed() {
        packs.retryFailed()
        PackJobs.schedule(container.appContext)
    }

    fun dismissMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun setWifiOnly(value: Boolean) {
        packs.setSettings { it.copy(wifiOnly = value) }
        // Rescheduled with the new network constraint.
        PackJobs.schedule(container.appContext)
    }

    fun setBudget(bytes: Long) {
        packs.setSettings { it.copy(budgetBytes = bytes) }
        packs.retryFailed()
        PackJobs.schedule(container.appContext)
    }

    /**
     * Move the packs to another volume. Only while nothing is saved there yet:
     * moving gigabytes between volumes is a job of its own, and a half-moved
     * set is worse than asking the person to remove and save again.
     */
    fun setVolume(index: Int) {
        if (packs.state.value.packs.values.any { it.file != null }) {
            _state.update { it.copy(error = "Remove the saved areas first, then choose where new ones go.") }
            return
        }
        packs.setSettings { it.copy(volume = index) }
        rebuild()
    }

    // ── Updates ──────────────────────────────────────────────────────────────

    /** Fetch newer packs for every saved area (the person said yes). */
    fun applyUpdates() {
        viewModelScope.launch {
            val client = container.client()
            packs.state.value.areas.forEach { area ->
                runCatching { packs.applyManifest(client.packManifest(area.bbox.joinToString(","))) }
            }
            _state.update { it.copy(updateBytes = 0) }
            PackJobs.schedule(container.appContext)
        }
    }

    private fun checkUpdatesIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_CHECK_MS || packs.state.value.areas.isEmpty()) return
        lastUpdateCheck = now
        viewModelScope.launch {
            _state.update { it.copy(checkingUpdates = true) }
            val client = container.client()
            val held = packs.state.value.packs
            var bytes = 0L
            packs.state.value.areas.forEach { area ->
                val m = runCatching { client.packManifest(area.bbox.joinToString(",")) }.getOrNull() ?: return@forEach
                m.cells.forEach { c ->
                    c.layers.forEach { (layer, e) ->
                        val p = held["${c.cell}/$layer"]
                        if (p != null && p.version != e.version) bytes += e.bytes
                    }
                }
                m.shared.forEach { (name, e) ->
                    val p = held["shared/$name"]
                    if (p != null && p.version != e.version) bytes += e.bytes
                }
            }
            _state.update { it.copy(checkingUpdates = false, updateBytes = bytes) }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun newBytes(m: PackManifest): Long = PackSaver.newBytes(packs, m)

    private suspend fun saveOnPhone(name: String, bbox: List<Double>, serverId: Int?) {
        when (val r = PackSaver.save(container, name, bbox, serverId)) {
            is PackSaver.Result.Refused -> _state.update { it.copy(error = r.reason) }
            PackSaver.Result.Saved -> {
                PackJobs.schedule(container.appContext, userInitiated = onScreen)
                rebuild()
            }
        }
    }

    /** Requests whose server build has finished: save them now. */
    private suspend fun pickUpRequests() {
        val requests = PendingRegions.requests(container.appContext)
        if (requests.isEmpty()) return
        val byId = server.associateBy { it.id }
        requests.forEach { (id, req) ->
            val region = byId[id]
            when {
                region == null -> PendingRegions.settled(container.appContext, id)   // removed on the server
                region.isReady -> saveOnPhone(req.name, req.bbox, id)
                else -> Unit
            }
        }
    }

    /**
     * Move areas saved the old way (MapLibre offline regions) to packs.
     *
     * Each becomes a saved area over the same box, fetched as packs on Wi-Fi.
     * The old region keeps working until its packs are all in, and is deleted
     * only then; once none are left, MapLibre's offline database goes too.
     */
    private suspend fun migrateLegacy() {
        val old = runCatching { legacy.stored() }.getOrDefault(emptyList())
        if (old.isEmpty()) return
        val areas = packs.state.value.areas
        old.forEach { region ->
            val b = region.bounds
            val bbox = listOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
            val area = areas.firstOrNull { it.bbox == bbox }
            when {
                area == null -> runCatching {
                    val m = container.client().packManifest(bbox.joinToString(","))
                    packs.addArea(region.metadata.name, bbox, m, region.metadata.serverRegionId)
                }
                packs.state.value.packsFor(area).all { it.current } -> runCatching { legacy.delete(region.id) }
            }
        }
    }

    private suspend fun reloadServer() {
        val listing = runCatching { container.client().mapRegions() }
        listing.onSuccess { list ->
            server = list
            val installed = list.filter { it.isReady }.map { it.id }.toSet()
            if (installed != PendingRegions.lastServerCoverage(container.appContext)) {
                PendingRegions.rememberServerCoverage(container.appContext, installed)
                onCoverageChanged()
            }
        }
        rebuild()
    }

    /** The list, from the packs on this phone and the areas on the server. */
    private fun rebuild() {
        val snap = packs.state.value
        val requests = PendingRegions.requests(container.appContext)
        val rows = buildList {
            snap.areas.forEach { area ->
                val mine = snap.packsFor(area)
                val total = mine.sumOf { it.bytes }.coerceAtLeast(1)
                val done = mine.sumOf { if (it.current) it.fileBytes else it.done }
                val failed = mine.firstOrNull { it.state == PackStore.State.FAILED }
                val phase = when {
                    mine.isNotEmpty() && mine.all { it.current } -> DownloadPhase.Ready(mine.sumOf { it.fileBytes })
                    failed != null -> DownloadPhase.Failed(failed.error ?: "Could not save part of this area")
                    else -> DownloadPhase.Storing(
                        (done.toFloat() / total).coerceIn(0f, 1f), done,
                        waiting = if (snap.settings.wifiOnly && !onUnmeteredNetwork()) "Waiting for Wi-Fi" else null,
                    )
                }
                add(RegionRow(
                    name = area.name, phase = phase, serverId = area.serverId,
                    bounds = area.bbox.toBounds(), coverage = coverageOf(phase, serverReady = true),
                    areaId = area.id,
                ))
            }
            val savedServerIds = snap.areas.mapNotNull { it.serverId }.toSet()
            server.forEach { region ->
                val requested = region.id in requests
                if (region.id in savedServerIds && !requested) return@forEach
                val phase = when {
                    region.isFailed -> DownloadPhase.Failed(region.error ?: "The server could not build this area")
                    region.stale -> DownloadPhase.Failed("The server stopped working on this area")
                    region.isWorking -> DownloadPhase.Extracting(percentToFraction(region.progress), region.detail)
                    else -> DownloadPhase.ServerOnly
                }
                add(RegionRow(
                    name = requests[region.id]?.name ?: region.name, phase = phase, serverId = region.id,
                    bounds = region.bbox.toBounds(), shape = region.shapeGeoJson,
                    coverage = coverageOf(phase, serverReady = region.isReady),
                    requestedHere = requested,
                ))
            }
        }
        _state.update {
            it.copy(
                rows = rows,
                storedBytes = snap.usedBytes,
                usedBytes = snap.usedBytes,
                budgetBytes = packs.budget(),
                freeBytes = packs.freeBytes(),
                totalBytes = packs.totalBytes(),
                volumes = packs.volumes().size,
                volume = snap.settings.volume,
                wifiOnly = snap.settings.wifiOnly,
                routingBytes = runCatching { container.offlineRoutingData().storedBytes() }.getOrDefault(0),
            )
        }
    }

    private fun onUnmeteredNetwork(): Boolean = runCatching {
        val cm = container.appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(true)

    private fun startPollingIfBuilding() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (server.any { it.isWorking } || PendingRegions.requests(container.appContext).isNotEmpty()) {
                delay(if (onScreen) POLL_MS else BACKGROUND_POLL_MS)
                reloadServer()
                pickUpRequests()
            }
        }
    }

    private companion object {
        /** Slow enough not to hammer a server doing heavy work, fast enough that a bar moves. */
        const val POLL_MS = 3000L
        /** The same poll with the screen off: the radio is expensive to wake. */
        const val BACKGROUND_POLL_MS = 30_000L
        const val ESTIMATE_DEBOUNCE_MS = 400L
        /** Saved areas are checked for newer packs at most this often. */
        const val UPDATE_CHECK_MS = 6 * 3600_000L
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
 * number and a different calculation — see [PackStore.budget].
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
 * Square degrees. About 1,500 is the contiguous United States: with map packs
 * the phone holds whatever its owner gives storage to, so this only stops a
 * box framed at world zoom from setting the server extracting the planet.
 */
private const val MAX_REGION_SQ_DEGREES = 1500.0

/** A sanity bound on either side, not a size limit. */
private const val MAX_REGION_DEGREES = 60.0
