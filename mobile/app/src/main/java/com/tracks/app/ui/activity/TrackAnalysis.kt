// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.core.api.ActivityDetail
import com.tracks.core.api.TrackPoint
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the recorded samples say that the summary row does not.
 *
 * The server sends a summary and a track, and the summary is the watch's own
 * arithmetic — twenty-odd fields, all of them a single number for the whole
 * activity. Everything about the *shape* of an effort lives in the track and
 * nowhere else: which kilometre was the fast one, where the climbing actually
 * was, how long was really spent at threshold rather than what fraction of
 * samples landed there.
 *
 * Kept out of the composables and out of `core`. Out of the composables because
 * it is arithmetic over a few thousand points and wants testing without a
 * screen; out of `core` because it needs real timestamps, and `core.format`
 * documents a deliberate decision to own no date handling — see its header. On
 * Android `java.time` is available at minSdk 26 and through desugaring below
 * that, so the parsing lives here rather than pulling a datetime library into
 * a module shared with iOS for one field.
 */

// ── Sample timing ────────────────────────────────────────────────────────────

/**
 * How many seconds each sample stands for.
 *
 * The obvious alternative — divide the activity's duration by the sample count
 * — is what this screen used to assume, on the stated grounds that the track is
 * "evenly resampled". It is not. The server downsamples with LTTB
 * (`parsers/smoother.py`), which keeps one *visually significant* point per
 * equal-sized bucket, so spacing is even in bucket count and jittery in time.
 * Below the resolution cap there is no resampling at all and the spacing is
 * whatever the watch recorded — 1 Hz, or smart-recording's variable rate.
 *
 * Neither of those is a problem once the timestamps are read directly, which is
 * why they are.
 *
 * Long gaps are capped rather than trusted. A stop for lunch appears as one
 * sample followed by another forty minutes later, and charging the whole gap to
 * whichever heart-rate zone the athlete happened to be in when they sat down
 * would swamp every real number on the screen. Past [MAX_SAMPLE_GAP_SECONDS] the
 * gap is treated as a pause and contributes the nominal interval instead.
 */
internal fun sampleSeconds(track: List<TrackPoint>): List<Double>? {
    if (track.size < 2) return null
    val instants = track.map { parseInstant(it.recordedAt) ?: return null }

    val gaps = (0 until instants.size - 1).map { i ->
        (instants[i + 1].toEpochMilli() - instants[i].toEpochMilli()) / 1000.0
    }
    // Non-monotonic timestamps mean something is wrong enough that no
    // per-sample number derived from them should be shown.
    if (gaps.any { it < 0 }) return null

    val typical = gaps.filter { it > 0 }.sorted().let { sorted ->
        if (sorted.isEmpty()) return null else sorted[sorted.size / 2]
    }
    val capped = gaps.map { if (it > MAX_SAMPLE_GAP_SECONDS) typical else it }

    // Each sample covers the interval that follows it; the last one is given
    // the interval that preceded it so it is not silently worth nothing.
    return capped + capped.last()
}

private const val MAX_SAMPLE_GAP_SECONDS = 60.0

private fun parseInstant(value: String?): Instant? {
    val text = value ?: return null
    return try {
        // The server sends naive UTC — no offset — which `Instant.parse` will
        // not take, so the zone is appended when it is missing rather than
        // failing every activity.
        Instant.parse(if (text.endsWith("Z") || text.contains('+')) text else "${text}Z")
    } catch (e: DateTimeParseException) {
        null
    }
}

// ── Distance ─────────────────────────────────────────────────────────────────

private const val EARTH_RADIUS_METRES = 6_371_008.8

/** Great-circle distance. Haversine, which is stable for the short hops here. */
internal fun haversineMetres(
    lat1: Double, lng1: Double,
    lat2: Double, lng2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return 2 * EARTH_RADIUS_METRES * asin(min(1.0, sqrt(a)))
}

// ── Splits ───────────────────────────────────────────────────────────────────

/**
 * One unit of distance, and how it went.
 *
 * [metres] is what the split actually covered, which is the full unit for every
 * split but the last — the tail of a 7.4 km run is a real 400 m split and
 * showing it as a kilometre would misstate its pace by a factor of two.
 */
data class Split(
    val number: Int,
    val metres: Double,
    val seconds: Double,
    val avgHeartRate: Int?,
    val ascent: Double,
    val descent: Double,
) {
    /** Metres per second, the unit every formatter in `core.format` takes. */
    val speed: Double get() = if (seconds > 0) metres / seconds else 0.0
}

