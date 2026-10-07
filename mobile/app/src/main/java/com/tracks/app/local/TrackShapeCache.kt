// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.local

import android.content.Context
import com.tracks.core.api.TrackShape
import com.tracks.core.api.TrackShapes
import java.io.File

/**
 * Route outlines for the activity list, kept between launches.
 *
 * An outline costs decoding the activity's whole detail file, and a track
 * never changes once recorded, so each is worked out once and then read back
 * from here — a second visit to the list draws every thumbnail at once.
 *
 * ## Why a plain file, outside the encrypted mirror
 *
 * Because of what is in it. A [TrackShape] is the route normalised into a
 * unit square: no coordinates, no scale, nothing that says where on earth it
 * was (core TrackShapes, and the server's calculators/track_outline.py, which
 * the browser caches the same way). The tracks themselves stay in the
 * encrypted mirror; this must never hold them. It is in `noBackupFilesDir`,
 * so it is the app's alone and is not copied into a cloud backup.
 *
 * Keyed by uid — the file's identity — so an outline survives an alias being
 * reassigned. A uid known to have no track is stored as null, so an activity
 * with a distance but no GPS (a treadmill) is decoded once, not on every visit.
 */
class TrackShapeCache(context: Context) {

    private val file = File(context.noBackupFilesDir, "track-shapes.v1.json")
    private val lock = Any()
    private var memo: MutableMap<String, TrackShape?>? = null

    private fun loaded(): MutableMap<String, TrackShape?> = synchronized(lock) {
        memo ?: read().also { memo = it }
    }

    /** Everything known, uid → shape (or null for "has none"). */
    fun snapshot(): Map<String, TrackShape?> = synchronized(lock) { HashMap(loaded()) }

    fun put(uid: String, shape: TrackShape?) = synchronized(lock) { loaded()[uid] = shape }

    /** Write what is in memory. Called every so often, not per outline. */
    fun flush() {
        val copy = synchronized(lock) { HashMap(memo ?: return) }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(TrackShapes.encodeCache(copy))
            tmp.renameTo(file)
        }
    }

    private fun read(): MutableMap<String, TrackShape?> =
        if (file.exists()) runCatching { TrackShapes.decodeCache(file.readText()) }.getOrElse { HashMap() }
        else HashMap()
}
