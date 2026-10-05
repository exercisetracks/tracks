// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

/**
 * Tiles on the phone, for a map that works with the radio off.
 *
 * ## Why this is a second download
 *
 * Asking the server for a region and getting tiles onto a phone are different
 * jobs, and only doing the first is the trap. `POST /maps/regions/download`
 * makes the server extract an area out of the planet archives into the master
 * files its tile endpoint serves — after which the phone can *fetch* that area,
 * over the network, exactly as before. Nothing has been stored locally. A map
 * that needs the server is not an offline map, and the failure only shows up
 * where it matters most: in a valley, with no signal, which is the entire
 * premise of carrying it.
 *
 * So a region download is two phases. The server extracts (minutes of
 * tippecanoe over gigabytes — not phone work), and then the phone pulls the
 * result into MapLibre's own offline database. The order is not optional: pull
 * first and the phone caches the blank tiles that exist before the extraction
 * ran, and caches them as legitimately empty.
 *
 * ## What MapLibre stores
 *
 * Everything the style references for the given bounds and zoom range —
 * vector tiles, glyphs, the sprite — keyed by URL in a SQLite database it
 * manages. Once a region is complete the normal map draws from it without
 * knowing: there is no offline mode to switch into, which is why the Map tab
 * needs no separate offline UI beyond saying what is stored.
 *
 * It wants a style *URL*, not the document the live map is handed, because it
 * re-fetches the style and walks it to find what to download. Everything it
 * needs — style, tiles, glyphs, sprite — is served publicly, so none of this
 * carries the user's token.
 *
 * ## What a download does when something goes wrong
 *
 * Almost nothing, and that is the point — see [troubleOf]. MapLibre's downloader
 * skips what is missing and retries what timed out, entirely on its own, so the
 * job of this class during trouble is to describe it rather than to intervene.
 * The one intervention that matters is [resumeIncomplete], because the single
 * thing MapLibre does *not* do by itself is come back after the process died.
 */
class OfflineTiles(context: Context) {

    private val appContext = context.applicationContext

    private val manager: OfflineManager by lazy {
        // MapLibre.getInstance must precede any use of the SDK, and this class
        // can be reached before a MapView has ever been constructed — opening
        // the downloads sheet without opening the map first.
        MapLibre.getInstance(appContext)
        OfflineManager.getInstance(appContext)
    }