/**
 * The track cut into equal distances.
 *
 * Every watch shows these and none of the server's endpoints has them, because
 * they are not a stored fact — laps are what the athlete or the auto-lap setting
 * chose, and they answer a different question. A ride with auto-lap off has one
 * lap and twenty splits.
 *
 * ## Why the distances are scaled
 *
 * Summing haversine hops along the track undershoots: the polyline that arrives
 * has been LTTB-downsampled, and every curve is cut by its chords. Left alone, a
 * 10.0 km run measures perhaps 9.85 km here and produces nine splits and a
 * suspiciously long tail — splits that visibly disagree with the distance
 * printed above them, which reads as a broken screen rather than as sampling.
 *
 * So the summed length is scaled to the activity's own recorded distance, which
 * is the watch's odometer and the authority. The scale corrects the sampling
 * loss and leaves the *proportions* — which is all a split boundary is — exactly
 * as the track recorded them.
 */
fun splits(
    track: List<TrackPoint>,
    unitMetres: Double,
    recordedDistance: Double? = null,
): List<Split> {
    if (unitMetres <= 0) return emptyList()
    val weights = sampleSeconds(track) ?: return emptyList()

    // Hop lengths, indexed so hop[i] is the leg arriving at sample i+1. A
    // sample with no fix breaks the chain rather than teleporting across it.
    val hops = DoubleArray(track.size - 1)
    var measured = 0.0
    for (i in 0 until track.size - 1) {
        val a = track[i]
        val b = track[i + 1]
        val lat1 = a.lat; val lng1 = a.lng; val lat2 = b.lat; val lng2 = b.lng
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) continue
        hops[i] = haversineMetres(lat1, lng1, lat2, lng2)
        measured += hops[i]
    }
    if (measured <= 0) return emptyList()

    val scale = recordedDistance?.takeIf { it > 0 }?.div(measured) ?: 1.0
    val total = measured * scale
    if (total < unitMetres) return emptyList()

    val out = mutableListOf<Split>()
    var carried = 0.0
    var seconds = 0.0
    var ascent = 0.0
    var descent = 0.0
    var hrSum = 0.0
    var hrWeight = 0.0

    fun flush() {
        out += Split(
            number = out.size + 1,
            metres = carried,
            seconds = seconds,
            avgHeartRate = if (hrWeight > 0) (hrSum / hrWeight).roundToInt() else null,
            ascent = ascent,
            descent = descent,
        )
        carried = 0.0; seconds = 0.0; ascent = 0.0; descent = 0.0
        hrSum = 0.0; hrWeight = 0.0
    }

    for (i in 0 until track.size - 1) {
        val hopSeconds = weights[i]
        val here = track[i].altitude
        val next = track[i + 1].altitude
        // A metre of barometric noise per sample adds up to hundreds of metres
        // over a long ride, so only changes past the sensor's resolution count.
        val climb = if (here != null && next != null) next - here else 0.0
        val bpm = track[i].heartRate

        /** Fold [fraction] of this hop's time, climb and heart rate into the
         *  split being built. */
        fun accumulate(fraction: Double) {
            val share = hopSeconds * fraction
            seconds += share
            if (climb > ALTITUDE_NOISE_METRES) ascent += climb * fraction
            if (climb < -ALTITUDE_NOISE_METRES) descent += -climb * fraction
            if (bpm != null) {
                hrSum += bpm * share
                hrWeight += share
            }
        }

        // Standing still still takes time, and a hop of no length would
        // otherwise never enter the loop below and lose it.
        val hop = hops[i] * scale
        if (hop <= 0.0) {
            accumulate(1.0)
            continue
        }

        // A hop that crosses a boundary is divided across it rather than
        // landing wholly on one side. With LTTB thinning a long ride to ~30 m
        // between samples, rounding each boundary to the nearest sample would
        // put a few percent of error on every split's pace — which is the one
        // number the whole card exists to compare.
        var remaining = hop
        while (remaining > EPSILON_METRES) {
            val take = min(remaining, unitMetres - carried)
            carried += take
            accumulate(take / hop)
            remaining -= take
            if (carried >= unitMetres - EPSILON_METRES) flush()
        }
    }

    // The tail, when there is enough of it to mean anything. A 12 m remainder is
    // GPS drift at the finish line, not a split.
    if (carried > MIN_TAIL_METRES) flush()
    return out
}

private const val ALTITUDE_NOISE_METRES = 0.5
private const val MIN_TAIL_METRES = 50.0

/** Below this a remaining distance is floating-point residue, not distance. */
private const val EPSILON_METRES = 1e-6

// ── Elevation ────────────────────────────────────────────────────────────────

/** Where the climbing was, as four numbers the summary row does not carry. */
data class ElevationProfile(
    val gain: Double,
    val loss: Double,
    val lowest: Double,
    val highest: Double,
)

/**
 * Gain and loss from the recorded altitudes.
 *
 * The summary's `total_ascent` is the watch's, computed from the barometer at
 * full rate, and is the better number — this exists for the two it does not
 * carry at all, the high and low points of the day, and as the answer for
 * activities whose summary has no ascent.
 */
fun elevationProfile(track: List<TrackPoint>): ElevationProfile? {
    val altitudes = track.mapNotNull { it.altitude }
    if (altitudes.size < 2) return null

    var gain = 0.0
    var loss = 0.0
    for (i in 1 until altitudes.size) {
        val step = altitudes[i] - altitudes[i - 1]
        if (step > ALTITUDE_NOISE_METRES) gain += step
        if (step < -ALTITUDE_NOISE_METRES) loss += -step
    }
    return ElevationProfile(gain, loss, altitudes.min(), altitudes.max())
}

