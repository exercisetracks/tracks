// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the dashboard heatmap colours its routes by — the web's four modes.
 *
 * [Frequency] is one colour, so density comes from overlap. The other three
 * colour each stretch of a route by a value its points carry, which the web
 * does in its WebGL glow layer (`HeatmapGlowLayer.buildHeatmapVerts`) and this
 * file ports. The ramps and the percentile clipping are the web's, so the same
 * ride is the same colours on both screens.
 */
enum class HeatmapMode(val label: String) {
    Frequency("Frequency"),
    Pace("Pace"),
    HeartRate("Heart rate"),
    Gradient("Gradient"),
}

/** One track point, with the three values a mode can colour by. */
data class HeatPoint(
    val lat: Double,
    val lng: Double,
    val speed: Double? = null,
    val heartRate: Double? = null,
    val altitude: Double? = null,
) {
    /** The value [mode] needs, or null; [HeatmapMode.Frequency] needs none. */
    fun valueFor(mode: HeatmapMode): Double? = when (mode) {
        HeatmapMode.Frequency -> 0.0
        HeatmapMode.Pace -> speed
        HeatmapMode.HeartRate -> heartRate
        HeatmapMode.Gradient -> altitude
    }
}

/**
 * Tracks → a GeoJSON FeatureCollection of LineStrings, each with a `color`.
 *
 * Coloured here rather than with a MapLibre expression, because two of the
 * three value modes are not a function of one feature: pace and heart rate are
 * scaled to the 5th–95th percentile of *every* point shown, so a recovery jog
 * reads blue beside a race and red on its own week. A style expression sees one
 * feature at a time and cannot know that range.
 *
 * Runs of segments that land in the same colour step are merged into one line.
 * One feature per segment would be correct and about a hundred thousand
 * features for a year of history, which a phone redraws on every pan; the
 * steps are fine enough ([STEPS]) that the merging is invisible.
 */
object HeatmapRoutes {

    /** The phone's route orange, kept from before modes existed. */
    const val FREQUENCY_COLOR = "#f97316"

    private val SPEED_STOPS = listOf(
        0.0 to intArrayOf(41, 98, 255), 0.4 to intArrayOf(16, 185, 129),
        0.7 to intArrayOf(251, 191, 36), 1.0 to intArrayOf(239, 68, 68),
    )
    private val GRAD_STOPS = listOf(
        0.0 to intArrayOf(16, 185, 129), 0.5 to intArrayOf(255, 255, 255), 1.0 to intArrayOf(139, 92, 246),
    )

    /** Colour resolution of the value modes; see the class note. */
    private const val STEPS = 32

    /** Steeper than this is drawn as this — the web's ±20 %. */
    private const val MAX_GRADE = 0.2

    /** Shorter than this, altitude noise swamps the slope, so it reads flat. */
    private const val MIN_GRADE_RUN_M = 3.0

    fun geoJson(tracks: List<List<HeatPoint>>, mode: HeatmapMode): String {
        val features = ArrayList<JsonObject>()
        val stepOf = stepper(tracks, mode)

        for (track in tracks) {
            val pts = track.filter { it.valueFor(mode) != null }
            if (pts.size < 2) continue
            if (stepOf == null) {
                features += line(pts, FREQUENCY_COLOR)
                continue
            }
            // Walk the segments, cutting a new line whenever the step changes.
            // Consecutive lines share their joining point so the route stays
            // continuous on screen.
            var start = 0
            var step = stepOf(pts[0], pts[1])
            for (i in 1 until pts.size - 1) {
                val next = stepOf(pts[i], pts[i + 1])
                if (next != step) {
                    features += line(pts.subList(start, i + 1), colorOf(mode, step))
                    start = i
                    step = next
                }
            }
            features += line(pts.subList(start, pts.size), colorOf(mode, step))
        }
        return JsonObject(mapOf(
            "type" to JsonPrimitive("FeatureCollection"),
            "features" to JsonArray(features),
        )).toString()
    }

