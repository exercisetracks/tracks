// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.map

import android.content.Context

/**
 * The areas somebody asked to have on this phone, remembered across restarts.
 *
 * ## Why an intention needs storing at all
 *
 * A region download is two waits, and the first one is the server's: minutes of
 * tippecanoe, sometimes tens of minutes. The phone's half cannot start until
 * that finishes, so the app spends the whole extraction holding nothing but the
 * *knowledge* that it is supposed to pull this area down afterwards — in a
 * coroutine, in a ViewModel, in a process Android is free to kill at any point.
 *
 * When that happened the intention died with it. The server finished a perfect
 * extraction, the phone came back, listed the regions, saw one it did not have,
 * and drew it as "on the server only" — a completed download that had silently
 * become a no-op. The user's part had been done for half an hour by then.
 *
 * So the request outlives the process. It is written when somebody asks for an
 * area and erased when the phone actually holds it, which makes the pair of
 * them a promise rather than a coincidence of timing.
 *
 * Keyed by the server's id, which is what the build is watched by, and
 * carrying the box and name the person framed: with packs the phone saves
 * exactly what was framed (see map/packs), which may be a small piece of a
 * large server area.
 */
internal object PendingRegions {

    data class Request(val name: String, val bbox: List<Double>)

    /** Areas whose phone copy has been asked for and not yet started. */
    fun requests(context: Context): Map<Int, Request> = runCatching {
        val o = org.json.JSONObject(prefs(context).getString(KEY_REQUESTS, "{}") ?: "{}")
        o.keys().asSequence().associate { k ->
            val r = o.getJSONObject(k)
            k.toInt() to Request(r.getString("name"),
                                 r.getJSONArray("bbox").let { b -> (0 until b.length()).map(b::getDouble) })
        }
    }.getOrDefault(emptyMap())

    fun wanted(context: Context): Set<Int> = requests(context).keys

    fun want(context: Context, serverRegionId: Int, name: String, bbox: List<Double>) {
        write(context, requests(context) + (serverRegionId to Request(name, bbox)))
    }

    fun settled(context: Context, serverRegionId: Int) {
        write(context, requests(context) - serverRegionId)
    }

    /**
     * The set of areas the server was serving at full detail last time we
     * looked, so a change can be noticed rather than assumed.
     *
     * Persisted for the same reason as the wants: the interesting comparison is
     * against what this phone last *saw*, not against what it saw this session.
     * An area downloaded in the browser while the app was closed is exactly the
     * case where the phone is holding a stale style and a cache full of "there
     * is nothing there", and it is invisible to any check that only remembers
     * as far back as the last launch.
     */
    fun lastServerCoverage(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY_COVERAGE, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()

    fun rememberServerCoverage(context: Context, ids: Set<Int>) {
        prefs(context).edit()
            .putStringSet(KEY_COVERAGE, ids.map(Int::toString).toSet())
            .apply()
    }

    private fun write(context: Context, requests: Map<Int, Request>) {
        val o = org.json.JSONObject()
        requests.forEach { (id, r) ->
            o.put(id.toString(), org.json.JSONObject().put("name", r.name).put("bbox", org.json.JSONArray(r.bbox)))
        }
        prefs(context).edit().putString(KEY_REQUESTS, o.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "tracks_offline_maps"
    private const val KEY_REQUESTS = "requested_areas"
    private const val KEY_COVERAGE = "server_coverage_ids"
}