// ── Heart-rate distribution ──────────────────────────────────────────────────

/** One bar of the histogram: the bpm the bin starts at, and its weight. */
data class HeartRateBin(val bpm: Int, val seconds: Double)

/**
 * Time at each heart rate, in fixed-width bins.
 *
 * Time rather than sample count — the same distinction the zone card makes, and
 * for the same reason: a count is only proportional to time while the sampling
 * is uniform, which [sampleSeconds] exists precisely because it is not.
 *
 * The bins span the range actually recorded rather than a fixed 40–200: an easy
 * hour lived between 120 and 145 bpm, and drawing it across a full physiological
 * range would put the entire session in two bars.
 */
fun heartRateBins(track: List<TrackPoint>, binWidth: Int = 5): List<HeartRateBin> {
    if (binWidth <= 0) return emptyList()
    val weights = sampleSeconds(track) ?: return emptyList()
    val readings = track.indices.mapNotNull { i ->
        track[i].heartRate?.takeIf { it > 0 }?.let { it to weights[i] }
    }
    if (readings.size < MIN_SAMPLES) return emptyList()

    val low = readings.minOf { it.first } / binWidth * binWidth
    val high = readings.maxOf { it.first } / binWidth * binWidth
    val bins = DoubleArray((high - low) / binWidth + 1)
    readings.forEach { (bpm, seconds) -> bins[(bpm - low) / binWidth] += seconds }

    return bins.mapIndexed { i, seconds -> HeartRateBin(low + i * binWidth, seconds) }
}

/**
 * Seconds spent in each zone, aligned to [zoneFloors].
 *
 * Returns null when the timestamps cannot carry it, which lets the caller fall
 * back to counting samples rather than showing nothing — a percentage from
 * counts is still roughly right, and it is what this screen showed before.
 */
fun zoneSeconds(track: List<TrackPoint>, zoneFloors: List<Int>): List<Double>? {
    if (zoneFloors.isEmpty()) return null
    val weights = sampleSeconds(track) ?: return null

    val out = DoubleArray(zoneFloors.size)
    var counted = false
    track.forEachIndexed { i, point ->
        val bpm = point.heartRate ?: return@forEachIndexed
        val zone = zoneFloors.indexOfLast { bpm >= it }
        if (zone >= 0) {
            out[zone] += weights[i]
            counted = true
        }
    }
    return if (counted) out.toList() else null
}

// ── Moving time ──────────────────────────────────────────────────────────────

/**
 * Time actually moving, from the recorded speeds.
 *
 * Elapsed time is the only duration the summary carries, and on anything with a
 * café stop in it the two are different enough to change how the day reads.
 * Null when the track has no speed channel, rather than a number equal to
 * elapsed — "we could not tell" and "you never stopped" should not look alike.
 */
fun movingSeconds(track: List<TrackPoint>): Double? {
    val weights = sampleSeconds(track) ?: return null
    if (track.none { it.speed != null }) return null
    return track.indices
        .filter { (track[it].speed ?: 0.0) > STOPPED_SPEED_MPS }
        .sumOf { weights[it] }
}

/** Slower than a slow walk is standing still with GPS noise on top. */
private const val STOPPED_SPEED_MPS = 0.5

private const val MIN_SAMPLES = 5

// ── Summary helpers ──────────────────────────────────────────────────────────

/**
 * A stream's shape in three numbers.
 *
 * Min as well as average and max, because for half these channels the low is the
 * interesting end — the lowest heart rate in a session is the recovery, and the
 * lowest altitude is the valley floor.
 */
data class StreamSummary(val min: Double, val average: Double, val max: Double)

fun summarise(values: List<Double>): StreamSummary? {
    if (values.isEmpty()) return null
    return StreamSummary(values.min(), values.average(), values.max())
}

/**
 * Whether two numbers the user will read side by side are worth showing twice.
 *
 * Moving time next to an identical elapsed time is a row that costs space and
 * says nothing.
 */
internal fun differsMeaningfully(a: Double, b: Double, tolerance: Double): Boolean =
    abs(a - b) > tolerance

/** The summary's own distance, or what the track measured, or nothing. */
fun distanceFor(detail: ActivityDetail, track: List<TrackPoint>): Double? =
    detail.distanceMeters?.takeIf { it > 0 }
        ?: trackDistance(track).takeIf { it > 0 }

private fun trackDistance(track: List<TrackPoint>): Double {
    var total = 0.0
    for (i in 0 until track.size - 1) {
        val lat1 = track[i].lat; val lng1 = track[i].lng
        val lat2 = track[i + 1].lat; val lng2 = track[i + 1].lng
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) continue
        total += haversineMetres(lat1, lng1, lat2, lng2)
    }
    return total
}
