// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import com.tracks.core.parse.PyMath
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Race splits that follow the course instead of the kilometre markers.
 *
 * The server's `calculators/race_predictor/terrain.py`, rule for rule (its
 * header has the reasoning), and held to it by spec/fixtures/race_predictor.json:
 * the course's ~100 m [samples], lightly smoothed, banded into steep descent /
 * descent / flat / climb / steep climb; runs of one band are a segment; what
 * is too short to pace on its own is shared between two different neighbours
 * (a crest: each side takes its own half) or folded into the neighbour
 * nearest in grade — a steep one needs only [MIN_STEEP_M] and a climb
 * [MIN_CLIMB_M], so a short hill keeps a target of its own; long stretches
 * are cut at [MAX_M]; and the most alike neighbours are merged until a Garmin
 * workout can carry them.
 *
 * Each segment is priced by the ground it covers — the mean of its raw
 * samples' Minetti multipliers ([Piece.multiplier]), not its mean grade's —
 * so a hill inside a segment still counts, and the finish, the samples'
 * total ([courseGradeFactor]), is the same however the course is cut.
 */
object Terrain {
    /** [gradient] is the segment's mean; [multiplier] its price. */
    data class Piece(val distanceM: Double, val gradient: Double, val kind: String, val multiplier: Double = 1.0)

    /** One ~100 m sample: its length scaled to the race, its own grade, and the smoothed grade that bands it. */
    data class Sample(val distanceM: Double, val gradient: Double, val smoothed: Double)

    const val MIN_M = 300.0
    const val MIN_CLIMB_M = 200.0
    const val MIN_STEEP_M = 150.0
    const val MAX_M = 2000.0
    const val MAX_SEGMENTS = 48

    /** A flat segment this much dearer than its own mean grade is rolling ground. */
    const val ROLLING_EXCESS = 0.02

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

    private fun minLen(kind: String) = when {
        kind.startsWith("steep") -> MIN_STEEP_M
        kind == "up" -> MIN_CLIMB_M
        else -> MIN_M
    }

    /** A segment while the course is being cut: banded and compared by [gradient] (smoothed), priced by [gain] and [cost] (raw). */
    private class Seg(
        val samples: List<Sample>?,
        val distanceM: Double,
        val gradient: Double,
        val kind: String,
        val gain: Double,
        val cost: Double,
    )

    /** `_piece`: a run of samples; [kind] keeps a band the mean alone would not give it. */
    private fun piece(smp: List<Sample>, kind: String? = null): Seg {
        val d = PyMath.sum(smp.map { it.distanceM })
        val g = PyMath.sum(smp.map { it.distanceM * it.smoothed }) / d
        return Seg(
            smp, d, g, kind ?: band(g),
            PyMath.sum(smp.map { it.distanceM * it.gradient }),
            PyMath.sum(smp.map { it.distanceM * RacePredictor.gradeCostMultiplier(it.gradient) }),
        )
    }

    /** `_merge`, for segments already cut: their sums added, rebanded by the mean. */
    private fun merge(a: Seg, b: Seg): Seg {
        val d = a.distanceM + b.distanceM
        val g = (a.gradient * a.distanceM + b.gradient * b.distanceM) / d
        return Seg(null, d, g, band(g), a.gain + b.gain, a.cost + b.cost)
    }

    /**
     * `samples`: the course scaled to [distanceM], each sample with its own
     * grade and the ¼ ½ ¼ smoothed one. Empty without a course.
     */
    fun samples(course: List<Segment>?, distanceM: Double): List<Sample> {
        val raw = course.orEmpty().filter { it.distanceM > 0 }.map { it.distanceM to it.gradient }
        val total = PyMath.sum(raw.map { it.first })
        if (raw.isEmpty() || total <= 0 || distanceM <= 0) return emptyList()
        val scale = distanceM / total
        val n = raw.size
        return List(n) { i ->
            val (d, g) = raw[i]
            val lo = if (i > 0) raw[i - 1].second else g
            val hi = if (i < n - 1) raw[i + 1].second else g
            Sample(d * scale, g, 0.25 * lo + 0.5 * g + 0.25 * hi)
        }
    }

    /**
     * `ground`: the rise and the cost (metres × multiplier) of each of
     * [lengths], laid end to end along [smp] so each metre counts in exactly
     * one; ground past the samples' end is flat. What a lap or a checkpoint
     * is priced by.
     */
    fun ground(smp: List<Sample>, lengths: List<Double>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(lengths.size)
        var cursor = 0
        var consumed = 0.0
        for (length in lengths) {
            var gain = 0.0
            var cost = 0.0
            var remaining = length
            while (remaining > 0 && cursor < smp.size) {
                val x = smp[cursor]
                val take = kotlin.math.min(remaining, x.distanceM - consumed)
                gain += x.gradient * take
                cost += RacePredictor.gradeCostMultiplier(x.gradient) * take
                remaining -= take
                consumed += take
                if (consumed >= x.distanceM - 0.001) {
                    cursor += 1
                    consumed = 0.0
                }
            }
            if (remaining > 0) cost += remaining
            out += gain to cost
        }
        return out
    }

