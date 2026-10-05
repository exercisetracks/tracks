// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.run

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One GPS fix as the recorder keeps it.
 *
 * [elapsedMs] is time since the run started, not wall-clock: a run is a
 * stopwatch and the phone's clock can step under it (network time, a timezone
 * change at a border). [timestampMs] is kept as well because the FIT file needs
 * real timestamps, but nothing derived from the run uses it.
 */
data class RunFix(
    val timestampMs: Long,
    val elapsedMs: Long,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,
    /** Metres of horizontal uncertainty, as the platform reports it. */
    val accuracyM: Double? = null,
    /** From the GNSS chip's own Doppler estimate, which is better than differencing positions. */
    val speedMps: Double? = null,
)

/**
 * A run as it is being recorded.
 *
 * Deliberately pure and in `core`: the whole of the distance and pace logic is
 * here, where it can be tested against a fixed list of fixes, rather than inside
 * an Android service where it can only be tested by going for a run.
 *
 * ## Fixes are filtered, and this is most of what makes a track usable
 *
 * A phone's GPS lies in two specific ways, and both of them inflate distance —
 * which is the number people care most about and the one hardest to notice being
 * wrong.
 *
 * The first is scatter. A fix with a 40 m accuracy circle is a fix that could be
 * anywhere in a 40 m circle, and taking two of them a second apart as a
 * displacement invents up to 80 m of running. [MAX_ACCURACY_M] drops those
 * outright.
 *
 * The second is standing still. Under a tree, at a traffic light, the reported
 * position wanders by several metres a second with excellent accuracy figures,
 * and an unfiltered track will happily add a kilometre to a run that waited at
 * two junctions. [MIN_STEP_M] is the floor a step has to clear to count as
 * movement at all.
 *
 * Both thresholds discard real information, which is the point: the discarded
 * information is wrong.
 */
class RunTrack {

    private val _fixes = mutableListOf<RunFix>()
    val fixes: List<RunFix> get() = _fixes

    /** Metres, over the fixes that survived filtering. */
    var distanceM: Double = 0.0
        private set

    /**
     * Whether movement counts toward [distanceM] and [splits] right now.
     *
     * Off during a guided run's walking warm-up and cool-down: a 5-minute walk
     * is ~500 m that is not running, and counting it made a planned 5 km read
     * 5.5 km and every split after the first one early. The fixes are still
     * kept — the map shows the whole outing, walk included — only the run's
     * totals leave the walk out.
     */
    var counting: Boolean = true

    /** Per kept fix: whether the step that arrived at it counted (see [counting]). */
    private val _counted = mutableListOf<Boolean>()

    /** Metres climbed, summed over rises that clear [MIN_CLIMB_M]. */
    var ascentM: Double = 0.0
        private set

    private var lastKept: RunFix? = null
    private var lastKeptAltitude: Double? = null

    /**
     * Offer a fix. Returns true if it was kept.
     *
     * The caller does not need the answer, but a rejected fix is worth being able
     * to count: a run where most fixes are rejected is one under a canopy or in a
     * city street, and that is worth saying on screen rather than silently
     * recording a suspiciously short run.
     */
    fun add(fix: RunFix): Boolean {
        if (fix.accuracyM != null && fix.accuracyM > MAX_ACCURACY_M) return false

        val previous = lastKept
        if (previous == null) {
            _fixes += fix
            _counted += counting
            lastKept = fix
            lastKeptAltitude = fix.altitudeM
            return true
        }

        val step = haversineMetres(previous.lat, previous.lng, fix.lat, fix.lng)
        if (step < MIN_STEP_M) return false

        if (counting) distanceM += step
        _fixes += fix
        _counted += counting

        // Ascent from the last altitude that counted, not the last fix: a
        // barometric or GPS altitude that dithers by a metre would otherwise
        // accumulate hundreds of metres of climb over an hour on flat ground.
        val altitude = fix.altitudeM
        val reference = lastKeptAltitude
        if (altitude != null) {
            if (reference == null) {
                lastKeptAltitude = altitude
            } else if (altitude - reference >= MIN_CLIMB_M) {
                ascentM += altitude - reference
                lastKeptAltitude = altitude
            } else if (reference - altitude >= MIN_CLIMB_M) {
                lastKeptAltitude = altitude
            }
        }

        lastKept = fix
        return true
    }

