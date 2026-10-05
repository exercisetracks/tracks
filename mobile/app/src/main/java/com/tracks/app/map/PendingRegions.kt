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
 * Server ids rather than names or boxes: the id is the thing both halves agree
 * on, and it is what the phone's own region metadata is keyed by.
 */
internal object PendingRegions {

    /** Areas whose phone copy has been asked for and not yet finished. */
    fun wanted(context: Context): Set<Int> =
        prefs(context).getStringSet(KEY_WANTED, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()

    fun want(context: Context, serverRegionId: Int) {
        write(context, wanted(context) + serverRegionId)
    }

    fun settled(context: Context, serverRegionId: Int) {
        write(context, wanted(context) - serverRegionId)
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

    private fun write(context: Context, ids: Set<Int>) {
        prefs(context).edit()
            .putStringSet(KEY_WANTED, ids.map(Int::toString).toSet())
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "tracks_offline_maps"
    private const val KEY_WANTED = "wanted_region_ids"
    private const val KEY_COVERAGE = "server_coverage_ids"
}