    /** Segment → colour step, or null for [HeatmapMode.Frequency]. */
    private fun stepper(tracks: List<List<HeatPoint>>, mode: HeatmapMode): ((HeatPoint, HeatPoint) -> Int)? =
        when (mode) {
            HeatmapMode.Frequency -> null
            HeatmapMode.Gradient -> { a, b ->
                val dist = haversineM(a, b)
                val grade = if (dist < MIN_GRADE_RUN_M) 0.0
                else ((b.altitude!! - a.altitude!!) / dist).coerceIn(-MAX_GRADE, MAX_GRADE)
                stepFor((grade + MAX_GRADE) / (2 * MAX_GRADE))
            }
            HeatmapMode.Pace, HeatmapMode.HeartRate -> {
                val values = tracks.flatMap { t -> t.mapNotNull { it.valueFor(mode) } }.sorted()
                if (values.isEmpty()) { _, _ -> 0 } else {
                    val lo = percentile(values, 5.0)
                    val hi = percentile(values, 95.0)
                    val range = (hi - lo).takeIf { it != 0.0 } ?: 1.0
                    val stepper: (HeatPoint, HeatPoint) -> Int = { a, b ->
                        val mid = (a.valueFor(mode)!! + b.valueFor(mode)!!) / 2
                        stepFor(((mid - lo) / range).coerceIn(0.0, 1.0))
                    }
                    stepper
                }
            }
        }

    private fun stepFor(t: Double): Int = (t * (STEPS - 1)).roundToInt()

    internal fun colorOf(mode: HeatmapMode, step: Int): String {
        val t = step.toDouble() / (STEPS - 1)
        val stops = if (mode == HeatmapMode.Gradient) GRAD_STOPS else SPEED_STOPS
        return hex(rgbFromStops(stops, t))
    }

    /** The web's `pct`: linear interpolation between the closest ranks. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val idx = (p / 100) * (sorted.size - 1)
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
    }

    private fun rgbFromStops(stops: List<Pair<Double, IntArray>>, t: Double): IntArray {
        for (i in 1 until stops.size) {
            val (t0, c0) = stops[i - 1]
            val (t1, c1) = stops[i]
            if (t <= t1) {
                val f = (t - t0) / (t1 - t0)
                return IntArray(3) { (c0[it] + (c1[it] - c0[it]) * f).roundToInt() }
            }
        }
        return stops.last().second
    }

    private fun hex(rgb: IntArray): String =
        "#" + rgb.joinToString("") { it.coerceIn(0, 255).toString(16).padStart(2, '0') }

    private fun line(pts: List<HeatPoint>, color: String) = JsonObject(mapOf(
        "type" to JsonPrimitive("Feature"),
        "properties" to JsonObject(mapOf("color" to JsonPrimitive(color))),
        "geometry" to JsonObject(mapOf(
            "type" to JsonPrimitive("LineString"),
            "coordinates" to JsonArray(pts.map { JsonArray(listOf(JsonPrimitive(it.lng), JsonPrimitive(it.lat))) }),
        )),
    ))

    private fun haversineM(a: HeatPoint, b: HeatPoint): Double {
        val r = 6_371_000.0
        val rad = PI / 180
        val dLat = (b.lat - a.lat) * rad
        val dLng = (b.lng - a.lng) * rad
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * rad) * cos(b.lat * rad) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h))
    }
}

/**
 * A heatmap track packed for `local_heat_track`: five little-endian float32s
 * per point — lat, lng, speed, heart rate, altitude — with NaN for a value the
 * point does not carry.
 *
 * Float32 because it is plenty: at a latitude of 90 one step is under half a
 * metre, finer than the server's own four-decimal rounding of the same tracks,
 * and it halves what every dashboard filter change reads off disk. Binary
 * rather than JSON because the whole point of storing these is never parsing
 * text for them again.
 */
object HeatTrackCodec {
    private const val FLOATS = 5
    private const val BYTES = FLOATS * 4

    fun encode(track: List<HeatPoint>): ByteArray {
        val out = ByteArray(track.size * BYTES)
        var i = 0
        fun put(v: Double?) {
            val bits = (v?.toFloat() ?: Float.NaN).toRawBits()
            out[i++] = bits.toByte(); out[i++] = (bits shr 8).toByte()
            out[i++] = (bits shr 16).toByte(); out[i++] = (bits shr 24).toByte()
        }
        for (p in track) { put(p.lat); put(p.lng); put(p.speed); put(p.heartRate); put(p.altitude) }
        return out
    }

    fun decode(bytes: ByteArray): List<HeatPoint> {
        var i = 0
        fun next(): Double? {
            val bits = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8) or
                ((bytes[i + 2].toInt() and 0xff) shl 16) or ((bytes[i + 3].toInt() and 0xff) shl 24)
            i += 4
            return Float.fromBits(bits).takeUnless { it.isNaN() }?.toDouble()
        }
        return List(bytes.size / BYTES) { HeatPoint(next()!!, next()!!, next(), next(), next()) }
    }
}