    /**
     * Metres per second right now, averaged over the last [PACE_WINDOW_MS].
     *
     * Not the chip's instantaneous speed, which jitters enough to make a
     * displayed pace unreadable, and not the run average, which stops responding
     * to anything after the first kilometre. A rolling window is the only one of
     * the three that answers "am I going too fast" while you can still act on it.
     */
    fun currentSpeedMps(): Double {
        if (_fixes.size < 2) return 0.0
        val newest = _fixes.last()
        val cutoff = newest.elapsedMs - PACE_WINDOW_MS
        var index = _fixes.size - 1
        while (index > 0 && _fixes[index - 1].elapsedMs >= cutoff) index--
        val oldest = _fixes[index]
        val seconds = (newest.elapsedMs - oldest.elapsedMs) / 1000.0
        if (seconds <= 0.0) return 0.0

        var metres = 0.0
        for (i in index until _fixes.size - 1) {
            metres += haversineMetres(
                _fixes[i].lat, _fixes[i].lng, _fixes[i + 1].lat, _fixes[i + 1].lng,
            )
        }
        return metres / seconds
    }

    /**
     * The whole-kilometre boundaries crossed so far, as (km, elapsedMs).
     *
     * Interpolated across the fix that crossed the line rather than reported at
     * the first fix past it: at a second between fixes and four minutes a
     * kilometre, a fix lands up to four metres past the mark, and a split read
     * off it is out by a second or so every time — always in the same direction,
     * so the errors accumulate down the list rather than cancelling.
     */
    fun splits(): List<Pair<Int, Long>> {
        val out = mutableListOf<Pair<Int, Long>>()
        var covered = 0.0
        var nextMark = SPLIT_METRES
        for (i in 0 until _fixes.size - 1) {
            val from = _fixes[i]
            val to = _fixes[i + 1]
            if (!_counted[i + 1]) continue
            val step = haversineMetres(from.lat, from.lng, to.lat, to.lng)
            if (step <= 0.0) continue
            while (covered + step >= nextMark) {
                val fraction = (nextMark - covered) / step
                val at = from.elapsedMs + ((to.elapsedMs - from.elapsedMs) * fraction).toLong()
                out += (nextMark / SPLIT_METRES).toInt() to at
                nextMark += SPLIT_METRES
            }
            covered += step
        }
        return out
    }

    /**
     * The counted distance at each kept fix, for the FIT record stream — which
     * must agree with [distanceM], or an importer reading the records would
     * put the walk back in.
     */
    fun cumulativeM(): List<Double> {
        val out = ArrayList<Double>(_fixes.size)
        var covered = 0.0
        for (i in _fixes.indices) {
            if (i > 0 && _counted[i]) {
                val a = _fixes[i - 1]
                val b = _fixes[i]
                covered += haversineMetres(a.lat, a.lng, b.lat, b.lng)
            }
            out += covered
        }
        return out
    }

    companion object {
        /**
         * Fixes worse than this are thrown away.
         *
         * Chosen against what a phone reports rather than what would be nice: a
         * clear-sky fix is 4-8 m, a street between buildings 15-25 m, and the
         * first fixes after a cold start are often over 100 m. Twenty-five keeps
         * the second case, which is a real run, and drops the third, which is
         * the phone guessing from cell towers.
         */
        const val MAX_ACCURACY_M = 25.0

        /**
         * The smallest step that counts as having moved.
         *
         * Below a running stride and above the metre-or-two of drift a stationary
         * phone reports.
         */
        const val MIN_STEP_M = 3.0

        /** Altitude has to move this far before any of it is called climbing. */
        const val MIN_CLIMB_M = 3.0

        /** Long enough to smooth GPS noise, short enough to still be "now". */
        const val PACE_WINDOW_MS = 20_000L

        const val SPLIT_METRES = 1000.0
    }
}

/** Great-circle distance in metres. */
fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * asin(min(1.0, sqrt(a)))
}
