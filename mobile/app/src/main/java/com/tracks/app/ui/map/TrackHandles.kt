// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The handles a track was drawn with, so it can be edited the way it was made.
 *
 * A saved track stores only its line; the taps the line was routed between
 * are gone, and a line of several hundred vertices is not something anyone can
 * edit by hand. So they are kept here, on this phone, per track. A track
 * without them — drawn before this existed, imported, or drawn on the web —
 * gets handles by simplifying its line, and opens with snapping off, so
 * nothing moves until someone moves it.
 *
 * Phone-local rather than a synced column: the handles are an editing aid, not
 * part of the track, and the line itself is what syncs everywhere.
 */
class TrackHandles(context: Context) {

    data class Saved(val handles: List<LatLng>, val snapToTrails: Boolean)

    private val prefs = context.getSharedPreferences("track_handles", Context.MODE_PRIVATE)

    fun load(trackUid: String): Saved? {
        val raw = prefs.getString(trackUid, null) ?: return null
        val parts = raw.split(';')
        val snap = parts.firstOrNull() == "1"
        val handles = parts.drop(1).mapNotNull { p ->
            val ll = p.split(',')
            if (ll.size != 2) null else runCatching { LatLng(ll[0].toDouble(), ll[1].toDouble()) }.getOrNull()
        }
        return if (handles.size >= 2) Saved(handles, snap) else null
    }

    fun save(trackUid: String, handles: List<LatLng>, snapToTrails: Boolean) {
        val raw = (listOf(if (snapToTrails) "1" else "0") + handles.map { "${it.latitude},${it.longitude}" })
            .joinToString(";")
        prefs.edit().putString(trackUid, raw).apply()
    }

    companion object {
        /** Enough to shape a long route, few enough to edit on a phone. */
        const val MAX_HANDLES = 60

        /**
         * Handles for a line that has none: Ramer–Douglas–Peucker, loosened
         * until the result is editable. Starts at 10 m, so a short route keeps
         * its shape exactly where it matters.
         */
        fun fromLine(line: List<LatLng>): List<LatLng> {
            if (line.size <= 2) return line
            var tolerance = 10.0
            var out = simplify(line, tolerance)
            while (out.size > MAX_HANDLES) {
                tolerance *= 1.5
                out = simplify(line, tolerance)
            }
            return out
        }

        private fun simplify(points: List<LatLng>, toleranceM: Double): List<LatLng> {
            val keep = BooleanArray(points.size)
            keep[0] = true
            keep[points.lastIndex] = true
            val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var far = -1
                var farDist = toleranceM
                for (i in a + 1 until b) {
                    val d = offsetMetres(points[i], points[a], points[b])
                    if (d > farDist) { farDist = d; far = i }
                }
                if (far >= 0) {
                    keep[far] = true
                    stack.add(a to far)
                    stack.add(far to b)
                }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }

        /** Distance from [p] to the segment [a]–[b], in metres, on a local flat projection. */
        private fun offsetMetres(p: LatLng, a: LatLng, b: LatLng): Double {
            val k = cos(Math.toRadians(a.latitude)) * 111_320.0
            fun x(q: LatLng) = (q.longitude - a.longitude) * k
            fun y(q: LatLng) = (q.latitude - a.latitude) * 110_540.0
            val bx = x(b); val by = y(b); val px = x(p); val py = y(p)
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            return hypot(px - t * bx, py - t * by)
        }
    }
}
