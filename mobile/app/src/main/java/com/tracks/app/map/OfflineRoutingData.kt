// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.content.Context
import android.util.Log
import com.tracks.core.api.RoutingDataManifest
import com.tracks.core.api.TracksClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor

/**
 * The routing data on the phone, for planning a route with the radio off.
 *
 * ## Why this is separate from the tiles
 *
 * [OfflineTiles] stores what the map *draws*. This stores what the router
 * *thinks with*, and they are not the same data or even the same shape: a
 * vector tile knows that a line exists and is tagged `highway=path`, but it is
 * clipped at the tile edge, simplified for the zoom it was cut at, and carries
 * no junction topology at all. Routing over it would produce a line that stops
 * dead at tile boundaries and takes a footbridge that does not connect.
 *
 * So BRouter's own rd5 segments come down alongside the tiles. They are on a
 * fixed 5° grid — nothing to do with the region the user drew — which means one
 * small region can pull one large file, and two adjacent regions usually share
 * it. The server decides which cells a bbox needs and only offers the ones it
 * actually holds.
 *
 * ## The layout is BRouter's, not ours
 *
 * `segments4/` and `profiles2/` are the directory names BRouter's own engine
 * expects, with `lookups.dat` sitting beside the `.brf` in the profile
 * directory because [btools.router.ProfileCache] resolves it as a sibling. This
 * is one of the few places where mirroring an upstream layout exactly is worth
 * more than a tidier one of our own.
 */
class OfflineRoutingData(context: Context) {

    private val root = File(context.applicationContext.filesDir, "brouter")
    val segmentsDir = File(root, "segments4")
    val profilesDir = File(root, "profiles2")

    /** Progress of one routing-data download, as it runs. */
    sealed interface Progress {
        data class Running(val bytes: Long, val required: Long) : Progress {
            val fraction: Float
                get() = if (required <= 0) 0f
                else (bytes.toDouble() / required).toFloat().coerceIn(0f, 1f)
        }

        data class Done(val bytes: Long) : Progress
        data class Failed(val reason: String) : Progress
    }

    /**
     * The profile file the engine routes with, or null if it has never been
     * downloaded. Its presence is what separates "can route offline" from
     * "holds some segments" — an rd5 with no `lookups.dat` beside a `.brf` is
     * unreadable, so both must be there before the engine is worth starting.
     */
    fun profile(): File? {
        val brf = File(profilesDir, TREKKING)
        val lookups = File(profilesDir, LOOKUPS)
        return if (brf.isFile && lookups.isFile) brf else null
    }

    /**
     * Whether every 5° cell touching this box is on the phone.
     *
     * All of them, not any: a route from one cell into a missing neighbour
     * fails partway through the search with an unhelpful message, and answering
     * "yes, offline routing is available" and then producing that is worse than
     * saying up front that it is not.
     */
    fun covers(west: Double, south: Double, east: Double, north: Double): Boolean {
        if (profile() == null) return false
        val names = cellNames(west, south, east, north)
        // An empty cover would mean a bbox that touches no cell, which cannot
        // happen; treated as "no" rather than vacuously true.
        return names.isNotEmpty() && names.all { File(segmentsDir, it).isFile }
    }

    /** Bytes held, for the storage line in the downloads sheet. */
    fun storedBytes(): Long =
        (segmentsDir.listFiles().orEmpty().toList() + profilesDir.listFiles().orEmpty().toList())
            .sumOf { file -> file.length() }

    /** Forget the routing data. Segments are shared between regions, so this is
     *  all-or-nothing rather than per-region. */
    fun clear(): Boolean = root.deleteRecursively()

    /**
     * Pull everything needed to route inside a box.
     *
     * Emits progress until it finishes or fails. Files already held are skipped
     * without asking the server for them again — the common case for a second
     * region in the same mountain range, where the 5° cell is already down.
     */
    fun download(
        client: TracksClient,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): Flow<Progress> = callbackFlow {
        val manifest: RoutingDataManifest = try {
            client.routingDataManifest(west, south, east, north)
        } catch (e: Exception) {
            trySend(Progress.Failed(e.message ?: "Could not ask for routing data"))
            channel.close()
            return@callbackFlow
        }

        segmentsDir.mkdirs()
        profilesDir.mkdirs()

        val wanted = manifest.files.filter { file ->
            val dest = destinationFor(file.kind, file.name)
            // Size as the freshness check. A file interrupted mid-download is
            // short, and re-fetching a segment that is already the size the
            // server promised would cost tens of megabytes to learn nothing.
            dest == null || !dest.isFile || dest.length() != file.bytes
        }
        val required = wanted.sumOf { it.bytes }
        var done = 0L

        for (file in wanted) {
            val dest = destinationFor(file.kind, file.name) ?: continue
            // Written to a scratch name and moved into place, so a download cut
            // off by a dead radio cannot leave a truncated rd5 that looks
            // complete enough for [covers] to promise routing over it.
            val part = File(dest.parentFile, dest.name + ".part")
            val fileStart = done
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    FileOutputStream(part).use { out ->
                        client.downloadRoutingFile(
                            kind = file.kind,
                            name = file.name,
                            onProgress = { soFar ->
                                trySend(Progress.Running(fileStart + soFar, required))
                            },
                        ) { buffer, length -> out.write(buffer, 0, length) }
                    }
                    part.renameTo(dest)
                }
            }.onFailure { Log.w(TAG, "routing file ${file.name} failed", it) }.getOrDefault(false)

            part.delete()
            if (!ok) {
                trySend(Progress.Failed("Could not download ${file.name}"))
                channel.close()
                return@callbackFlow
            }
            done += file.bytes
        }

        trySend(Progress.Done(storedBytes()))
        channel.close()
        awaitClose { }
    }

    private fun destinationFor(kind: String, name: String): File? = when (kind) {
        "segment" -> File(segmentsDir, name)
        "profile" -> File(profilesDir, name)
        else -> null
    }.takeIf {
        // The server validates these names too, but this side writes them to
        // disk, and a name from a server the user does not control should never
        // be able to name a path outside the directory it belongs in.
        it != null && !name.contains('/') && !name.contains("..")
    }

    private companion object {
        const val TAG = "TracksRouting"
        const val TREKKING = "trekking.brf"
        const val LOOKUPS = "lookups.dat"
    }
}

/**
 * The rd5 filenames covering a box, by BRouter's own naming rule.
 *
 * Duplicated from the server's `_cells_for_bbox` on purpose: the phone has to
 * answer "can I route here" with no network, which is precisely when it cannot
 * ask. The rule is BRouter's 5° grid and has not changed in a decade — `E{lon}`
 * / `W{|lon|}` and `N{lat}` / `S{|lat|}` of the cell's *lower-left* corner.
 */
internal fun cellNames(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
): List<String> {
    val names = mutableListOf<String>()
    var lon = floor(west / 5).toInt() * 5
    while (lon <= floor(east / 5).toInt() * 5) {
        var lat = floor(south / 5).toInt() * 5
        while (lat <= floor(north / 5).toInt() * 5) {
            val ew = if (lon < 0) "W${-lon}" else "E$lon"
            val ns = if (lat < 0) "S${-lat}" else "N$lat"
            names += "${ew}_$ns.rd5"
            lat += 5
        }
        lon += 5
    }
    return names
}
