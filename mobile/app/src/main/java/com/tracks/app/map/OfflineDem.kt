// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tracks.core.api.DEM_TILE_PX
import com.tracks.core.api.TracksClient
import com.tracks.core.api.bilinearElevation
import com.tracks.core.api.terrariumElevation
import com.tracks.core.api.webMercatorTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor

/**
 * Point elevation with no server to ask — the same DEM the server itself
 * samples from, one 512×512 Terrarium tile at a time.
 *
 * ## Why this is not a bulk region download, the way tiles/routing/POI are
 *
 * A downloaded region's basemap already brings these exact bytes to the
 * phone once, for MapLibre's 3D terrain drape — but into MapLibre's own
 * offline cache, which nothing else in the app has a supported way to read a
 * raster value back out of. Re-downloading every z8-z12 tile in a region a
 * second time, just so this class can decode them, would mean paying for the
 * same megabytes twice on every region anyone downloads.
 *
 * Fetching one small tile the moment a point is actually asked about, and
 * keeping it afterwards, costs bytes only for ground someone has actually
 * looked at — and after the first look, that point has elevation with no
 * signal at all, including places outside any downloaded region. It is a
 * smaller promise than "the whole region works offline", and an honest one:
 * a spot inside a downloaded region that has never been tapped is exactly as
 * unavailable offline as one outside it, until it has been.
 *
 * ## Zoom
 *
 * Tries z12 down to z8 for a point, coarsening exactly the way the server's
 * own `elevation_sampler.py` does when a finer tile is not held — the DEM
 * archive's regional detail runs z8-z12; nothing here promises worldwide z12.
 */
class OfflineDem(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "dem")

    /**
     * Elevation in metres at one point, or null if no held or fetchable tile
     * covers it at any zoom this sampler tries.
     */
    suspend fun elevation(client: TracksClient, lat: Double, lng: Double): Double? {
        for (zoom in MAX_ZOOM downTo MIN_ZOOM) {
            val (fx, fy) = webMercatorTile(lng, lat, zoom)
            val tx = floor(fx).toInt()
            val ty = floor(fy).toInt()
            val bitmap = tile(client, zoom, tx, ty) ?: continue
            val value = bilinearElevation(
                at = { x, y -> elevationAt(bitmap, x, y) },
                px = (fx - tx) * DEM_TILE_PX,
                py = (fy - ty) * DEM_TILE_PX,
                size = DEM_TILE_PX,
            )
            if (value != null) return value
        }
        return null
    }

    /** As [elevation], for every `(lat, lng)` along a route — for its profile. */
    suspend fun elevations(client: TracksClient, points: List<Pair<Double, Double>>): List<Double?> =
        points.map { (lat, lng) -> elevation(client, lat, lng) }

    /**
     * Fetch every z12 tile covering a region's bbox, so [elevation] never
     * needs the network for a point inside a downloaded area.
     *
     * Point elevation was designed to fetch lazily, one tap at a time, on the
     * theory that a spot nobody has looked at yet costs nothing to leave
     * unfetched. Tested for real, that theory did not survive contact with a
     * phone in airplane mode: a point tapped for the first time offline —
     * which, inside a region that was JUST downloaded, is every point — has
     * no tile on disk and nothing to fetch it with, and [elevation] returns
     * null exactly where the whole feature is supposed to work. This is the
     * fix — called once, right after the tiles themselves, alongside the
     * routing and POI downloads a region already brings down.
     *
     * Only z12, not the full z8-z12 band [elevation] falls back through: that
     * fallback exists for ground *outside* archived detail, which prefetching
     * the same bbox at every zoom cannot help — it would only pay for the
     * same ground five times. One point tapped inside the region always finds
     * its z12 tile first and never reaches the coarser levels at all.
     *
     * Best-effort per tile, like [com.tracks.app.map.OfflineRoutingData] and
     * [com.tracks.app.map.OfflinePoiData]: a tile the server has nothing for,
     * or a phone that loses signal partway through, costs a gap in coverage
     * rather than the region's basemap.
     */
    suspend fun prefetch(client: TracksClient, west: Double, south: Double, east: Double, north: Double) {
        val (xMinF, yMinF) = webMercatorTile(west, north, MAX_ZOOM)
        val (xMaxF, yMaxF) = webMercatorTile(east, south, MAX_ZOOM)
        val xRange = floor(xMinF).toInt()..floor(xMaxF).toInt()
        val yRange = floor(yMinF).toInt()..floor(yMaxF).toInt()
        for (x in xRange) {
            for (y in yRange) {
                runCatching { tile(client, MAX_ZOOM, x, y) }
            }
        }
    }

    /**
     * The decoded tile at `zoom/x/y`, held on disk already or fetched now.
     *
     * Disk first — the same "phone first, it's free" precedent
     * [OfflineRouter.snap] already set for routing — so a point already
     * looked at once never costs a request again, offline or not.
     */
    private suspend fun tile(client: TracksClient, zoom: Int, x: Int, y: Int): Bitmap? {
        val file = File(dir, "$zoom/$x/$y.webp")
        val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            ?: runCatching { client.demTile(zoom, x, y) }
                .onFailure { Log.w(TAG, "DEM tile $zoom/$x/$y fetch failed", it) }
                .getOrNull()?.also { fresh -> withContext(Dispatchers.IO) { store(file, fresh) } }
            ?: return null

        return withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    private fun store(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        // Written to a scratch name and moved into place, so a process killed
        // mid-write cannot leave a truncated tile that decodes into noise.
        val part = File(file.parentFile, file.name + ".part")
        part.writeBytes(bytes)
        part.renameTo(file)
    }

    /** One pixel's elevation, or null for the DEM's own "no data" sentinel — see [terrariumElevation]. */
    private fun elevationAt(bitmap: Bitmap, x: Int, y: Int): Double? {
        val pixel = bitmap.getPixel(x, y)
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return terrariumElevation(r, g, b).takeIf { it >= NO_DATA_FLOOR }
    }

    /** Bytes on disk, for a downloads sheet that wants to say what this cost. */
    fun storedBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Forget every tile ever looked at. */
    fun clear(): Boolean = !dir.exists() || dir.deleteRecursively()

    private companion object {
        const val TAG = "TracksOfflineDem"
        const val MAX_ZOOM = 12
        const val MIN_ZOOM = 8

        /** Same floor `elevation_sampler.py` applies: below this is void, not ground. */
        const val NO_DATA_FLOOR = -500.0
    }
}