    /**
     * What the phone knows about a stored region beyond its bounds.
     *
     * MapLibre gives each region an opaque metadata blob and no schema, so this
     * is ours: the name to show, and the *server's* region id so the two halves
     * can be reconciled. Without that link a region deleted in the browser
     * would leave the phone holding tiles it could never explain or match up.
     */
    data class Metadata(
        val name: String,
        val serverRegionId: Int? = null,
        val createdAt: Long = 0,
    ) {
        // org.json rather than kotlinx.serialization: this module does not carry
        // the serialization plugin, and adding it for a three-field blob that
        // never crosses a process boundary would be the wrong trade.
        fun toBytes(): ByteArray = JSONObject()
            .put("name", name)
            .put("serverRegionId", serverRegionId ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .toString()
            .toByteArray()

        companion object {
            fun from(bytes: ByteArray): Metadata? = runCatching {
                val json = JSONObject(String(bytes))
                Metadata(
                    name = json.optString("name", "Saved area"),
                    serverRegionId = if (json.isNull("serverRegionId")) null
                    else json.getInt("serverRegionId"),
                    createdAt = json.optLong("createdAt", 0),
                )
            }.getOrNull()
        }
    }

    /** A region held on the phone, with its live download status. */
    data class Stored(
        val id: Long,
        val metadata: Metadata,
        val bounds: LatLngBounds,
        val completedTiles: Long,
        val requiredTiles: Long,
        val bytes: Long,
        val complete: Boolean,
        /**
         * Whether MapLibre is working on it *right now*.
         *
         * The difference between a download and a dead one. An incomplete
         * region that nothing is downloading is the state a killed process
         * leaves behind, and it is indistinguishable from a slow download
         * without this — which is why an interrupted area used to sit at some
         * fraction forever with the sheet insisting it was still saving.
         */
        val downloading: Boolean = false,
    ) {
        val fraction: Float
            get() = when {
                complete -> 1f
                requiredTiles <= 0 -> 0f
                else -> (completedTiles.toDouble() / requiredTiles).toFloat().coerceIn(0f, 1f)
            }
    }

    /** Progress of one download, as it runs. */
    sealed interface Progress {
        data class Running(val completed: Long, val required: Long, val bytes: Long) : Progress {
            val fraction: Float
                get() = if (required <= 0) 0f
                else (completed.toDouble() / required).toFloat().coerceIn(0f, 1f)
        }

        /**
         * Something went wrong that MapLibre intends to survive.
         *
         * Not a failure and deliberately not shaped like one: the download is
         * still active, the request will be retried, and the collector should
         * keep listening. It exists so a phone in a tunnel can say so instead
         * of going quiet.
         */
        data class Waiting(val reason: String, val bytes: Long) : Progress

        data class Done(val bytes: Long) : Progress

        /** Terminal. Only for the two things that really do stop a download. */
        data class Failed(val reason: String) : Progress
    }

    /**
     * Pull a region onto the phone, or pick up one that was interrupted.
     *
     * Emits progress until the region completes or genuinely fails, then closes.
     * The download keeps running if the collector goes away — MapLibre owns it,
     * not this flow — which is deliberate: leaving a tab should not abandon a
     * half-downloaded region, and reopening the sheet picks the status back up
     * through [stored].
     *
     * ## Why this resumes rather than starts
     *
     * Asking for an area the phone is already holding used to create a *second*
     * offline region over the same ground. The resources are shared and
     * reference-counted underneath, so it was not a second copy of the bytes —
     * but it was a second row, a second progress bar, and a list that grew a
     * duplicate every time somebody retried something that had stalled. Since
     * retrying a stalled download is exactly what a person does, the duplicates
     * arrived precisely when the list most needed to be readable.
     */
    fun download(
        name: String,
        serverRegionId: Int?,
        bounds: LatLngBounds,
        styleUrl: String,
        minZoom: Double = MIN_ZOOM,
        maxZoom: Double = MAX_ZOOM,
    ): Flow<Progress> = callbackFlow {
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density,
        )
        val metadata = Metadata(
            name = name,
            serverRegionId = serverRegionId,
            createdAt = System.currentTimeMillis(),
        )

        val region = existing(serverRegionId, bounds)
            ?.also { adopt(it, metadata) }
            ?: create(definition, metadata).getOrElse { failure ->
                trySend(Progress.Failed(failure.message ?: "The phone would not open a download"))
                channel.close()
                return@callbackFlow awaitClose { }
            }

        region.setObserver(observer(send = { trySend(it) }, close = { channel.close() }))
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)

