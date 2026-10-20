// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.activity.haversineMetres
import com.tracks.core.api.TrackPoint

/**
 * What an activity's route can be coloured by, in the order the pills show.
 *
 * A port of the web's `COLOR_MODES` (`frontend/src/components/ActivityRoute.jsx`),
 * and deliberately the same numbers: the two apps colour the same ride the
 * same way, so a stretch that reads as hard on the desktop reads as hard on
 * the phone. Change one and change the other.
 */
enum class RouteColour(val label: String) {
    HeartRate("HR"),
    Speed("Speed"),
    Cadence("Cadence"),
    Gradient("Gradient"),

    /** Garmin's MTB terrain difficulty. Only mountain-bike recordings carry it. */
    Grit("Grit"),

    /** Garmin's MTB descent smoothness — high is flowing, so its ramp runs the other way. */
    Flow("Flow"),
    ;

    /** The ramp's ends (and middle, for gradient), as the legend under the map names them. */
    val legend: List<String>
        get() = when (this) {
            Gradient -> listOf("Descent", "Flat", "Climb")
            Grit -> listOf("Easy", "Hard")
            Flow -> listOf("Choppy", "Flowing")
            else -> listOf("Low", "High")
        }

    /** The ramp, low to high, as ARGB — the legend draws it, the route samples it. */
    val ramp: List<Int> get() = stops.map { it.second }

    internal val stops: List<Pair<Double, Int>>
        get() = when (this) {
            Gradient -> GRADIENT_STOPS
            Grit -> GRIT_STOPS
            Flow -> FLOW_STOPS
            else -> SPEED_STOPS
        }

    internal fun valueOf(p: TrackPoint): Double? = when (this) {
        HeartRate -> p.heartRate?.toDouble()
        Speed -> p.speed
        Cadence -> p.cadence?.toDouble()
        Grit -> p.grit
        Flow -> p.flow
        Gradient -> null
    }
}

/** One short stretch of the route and the colour it is drawn in. */
data class ColouredSegment(
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val argb: Int,
)

/**
 * The modes this track can actually be coloured by.
 *
 * A pill for a channel the recording does not have would colour the whole
 * route one flat shade, which reads as "evenly paced" rather than "not
 * measured". Gradient needs only altitude.
 */
fun availableRouteColours(track: List<TrackPoint>): List<RouteColour> {
    val located = track.filter { it.lat != null && it.lng != null }
    return RouteColour.entries.filter { mode ->
        if (mode == RouteColour.Gradient) located.any { it.altitude != null }
        else located.any { mode.valueOf(it) != null }
    }
}

/**
 * The route cut into two-point segments, each coloured by [mode].
 *
 * Values are scaled between their 5th and 95th percentiles rather than their
 * extremes, as on the web: one GPS spike to 90 km/h would otherwise push every
 * real speed into the bottom of the ramp and turn the route one colour.
 * Gradient is the exception — it is a physical slope, clamped to ±20 %, so a
 * flat ride stays the flat colour instead of having its few metres of relief
 * stretched across the whole ramp.
 *
 * Empty when the track has no value for [mode]; the caller then draws the
 * plain line.
 */
fun colourRoute(track: List<TrackPoint>, mode: RouteColour): List<ColouredSegment> {
    val pts = track.filter { it.lat != null && it.lng != null }
    if (pts.size < 2) return emptyList()
    val out = ArrayList<ColouredSegment>(pts.size - 1)

    fun seg(a: TrackPoint, b: TrackPoint, t: Double) =
        ColouredSegment(a.lat!!, a.lng!!, b.lat!!, b.lng!!, colourAt(mode.stops, t))

    if (mode == RouteColour.Gradient) {
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val altA = a.altitude ?: continue
            val altB = b.altitude ?: continue
            val dist = haversineMetres(a.lat!!, a.lng!!, b.lat!!, b.lng!!)
            // Under 3 m apart the altitude difference is barometer noise, and
            // dividing by it gives cliffs on a car park.
            val grade = if (dist < 3.0) 0.0 else ((altB - altA) / dist).coerceIn(-MAX_GRADE, MAX_GRADE)
            out += seg(a, b, (grade + MAX_GRADE) / (2 * MAX_GRADE))
        }
        return out
    }

    val values = pts.mapNotNull { mode.valueOf(it) }
    if (values.isEmpty()) return emptyList()
    val sorted = values.sorted()
    val lo = percentile(sorted, 5.0)
    val hi = percentile(sorted, 95.0)
    val range = (hi - lo).takeIf { it != 0.0 } ?: 1.0
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        // A missing sample counts as zero, as on the web, so a dropout shows
        // as a low stretch rather than a gap in the line.
        val v = ((mode.valueOf(a) ?: 0.0) + (mode.valueOf(b) ?: 0.0)) / 2
        out += seg(a, b, ((v - lo) / range).coerceIn(0.0, 1.0))
    }
    return out
}

private const val MAX_GRADE = 0.2

private fun percentile(sorted: List<Double>, p: Double): Double {
    val idx = (p / 100) * (sorted.size - 1)
    val lo = kotlin.math.floor(idx).toInt()
    val hi = kotlin.math.ceil(idx).toInt()
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
}

/** The colour at [t] (0–1) along a ramp of stops, interpolated in RGB. */
internal fun colourAt(stops: List<Pair<Double, Int>>, t: Double): Int {
    for (i in 1 until stops.size) {
        val (t0, c0) = stops[i - 1]
        val (t1, c1) = stops[i]
        if (t <= t1) {
            val f = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
            fun ch(shift: Int): Int {
                val a = (c0 shr shift) and 0xFF
                val b = (c1 shr shift) and 0xFF
                return Math.round(a + (b - a) * f).toInt() and 0xFF
            }
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
    return stops.last().second
}

private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

private val SPEED_STOPS = listOf(
    0.0 to rgb(41, 98, 255), 0.4 to rgb(16, 185, 129), 0.7 to rgb(251, 191, 36), 1.0 to rgb(239, 68, 68),
)
private val GRADIENT_STOPS = listOf(
    0.0 to rgb(16, 185, 129), 0.5 to rgb(255, 255, 255), 1.0 to rgb(139, 92, 246),
)
private val GRIT_STOPS = listOf(
    0.0 to rgb(34, 197, 94), 0.4 to rgb(251, 191, 36), 0.7 to rgb(249, 115, 22), 1.0 to rgb(220, 38, 38),
)
private val FLOW_STOPS = listOf(
    0.0 to rgb(239, 68, 68), 0.4 to rgb(249, 115, 22), 0.7 to rgb(234, 179, 8), 1.0 to rgb(34, 197, 94),
)
