// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.util.Log
import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BRouter, running on the phone.
 *
 * ## The same engine, not a simplified one
 *
 * This is the routing core the `brouter` container runs, vendored into
 * `:routing-brouter` and handed the same rd5 segments and the same
 * `trekking.brf`. That matters more than it sounds: a route planned in a valley
 * and the same route re-planned at home must agree, or the feature is a
 * different feature depending on signal. Because both ends finish in BRouter's
 * own [FormatJson], an offline result is not merely equivalent to the server's
 * — it is the same document, down to the `filtered ascend` property the map
 * screen reads.
 *
 * ## What it costs
 *
 * The search is CPU and memory over memory-mapped rd5, so it runs on the IO
 * dispatcher and is given a deadline. BRouter's own Android app routes tens of
 * kilometres in a second or two on hardware far older than this; a route that
 * has not finished in [TIMEOUT_MS] is not slow, it is a search that has escaped
 * into a region with no connecting ways and would otherwise run until the
 * process died.
 */
class OfflineRouter(private val data: OfflineRoutingData) {

    /**
     * Snap [coordinates] (in `[lng, lat]` order, as the server takes them) to
     * trails, returning BRouter's GeoJSON.
     *
     * Returns null when the phone cannot answer at all — no profile, a missing
     * segment, or a search that found nothing. Null rather than an exception
     * because "route online instead" is the caller's ordinary next move, not an
     * error path.
     */
    suspend fun snap(
        coordinates: List<List<Double>>,
    ): String? = withContext(Dispatchers.IO) {
        if (coordinates.size < 2) return@withContext null

        val profile = data.profile() ?: return@withContext null
        val lngs = coordinates.map { it[0] }
        val lats = coordinates.map { it[1] }
        if (!data.covers(lngs.min(), lats.min(), lngs.max(), lats.max())) return@withContext null

        val context = RoutingContext().apply {
            // A path, not a name. With no `profileBaseDir` system property set,
            // ProfileCache treats this as the file itself and looks for
            // lookups.dat next to it — which is why the two are downloaded into
            // the same directory.
            localFunction = profile.absolutePath
        }

        val waypoints = coordinates.mapIndexed { index, point ->
            OsmNodeNamed().apply {
                name = when (index) {
                    0 -> "from"
                    coordinates.lastIndex -> "to"
                    else -> "via$index"
                }
                ilon = ((point[0] + 180.0) * 1_000_000.0 + 0.5).toInt()
                ilat = ((point[1] + 90.0) * 1_000_000.0 + 0.5).toInt()
            }
        }

        runCatching {
            val engine = RoutingEngine(
                // No output file and no log file: the result is wanted in
                // memory. Passing a log base also switches on BRouter's own
                // verbose info logging, which is not useful here.
                null,
                null,
                data.segmentsDir,
                waypoints,
                context,
                RoutingEngine.BROUTER_ENGINEMODE_ROUTING,
            )
            engine.quite = true
            engine.doRun(TIMEOUT_MS)

            val error = engine.errorMessage
            if (error != null) {
                Log.i(TAG, "offline route not found: $error")
                return@runCatching null
            }
            val track = engine.foundTrack ?: return@runCatching null
            FormatJson(context).format(track)
        }.onFailure {
            // Includes the OOM path BRouter guards internally: an rd5 for a
            // dense area plus a long search is the one thing here that can
            // genuinely exhaust a phone.
            Log.w(TAG, "offline routing failed", it)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "TracksRouting"

        /** Generous for a day's route, short enough that a search which has
         *  wandered into unconnected ways gives the map back to the user. */
        const val TIMEOUT_MS = 30_000L
    }
}
