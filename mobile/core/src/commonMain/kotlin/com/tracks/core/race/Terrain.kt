// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Race splits that follow the course instead of the kilometre markers.
 *
 * The server's `calculators/race_predictor/terrain.py`, rule for rule (its
 * header has the reasoning), and held to it by spec/fixtures/race_predictor.json:
 * the course's ~100 m samples, lightly smoothed, banded into steep descent /
 * descent / flat / climb / steep climb; runs of one band are a segment; what
 * is too short to pace on its own is folded into the neighbour nearest in
 * grade — a steep one needs only [MIN_STEEP_M], so a short steep hill keeps a
 * target of its own; long stretches are cut at [MAX_M]; and the most alike
 * neighbours are merged until a Garmin workout can carry them.
 */
object Terrain {
    data class Piece(val distanceM: Double, val gradient: Double, val kind: String)

    const val MIN_M = 300.0
    const val MIN_STEEP_M = 150.0
    const val MAX_M = 2000.0
    const val MAX_SEGMENTS = 48

    private val LABELS = mapOf(
        "steep_up" to "Steep climb", "up" to "Climb", "flat" to "Flat",
        "down" to "Descent", "steep_down" to "Steep descent",
    )

    fun band(g: Double): String = when {
        g < -0.06 -> "steep_down"
        g < -0.025 -> "down"
        g < 0.025 -> "flat"
        g < 0.06 -> "up"
        else -> "steep_up"
    }

    private fun minLen(kind: String) = if (kind.startsWith("steep")) MIN_STEEP_M else MIN_M

    private fun merge(a: Piece, b: Piece): Piece {
        val d = a.distanceM + b.distanceM
        val g = (a.gradient * a.distanceM + b.gradient * b.distanceM) / d
        return Piece(d, g, band(g))
    }

    fun segments(course: List<Segment>, distanceM: Double, maxSegments: Int = MAX_SEGMENTS): List<Piece> {
        val raw = course.filter { it.distanceM > 0 }.map { it.distanceM to it.gradient }
        val total = raw.sumOf { it.first }
        if (raw.isEmpty() || total <= 0 || distanceM <= 0) return listOf(Piece(distanceM, 0.0, "flat"))
        val scale = distanceM / total

        val n = raw.size
        val segs = ArrayList<Piece>()
        for (i in 0 until n) {
            val (d0, g0) = raw[i]
            val lo = if (i > 0) raw[i - 1].second else g0
            val hi = if (i < n - 1) raw[i + 1].second else g0
            val d = d0 * scale
            val g = 0.25 * lo + 0.5 * g0 + 0.25 * hi
            val k = band(g)
            val last = segs.lastOrNull()
            if (last != null && last.kind == k) {
                val tot = last.distanceM + d
                segs[segs.lastIndex] = Piece(tot, (last.gradient * last.distanceM + g * d) / tot, k)
            } else {
                segs += Piece(d, g, k)
            }
        }

        var cur: MutableList<Piece> = segs
        while (cur.size > 1) {
            val short = cur.indices.filter { cur[it].distanceM < minLen(cur[it].kind) }
            if (short.isEmpty()) break
            val i = short.minBy { cur[it].distanceM }
            val j = nearest(cur, i)
            val lo = minOf(i, j)
            val hi = maxOf(i, j)
            val merged = merge(cur[lo], cur[hi])
            cur = (cur.subList(0, lo) + merged + cur.subList(hi + 1, cur.size)).toMutableList()
            cur = coalesce(cur)
        }

        val cut = ArrayList<Piece>()
        for (s in cur) {
            val pieces = maxOf(1, ceil(s.distanceM / MAX_M).toInt())
            repeat(pieces) { cut += s.copy(distanceM = s.distanceM / pieces) }
        }
        var out: MutableList<Piece> = cut
        while (out.size > maxSegments) {
            val i = (0 until out.size - 1).minWith(
                compareBy<Int>({ abs(out[it].gradient - out[it + 1].gradient) },
                    { out[it].distanceM + out[it + 1].distanceM }),
            )
            val merged = merge(out[i], out[i + 1])
            out = (out.subList(0, i) + merged + out.subList(i + 2, out.size)).toMutableList()
        }
        return out
    }

    private fun nearest(segs: List<Piece>, i: Int): Int {
        if (i == 0) return 1
        if (i == segs.lastIndex) return i - 1
        val g = segs[i].gradient
        return if (abs(segs[i - 1].gradient - g) <= abs(segs[i + 1].gradient - g)) i - 1 else i + 1
    }

    private fun coalesce(segs: List<Piece>): MutableList<Piece> {
        val out = ArrayList<Piece>()
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && last.kind == s.kind) out[out.lastIndex] = merge(last, s) else out += s
        }
        return out
    }

    /** "Climb 4%" — the segment's name on the watch, the table and the voice. */
    fun label(kind: String, gradient: Double): String {
        val pct = PyRound.half(abs(gradient) * 100)
        val name = LABELS.getValue(kind)
        return if (kind == "flat" || pct == 0) name else "$name $pct%"
    }
}

/** Python's round() to an int: half to even. */
internal object PyRound {
    fun half(x: Double): Int {
        val f = kotlin.math.floor(x)
        val diff = x - f
        return when {
            diff > 0.5 -> (f + 1).toInt()
            diff < 0.5 -> f.toInt()
            else -> if (f.toInt() % 2 == 0) f.toInt() else (f + 1).toInt()
        }
    }
}