        awaitClose {
            // The observer, not the download. Detaching the callback stops this
            // flow leaking a reference; the region keeps downloading.
            region.setObserver(null)
        }
    }

    /**
     * Set every unfinished region downloading again.
     *
     * ## Why this has to exist
     *
     * MapLibre's download state is not persisted. Every region in the database
     * is inactive when the process starts, whatever it was doing when the
     * process ended — and a phone that spends a download in somebody's pocket
     * gets its process ended routinely. So an area interrupted by a swipe-away,
     * a low-memory kill, or a reboot stayed exactly as interrupted as it was,
     * for good, while the sheet went on describing it as though it were saving.
     * Nothing in the app ever asked it to continue.
     *
     * Resuming is cheap and safe. Everything already in the database is found
     * there and counted without a request, so a region that is actually
     * finished settles back to complete in seconds having fetched nothing, and
     * one that is not picks up where it stopped.
     *
     * @return how many regions were asked to continue.
     */
    suspend fun resumeIncomplete(): Int {
        val regions = allRegions()
        var resumed = 0
        regions.forEach { region ->
            val status = statusOf(region) ?: return@forEach
            if (finished(status) || status.downloadState == OfflineRegion.STATE_ACTIVE) return@forEach
            Log.i(TAG, "resuming offline region ${region.id}")
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            resumed++
        }
        return resumed
    }

    /** Every region on the phone, with how far along each one is. */
    suspend fun stored(): List<Stored> = allRegions().mapNotNull { region ->
        val definition = region.definition as? OfflineTilePyramidRegionDefinition
            ?: return@mapNotNull null
        val bounds = definition.bounds ?: return@mapNotNull null
        val status = statusOf(region) ?: return@mapNotNull null
        Stored(
            id = region.id,
            metadata = readMetadata(region) ?: Metadata(name = "Saved area"),
            bounds = bounds,
            completedTiles = status.completedResourceCount,
            requiredTiles = status.requiredResourceCount,
            bytes = status.completedResourceSize,
            complete = finished(status),
            downloading = status.downloadState == OfflineRegion.STATE_ACTIVE,
        )
    }

    /** Remove a region's tiles from the phone. Leaves the server's copy alone. */
    suspend fun delete(id: Long): Boolean {
        val region = suspendCancellableCoroutine<OfflineRegion?> { cont ->
            manager.getOfflineRegion(id, object : OfflineManager.GetOfflineRegionCallback {
                override fun onRegion(offlineRegion: OfflineRegion) = cont.resume(offlineRegion)

                // Its own callback, not an error: a region the phone has
                // already forgotten is the ordinary outcome of deleting the
                // same area twice, which two open sheets can easily do.
                override fun onRegionNotFound() = cont.resume(null)
                override fun onError(error: String) = cont.resume(null)
            })
        } ?: return false

        // Stopped first. Deleting a region that is still downloading races the
        // worker writing into the same database.
        region.setDownloadState(OfflineRegion.STATE_INACTIVE)
        return suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = cont.resume(true)
                override fun onError(error: String) {
                    Log.w(TAG, "could not delete offline region $id: $error")
                    cont.resume(false)
                }
            })
        }
    }

    /**
     * Make the phone ask again about tiles it already has an answer for.
     *
     * Run when the server's coverage changes, alongside refetching the style —
     * which is the part that actually matters, since a region landing adds
     * whole sources to the style and raises the basemap's maxzoom from 12 to
     * 15. This is the smaller, second half of the same problem: the answers the
     * phone kept from *before* that happened.
     *
     * Not about 404s. MapLibre never writes an errored response to its cache
     * (`OfflineDatabase::putInternal` returns early on `response.error`), so a
     * tile that did not exist yesterday is re-requested today by itself. It is
     * about the successful ones — including the deliberately empty tiles the
     * archives answer with — because the basemap's URL carries no version
     * parameter, by design, so nothing about it changes when new detail lands
     * underneath it.
     *
     * Invalidating marks the cached entries as needing revalidation rather than
     * deleting them, so nothing already correct is re-fetched: the tiles that
     * still say the same thing come back as a cheap 304. Stored regions are a
     * separate table and are not touched.
     */
    suspend fun invalidateAmbientCache(): Boolean = suspendCancellableCoroutine { cont ->
        manager.invalidateAmbientCache(object : OfflineManager.FileSourceCallback {
            override fun onSuccess() = cont.resume(true)
            override fun onError(message: String) {
                Log.w(TAG, "could not invalidate the ambient cache: $message")
                cont.resume(false)
            }
        })
    }

    /** Total bytes the phone is holding for maps. */
    suspend fun storedBytes(): Long = stored().sumOf { it.bytes }

    /**
     * The region already covering this download, if the phone has one.
     *
     * Matched on the server's id first, because that is an identity rather than
     * a guess. Bounds are the fallback for a region stored before the phone
     * knew the server's id for it, and compared with a tolerance: the box comes
     * from screen coordinates through a projection, so two attempts at "the
     * same area" agree to about a metre and never to the bit.
     */
    private suspend fun existing(serverRegionId: Int?, bounds: LatLngBounds): OfflineRegion? =
        allRegions().firstOrNull { region ->
            val metadata = readMetadata(region)
            val definition = region.definition as? OfflineTilePyramidRegionDefinition
            when {
                serverRegionId != null && metadata?.serverRegionId == serverRegionId -> true
                definition?.bounds?.matches(bounds) == true -> true
                else -> false
            }
        }

    /**
     * Teach a region it already had which server area it belongs to.
     *
     * Matters for the region matched on bounds rather than on an id: it was
     * stored before the phone knew the server's id for it, so every listing
     * afterwards shows it as an orphan the server has forgotten — beside the
     * server's own row for the same ground. Writing the id once ends that.
     */
    private fun adopt(region: OfflineRegion, metadata: Metadata) {
        val known = readMetadata(region)
        if (known?.serverRegionId == metadata.serverRegionId) return
        val merged = (known ?: metadata).copy(serverRegionId = metadata.serverRegionId)
        region.updateMetadata(
            merged.toBytes(),
            object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                override fun onUpdate(metadata: ByteArray) = Unit
                override fun onError(error: String) {
                    Log.w(TAG, "could not link region ${region.id} to its server area: $error")
                }
            },
        )
    }

    private suspend fun create(
        definition: OfflineTilePyramidRegionDefinition,
        metadata: Metadata,
    ): Result<OfflineRegion> = suspendCancellableCoroutine { cont ->
        manager.createOfflineRegion(
            definition,
            metadata.toBytes(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(created: OfflineRegion) = cont.resume(Result.success(created))
                override fun onError(error: String) =
                    cont.resume(Result.failure(IllegalStateException(error)))
            },
        )
    }

    private fun observer(
        send: (Progress) -> Unit,
        close: () -> Unit,
    ) = object : OfflineRegion.OfflineRegionObserver {

        /** Carried so a [Progress.Waiting] can still say how far along it is. */
        private var bytes = 0L

        override fun onStatusChanged(status: OfflineRegionStatus) {
            bytes = status.completedResourceSize
            if (finished(status)) {
                send(Progress.Done(status.completedResourceSize))
                close()
            } else {
                send(
                    Progress.Running(
                        completed = status.completedResourceCount,
                        required = status.requiredResourceCount,
                        bytes = status.completedResourceSize,
                    )
                )
            }
        }

        /**
         * One resource went wrong. The download did not.
         *
         * This used to end the download's flow and report a failure, which was
         * wrong for every error MapLibre reports — see [troubleOf] for what the
         * downloader actually does with each — and wrong most damagingly for
         * the most common one. Tracks' tile server answers 404 for a tile it
         * has no data for, which happens on the edge of every extracted region
         * and over every stretch of empty ground inside one, so *every* download
         * announced itself as failed within seconds of starting while
         * continuing perfectly happily in the background. There was no
         * successful download to compare against, so the map simply looked like
         * it could not save areas.
         */
        override fun onError(error: OfflineRegionError) {
            when (troubleOf(error.reason)) {
                Trouble.Missing ->
                    // Not even worth a line at warn level: on a large region
                    // there are thousands, and they are the server correctly
                    // saying there is nothing there.
                    Log.v(TAG, "no tile for one resource: ${error.message}")

                Trouble.Transient -> {
                    Log.i(TAG, "offline download waiting: ${error.reason} ${error.message}")
                    send(Progress.Waiting(describe(error), bytes))
                }
            }
        }

        /**
         * The tile-count ceiling MapLibre applies for Mapbox-hosted styles.
         *
         * Inert here, and kept for the day that stops being true. The limit is
         * only counted against canonical `mapbox://` URLs, and Tracks serves
         * its own tiles from its own host — but hitting it does stop a download
         * dead (MapLibre deactivates the region), so it is reported rather than
         * swallowed.
         */
        override fun mapboxTileCountLimitExceeded(limit: Long) {
            Log.w(TAG, "offline tile limit $limit reached")
            send(Progress.Failed("This area is too large to store"))
            close()
        }
    }

    private suspend fun allRegions(): List<OfflineRegion> =
        suspendCancellableCoroutine { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val regions = offlineRegions?.toList().orEmpty()
                    // Asked for once, here, rather than at each call site.
                    // Without it MapLibre stops reporting the moment it makes a
                    // region inactive — including the deactivation that *means
                    // it finished* — so the last status a download produced was
                    // whatever it read a moment before the end.
                    regions.forEach { it.setDeliverInactiveMessages(true) }
                    cont.resume(regions)
                }

                override fun onError(error: String) {
                    Log.w(TAG, "could not list offline regions: $error")
                    cont.resume(emptyList())
                }
            })
        }

    private suspend fun statusOf(region: OfflineRegion): OfflineRegionStatus? =
        suspendCancellableCoroutine<OfflineRegionStatus?> { cont ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) = cont.resume(status)
                override fun onError(error: String?) = cont.resume(null)
            })
        }

    private fun readMetadata(region: OfflineRegion): Metadata? =
        Metadata.from(region.metadata)

    private fun describe(error: OfflineRegionError): String = when (error.reason) {
        OfflineRegionError.REASON_CONNECTION -> "Waiting for a connection"
        OfflineRegionError.REASON_SERVER -> "The server is not answering — still trying"
        else -> error.message.ifBlank { "Something went wrong — still trying" }
    }

    companion object {
        private const val TAG = "TracksOffline"

        /**
         * The zoom band worth storing.
         *
         * Not from zero: the whole-planet overview is a handful of tiles that
         * the ambient cache already keeps, and including them in every region
         * would re-download the same world tiles per area.
         *
         * Not past 15 either, because that is where the data stops — the
         * basemap archives are z0-12 with region detail to 15, and MapLibre
         * overzooms beyond it (see `services/tile_coverage.py`). Asking for 16
         * would download nothing extra and inflate the required-tile count that
         * the progress bar is drawn from.
         */
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 15.0

        /**
         * Whether a status really means "there is nothing left to fetch".
         *
         * `isComplete` alone does not. MapLibre counts required resources as it
         * discovers them, so at the start of a download — and on any region it
         * reads cold from the database without the style to walk — the required
         * count is a *lower bound* that the completed count trivially meets. A
         * region interrupted at ten percent therefore reports itself complete
         * the next time the app opens, which is the worst possible lie for this
         * feature to tell: the map claims coverage it does not have, and says
         * so on a screen in town rather than in the valley where it is found
         * out.
         */
        internal fun finished(status: OfflineRegionStatus): Boolean =
            status.isComplete && status.isRequiredResourceCountPrecise
    }
}

