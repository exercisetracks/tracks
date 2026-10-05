// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.content.Context
import android.util.Log
import com.tracks.core.api.OfflinePoi
import com.tracks.core.api.PoiHit
import com.tracks.core.api.TracksClient
import com.tracks.core.api.decodeOfflinePoi
import com.tracks.core.api.encodeOfflinePoi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The phone's own copy of the gazetteer, for the ground it has downloaded.
 *
 * ## Why this exists
 *
 * `searchPoi` leans on the server's full-text and trigram indexes over
 * `poi_search` — real work Postgres does and a phone should not try to
 * reproduce. But someone standing at a trailhead with no signal still wants
 * to find "crescent lake" among the places near a region they downloaded, and
 * no search at all offline is a worse answer than a plainer one. This is
 * that plainer one: everything named inside a downloaded region's bounds,
 * kept as a flat file and scored with nothing cleverer than a substring
 * match.
 *
 * ## Where it lives
 *
 * A plain file, not the encrypted mirror
 * [com.tracks.core.sync.OfflineRepository] writes into. Same reasoning as
 * [MapStyleCache]: this is public cartography — place names and positions off
 * OpenStreetMap and GNIS — with nothing about the user in it, and putting it
 * through SQLCipher would buy nothing.
 *
 * ## Merging across regions
 *
 * Two downloaded regions can overlap, so a download does not replace what is
 * already on disk — it merges by [OfflinePoi.id], the server's own primary
 * key and the one thing two independent exports of the same point are
 * guaranteed to agree on. The fresh copy wins a conflict, on the off chance
 * the gazetteer itself changed between two downloads.
 */
class OfflinePoiData(context: Context) {

    private val file = File(context.applicationContext.filesDir, "offline_poi.json")

    @Volatile
    private var cache: List<OfflinePoi>? = null

    /**
     * Bring down everything named inside a box.
     *
     * Non-fatal by design, like [com.tracks.app.map.OfflineRoutingData]'s own
     * download — a region's map and routing data are the thing the user asked
     * for, and this is a convenience on top that a phone can perfectly well
     * work without. False means only that offline search will not cover this
     * area; nothing else about the download failed. Rejected outright by the
     * server for a box wider than a few degrees, the same limit
     * [TracksClient.routingDataManifest] enforces and this treats the same
     * non-fatal way, rather than chunking a big region into many requests.
     */
    suspend fun download(client: TracksClient, west: Double, south: Double, east: Double, north: Double): Boolean {
        val export = runCatching { client.offlinePoi(west, south, east, north) }
            .onFailure { Log.w(TAG, "offline POI export failed", it) }
            .getOrNull() ?: return false

        withContext(Dispatchers.IO) {
            val merged = (export.points + loadFromDisk()).distinctBy { it.id }
            // Written to a scratch name and moved into place, so a process
            // killed mid-write cannot leave a truncated file that fails to
            // decode on the next search.
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(encodeOfflinePoi(merged))
            part.renameTo(file)
        }
        cache = null
        return true
    }

    /**
     * The best matches for [query] among whatever has been downloaded.
     *
     * Ranked with nothing the server's own `/search` has: no full-text index,
     * no trigram similarity, no viewport bias — a name that starts with the
     * query wins over one that merely contains it, and among equal matches
     * the shorter name wins, which is most of what makes "glen" find "Glen"
     * before "Glen Pass Winter Recreation Area". Good enough to find a
     * trailhead in a few hundred names; not a replacement for the real thing
     * when there is a server to ask, which is why [MapToolsViewModel] only
     * reaches this when the network attempt has already failed.
     */
    suspend fun search(query: String, limit: Int = 10): List<PoiHit> = withContext(Dispatchers.Default) {
        rankOfflinePoi(points(), query, limit).map { it.asHit() }
    }

    /** Bytes on disk, for a downloads sheet that wants to say what this cost. */
    fun storedBytes(): Long = file.length()

    /** Forget every downloaded point. Shared across regions, like the routing data. */
    fun clear(): Boolean {
        cache = null
        return !file.exists() || file.delete()
    }

    private suspend fun points(): List<OfflinePoi> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) { loadFromDisk() }
        cache = loaded
        return loaded
    }

    private fun loadFromDisk(): List<OfflinePoi> = runCatching {
        if (!file.isFile) emptyList() else decodeOfflinePoi(file.readText())
    }.getOrDefault(emptyList())

    private fun OfflinePoi.asHit() = PoiHit(
        name = name,
        description = kind?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }.orEmpty(),
        lat = lat,
        lng = lng,
    )

    private companion object {
        const val TAG = "TracksOfflinePoi"
    }
}

/**
 * Rank [candidates] against [query], best match first.
 *
 * A free function rather than a private method, so [OfflinePoiData.search]'s
 * only untested part is the file I/O around it — the ranking itself is pure
 * and worth checking without a `Context` or a filesystem in the way. See
 * [OfflinePoiData.search]'s own doc comment for what "best" means here.
 */
internal fun rankOfflinePoi(candidates: List<OfflinePoi>, query: String, limit: Int = 10): List<OfflinePoi> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return candidates.asSequence()
        .mapNotNull { point ->
            val name = point.name.lowercase()
            val score = when {
                name == needle -> 3
                name.startsWith(needle) -> 2
                name.contains(needle) -> 1
                else -> return@mapNotNull null
            }
            Triple(point, score, point.name.length)
        }
        .sortedWith(compareByDescending<Triple<OfflinePoi, Int, Int>> { it.second }.thenBy { it.third })
        .take(limit)
        .map { it.first }
        .toList()
}
