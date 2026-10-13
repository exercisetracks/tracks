// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A track's height against distance, built on the phone.
 *
 * The server builds this too (`profile`, resampled and DEM-corrected), but
 * only after a sync — so a track drawn or edited on the phone showed "Loading
 * the height profile…" until the next round trip, and forever offline. Built
 * here it is there at once, in the same shape the server writes, and syncs
 * out like any other edit.
 *
 * Elevations come from the line itself when it carries them (a route from
 * BRouter does, as do most imports), and otherwise from [sample] — the offline
 * DEM, which a downloaded region prefetches. Resampled to at most [SAMPLES]
 * points evenly by distance, so a long line costs no more than a short one.
 */
object TrackProfile {

    const val SAMPLES = 200

    /** `[lng, lat, ele?]` per vertex in; the stored `profile` object out, or null. */
    suspend fun build(
        coords: List<List<Double?>>,
        sample: suspend (lat: Double, lng: Double) -> Double?,
    ): Map<String, Any?>? {
        val pts = coords.filter { it.size >= 2 && it[0] != null && it[1] != null }
        if (pts.size < 2) return null
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val mean = (a[1]!! + b[1]!!) / 2 * kotlin.math.PI / 180
            cum[i] = cum[i - 1] + hypot((b[0]!! - a[0]!!) * cos(mean) * 111_320.0, (b[1]!! - a[1]!!) * 110_540.0)
        }
        val total = cum.last()
        if (total <= 0.0) return null
        val ownElevation = pts.count { it.getOrNull(2) != null } >= pts.size * 0.9

        val n = minOf(SAMPLES, pts.size.coerceAtLeast(2))
        val points = ArrayList<Pair<Double, Double?>>(n)
        var seg = 1
        for (k in 0 until n) {
            val d = total * k / (n - 1)
            while (seg < pts.lastIndex && cum[seg] < d) seg++
            val a = pts[seg - 1]; val b = pts[seg]
            val span = cum[seg] - cum[seg - 1]
            val t = if (span <= 0.0) 0.0 else ((d - cum[seg - 1]) / span).coerceIn(0.0, 1.0)
            val lng = a[0]!! + (b[0]!! - a[0]!!) * t
            val lat = a[1]!! + (b[1]!! - a[1]!!) * t
            val ele = if (ownElevation) {
                val ea = a.getOrNull(2); val eb = b.getOrNull(2)
                if (ea != null && eb != null) ea + (eb - ea) * t else ea ?: eb
            } else {
                sample(lat, lng)
            }
            points += d to ele
        }
        val known = points.mapNotNull { it.second }
        if (known.size < 2) return null

        var gain = 0.0
        var loss = 0.0
        var prev: Double? = null
        for (e in known) {
            prev?.let { if (e > it) gain += e - it else loss += it - e }
            prev = e
        }
        return mapOf(
            "points" to points.map { (d, e) -> mapOf("d_km" to round3(d / 1000), "ele_m" to e?.let(::round1)) },
            "gain_m" to round1(gain),
            "loss_m" to round1(loss),
            "min_m" to round1(known.min()),
            "max_m" to round1(known.max()),
        )
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
}