/** What one of MapLibre's download errors means for the download that saw it. */
internal enum class Trouble {
    /**
     * The server has no such tile. MapLibre drops the requirement and carries
     * on, so the download is not only alive but one resource shorter.
     */
    Missing,

    /**
     * A connection or server problem. MapLibre keeps the request and retries it
     * on a backoff, so the download is stalled rather than over — and a stall
     * that lasts as long as a tunnel does should not cost the region.
     */
    Transient,
}

/**
 * Read a MapLibre error reason as what it does to the download.
 *
 * Split out as a plain function over the reason string because the whole point
 * is that it is decidable without a device: the reasons are constants, the
 * downloader's behaviour for each is fixed, and getting the mapping wrong is
 * the difference between "saved" and "failed" on a screen somebody trusts.
 */
internal fun troubleOf(reason: String?): Trouble = when (reason) {
    OfflineRegionError.REASON_NOT_FOUND -> Trouble.Missing
    else -> Trouble.Transient
}

/**
 * Whether two boxes describe the same area, to a metre.
 *
 * A tenth of a millidegree is about eleven metres at the equator and finer than
 * a fingertip on a drag handle, so anything closer than this was meant to be
 * the same box.
 */
private fun LatLngBounds.matches(other: LatLngBounds): Boolean =
    kotlin.math.abs(latitudeNorth - other.latitudeNorth) < BOUNDS_SLACK &&
        kotlin.math.abs(latitudeSouth - other.latitudeSouth) < BOUNDS_SLACK &&
        kotlin.math.abs(longitudeEast - other.longitudeEast) < BOUNDS_SLACK &&
        kotlin.math.abs(longitudeWest - other.longitudeWest) < BOUNDS_SLACK

private const val BOUNDS_SLACK = 1e-4
