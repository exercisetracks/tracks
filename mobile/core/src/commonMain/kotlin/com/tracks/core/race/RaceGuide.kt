// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.race

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Racing a plan with the phone: one leg per terrain split, and where on the
 * course the runner is.
 *
 * ## What it says, and when
 *
 * A leg is announced as it begins, with a buzz first (RunCues.announceStep) —
 * the phone is in a pocket, and the buzz is what gets noticed. The words are
 * about the change, not the number alone: "Climb ahead, 7 percent for 300
 * metres. Ease to 5 52." going up; "Top of the climb. Back to 4 50." at the
 * crest, because that is the moment runners either sit on the slower pace or
 * surge, and the plan wants neither.
 *
 * ## Where the legs change
 *
 * By position on the course, not by distance run. A watch or phone measuring
 * distance drifts by a few percent over a race — tangents, GPS — so a hill
 * announced by distance arrives early or late, which is exactly wrong for a
 * 300 m wall. [CourseProgress] snaps each fix onto the course line and the
 * legs change where the terrain does. With no course, or a fix far off it
 * (a detour, bad signal), the distance run is what is left, and is used.
 */
object RaceGuide {

    data class Leg(
        val title: String,
        /** "5:52/km" — the target as the screen shows it. */
        val detail: String,
        val distanceM: Double,
        /** Where on the course this leg ends, in the plan's metres. */
        val courseEndM: Double,
        val kind: String?,
        /** What is said as it begins. */
        val spoken: String,
        val targetSecPerKm: Double,
    )

    fun legs(laps: List<RaceLap>, imperial: Boolean): List<Leg> {
        var end = 0.0
        return laps.mapIndexed { i, lap ->
            end += lap.distanceM.toDouble()
            val prev = laps.getOrNull(i - 1)
            Leg(
                title = lap.label ?: "Km ${lap.lap}",
                detail = paceText(lap.targetSecPerKm, imperial),
                distanceM = lap.distanceM.toDouble(),
                courseEndM = end,
                kind = lap.kind,
                spoken = cue(prev, lap, i == 0, imperial),
                targetSecPerKm = lap.targetSecPerKm,
            )
        }
    }

    private fun climbing(kind: String?) = kind == "up" || kind == "steep_up"
    private fun descending(kind: String?) = kind == "down" || kind == "steep_down"

    internal fun cue(prev: RaceLap?, lap: RaceLap, first: Boolean, imperial: Boolean): String {
        val pace = spokenPace(lap.targetSecPerKm, imperial)
        val pct = (abs(lap.gradient) * 100).roundToInt()
        val len = spokenDistance(lap.distanceM.toDouble(), imperial)
        val body = when {
            climbing(lap.kind) ->
                "${if (lap.kind == "steep_up") "Steep climb" else "Climb"} ahead, $pct percent for $len. Ease to $pace."
            descending(lap.kind) ->
                "Downhill, $pct percent for $len. Let it come, $pace."
            climbing(prev?.kind) -> "Top of the climb. Back to $pace."
            descending(prev?.kind) -> "Bottom of the descent. Hold $pace."
            else -> "$pace."
        }
        return if (first) "Race start. $body" else body
    }

    internal fun paceText(secPerKm: Double, imperial: Boolean): String {
        val s = (secPerKm * if (imperial) 1.60934 else 1.0).roundToInt()
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}/${if (imperial) "mi" else "km"}"
    }

    /** "5 52" — how a pace is said aloud; "per mile" only when it is not kilometres. */
    internal fun spokenPace(secPerKm: Double, imperial: Boolean): String {
        val s = (secPerKm * if (imperial) 1.60934 else 1.0).roundToInt()
        val ss = s % 60
        val base = if (ss == 0) "${s / 60} flat" else "${s / 60} ${ss.toString().padStart(2, '0')}"
        return if (imperial) "$base per mile" else base
    }

    internal fun spokenDistance(m: Double, imperial: Boolean): String = if (imperial) {
        val mi = m / 1609.344
        if (mi >= 0.5) "${(mi * 10).roundToInt() / 10.0} miles" else "${(m * 1.09361 / 10).roundToInt() * 10} yards"
    } else {
        if (m >= 1000) "${(m / 100).roundToInt() / 10.0} kilometres" else "${(m / 10).roundToInt() * 10} metres"
    }

    /**
     * Distance along a course for each GPS fix.
     *
     * The course is the plan's sampled path; its length is scaled to
     * [planDistanceM] (the plan may race the event's distance over a GPX that
     * measured a little different). Each fix is projected onto the nearest
     * stretch of the line, searched a little behind and well ahead of the last
     * one, so a course that loops back past itself is not answered with the
     * wrong lap; progress never goes backwards. A fix more than [offCourseM]
     * from the line answers null — the caller falls back to distance run.
     */
    class CourseProgress(path: List<Pair<Double, Double>>, planDistanceM: Double, private val offCourseM: Double = 120.0) {
        private val pts = path
        private val cum = DoubleArray(path.size)
        private val scale: Double
        private var at = 0
        private var best = 0.0

        init {
            for (i in 1 until pts.size) {
                cum[i] = cum[i - 1] + RacePredictor.haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
            }
            val len = if (pts.isEmpty()) 0.0 else cum[pts.size - 1]
            scale = if (len > 0) planDistanceM / len else 1.0
        }

        val usable: Boolean get() = pts.size >= 2 && cum[pts.size - 1] > 0

        fun update(lat: Double, lon: Double): Double? {
            if (!usable) return null
            val from = (at - 3).coerceAtLeast(0)
            val to = (at + 40).coerceAtMost(pts.size - 2)
            var bestD = Double.MAX_VALUE
            var bestAlong = 0.0
            var bestI = at
            for (i in from..to) {
                val (d, t) = project(lat, lon, pts[i], pts[i + 1])
                if (d < bestD) {
                    bestD = d
                    bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
                    bestI = i
                }
            }
            if (bestD > offCourseM) return null
            at = bestI
            best = maxOf(best, bestAlong * scale)
            return best
        }

        /** Distance to the segment a→b and how far along it (0..1), on a local flat projection. */
        private fun project(lat: Double, lon: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
            val k = cos(lat * kotlin.math.PI / 180.0) * 111_320.0
            val ax = (a.second - lon) * k
            val ay = (a.first - lat) * 110_540.0
            val bx = (b.second - lon) * k
            val by = (b.first - lat) * 110_540.0
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            return kotlin.math.sqrt(px * px + py * py) to t
        }
    }
}