    /** `course_grade_factor`: what the course's ground does to a running finish, 1.0 without a course. */
    fun courseGradeFactor(course: List<Segment>?, distanceM: Double): Double {
        val smp = samples(course, distanceM)
        if (smp.isEmpty()) return 1.0
        return PyMath.sum(smp.map { it.distanceM * RacePredictor.gradeCostMultiplier(it.gradient) }) /
            PyMath.sum(smp.map { it.distanceM })
    }

    /** `_share`: how many of a short piece's samples go to its left neighbour, the rest going right. */
    private fun share(smp: List<Sample>, left: Seg, right: Seg): Int {
        val gl = left.gain / left.distanceM
        val gr = right.gain / right.distanceM
        var bestT = 0
        var best: Double? = null
        for (t in 0..smp.size) {
            val err = PyMath.sum(smp.subList(0, t).map { it.distanceM * (it.gradient - gl) * (it.gradient - gl) }) +
                PyMath.sum(smp.subList(t, smp.size).map { it.distanceM * (it.gradient - gr) * (it.gradient - gr) })
            if (best == null || err < best) {
                bestT = t
                best = err
            }
        }
        return bestT
    }

    fun segments(course: List<Segment>, distanceM: Double, maxSegments: Int = MAX_SEGMENTS): List<Piece> {
        val smp = samples(course, distanceM)
        if (smp.isEmpty()) return listOf(Piece(distanceM, 0.0, "flat", 1.0))

        val runs = ArrayList<MutableList<Sample>>()
        val kinds = ArrayList<String>()
        for (x in smp) {
            val k = band(x.smoothed)
            if (runs.isNotEmpty() && kinds.last() == k) runs.last() += x else {
                runs += mutableListOf(x)
                kinds += k
            }
        }
        var segs: MutableList<Seg> = runs.indices.map { piece(runs[it], kinds[it]) }.toMutableList()

        while (segs.size > 1) {
            val short = segs.indices.filter { segs[it].distanceM < minLen(segs[it].kind) }
            if (short.isEmpty()) break
            val i = short.minBy { segs[it].distanceM }
            val next: MutableList<Seg>
            if (i > 0 && i < segs.size - 1 && segs[i - 1].kind != segs[i + 1].kind) {
                val left = segs[i - 1]
                val mid = segs[i].samples!!
                val right = segs[i + 1]
                val t = share(mid, left, right)
                next = (segs.subList(0, i - 1) +
                    piece(left.samples!! + mid.subList(0, t), left.kind) +
                    piece(mid.subList(t, mid.size) + right.samples!!, right.kind) +
                    segs.subList(i + 2, segs.size)).toMutableList()
            } else {
                val j = nearest(segs, i)
                val lo = minOf(i, j)
                val hi = maxOf(i, j)
                next = (segs.subList(0, lo) + piece(segs[lo].samples!! + segs[hi].samples!!) +
                    segs.subList(hi + 1, segs.size)).toMutableList()
            }
            segs = coalesce(next)
        }

        // Checkpoints on a long stretch, each priced by its own ground.
        val cut = ArrayList<Seg>()
        for (s in segs) {
            val pieces = maxOf(1, ceil(s.distanceM / MAX_M).toInt())
            if (pieces == 1) {
                cut += s
                continue
            }
            val length = s.distanceM / pieces
            for ((gain, cost) in ground(s.samples!!, List(pieces) { length })) {
                cut += Seg(null, length, s.gradient, s.kind, gain, cost)
            }
        }
        var out: MutableList<Seg> = cut
        while (out.size > maxSegments) {
            val i = (0 until out.size - 1).minWith(
                compareBy<Int>({ abs(out[it].gradient - out[it + 1].gradient) },
                    { out[it].distanceM + out[it + 1].distanceM }),
            )
            val merged = merge(out[i], out[i + 1])
            out = (out.subList(0, i) + merged + out.subList(i + 2, out.size)).toMutableList()
        }
        return out.map { Piece(it.distanceM, it.gain / it.distanceM, it.kind, it.cost / it.distanceM) }
    }

    private fun nearest(segs: List<Seg>, i: Int): Int {
        if (i == 0) return 1
        if (i == segs.lastIndex) return i - 1
        val g = segs[i].gradient
        return if (abs(segs[i - 1].gradient - g) <= abs(segs[i + 1].gradient - g)) i - 1 else i + 1
    }

    private fun coalesce(segs: List<Seg>): MutableList<Seg> {
        val out = ArrayList<Seg>()
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && last.kind == s.kind) out[out.lastIndex] = piece(last.samples!! + s.samples!!, s.kind) else out += s
        }
        return out
    }

    /**
     * "Climb 4%" — the segment's name on the watch, the table and the voice;
     * "Rolling" for a flat segment priced [ROLLING_EXCESS] over its own grade.
     */
    fun label(kind: String, gradient: Double, multiplier: Double? = null): String {
        val pct = PyRound.half(abs(gradient) * 100)
        if (kind == "flat") {
            if (multiplier != null && multiplier >= RacePredictor.gradeCostMultiplier(gradient) * (1 + ROLLING_EXCESS)) {
                return "Rolling"
            }
            return LABELS.getValue(kind)
        }
        val name = LABELS.getValue(kind)
        return if (pct == 0) name else "$name $pct%"
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
