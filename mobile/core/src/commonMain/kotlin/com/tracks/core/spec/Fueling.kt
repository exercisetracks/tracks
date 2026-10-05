// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import kotlin.math.floor

/**
 * Race-day fueling calculator over the generated thresholds
 * (spec/fueling.yaml -> FuelingData.kt).
 *
 * Ported from frontend/src/utils/fuelingUtils.js, the still-shipping
 * implementation and the oracle spec/fixtures/fueling.json was baselined
 * against — see that file's header comment. This is the only Kotlin port:
 * there is no Python equivalent (no backend caller ever needed one), and this
 * is the piece that actually needs to work with no network, on a race-day
 * screen mid-run.
 */

/** One carb-per-hour breakpoint: `carbsPerHour` applies for any duration up
 * to and including `maxHours`. */
data class CarbBreakpoint(val maxHours: Int, val carbsPerHour: Int)

/** One heat band's drink concentration, sip interval, and salt index. */
data class FuelingBand(val concentrationPct: Int, val sipIntervalMin: Int, val saltIndex: Int)

/** The subset of a weather snapshot the calculator reads. */
data class WeatherSnapshot(val temperatureC: Double?, val humidityPct: Double?)

/** One lap's fueling-relevant fields: pace target and distance. */
data class FuelingLap(val targetSecPerKm: Double?, val distanceM: Double?)

/** A single scheduled sip: how much, and how many minutes into the effort. */
data class Drink(val sipMl: Int, val atMin: Int)

/** Concentration/interval/salt band plus the two booleans the caller shows a
 * banner from — mirrors the JS return shape of `computeFuelingParams`. */
data class FuelingParams(
    val concentrationPct: Int,
    val concentration: Double,
    val sipIntervalMin: Int,
    val firstSipMin: Int,
    val heatAdjusted: Boolean,
    val isHot: Boolean,
    val isVeryHot: Boolean,
    val isHumid: Boolean,
    val saltIdx: Int,
)

/**
 * `Math.round` from JavaScript: ties go UP, toward positive infinity — NOT
 * Kotlin's `round()`, which rounds ties to even. See
 * mobile/core/.../format/Format.kt's `jsRound` for the fuller rationale and
 * the project history of this exact class of divergence; this is a separate
 * copy rather than a shared import because that one is private to the format
 * package and the two evaluators have no other reason to depend on each other.
 */
private fun jsRound(x: Double): Double = floor(x + 0.5)

/** Default carbs/hour by duration breakpoint: the first breakpoint whose
 * `maxHours` the duration doesn't exceed wins; past the last one, the spec's
 * flat default applies. */
fun defaultCarbsPerHour(durationHours: Double): Int {
    for (bp in CARB_BREAKPOINTS) {
        if (durationHours <= bp.maxHours) return bp.carbsPerHour
    }
    return DEFAULT_CARBS_PER_HOUR
}

/**
 * Heat/humidity classification and the concentration/interval/salt band it
 * selects. `durationHours` is accepted for parity with the JS signature this
 * was ported from but is not read here — the original doesn't use it either;
 * see the note in spec/fueling.yaml.
 */
@Suppress("UNUSED_PARAMETER")
fun computeFuelingParams(durationHours: Double, weather: WeatherSnapshot?): FuelingParams {
    val temp = weather?.temperatureC
    val humidity = weather?.humidityPct

    val isHot = temp != null && temp >= HEAT_THRESHOLD_C
    val isVeryHot = temp != null && temp >= VERY_HOT_THRESHOLD_C
    val isHumid = humidity != null && humidity >= HUMIDITY_THRESHOLD_PCT &&
        (temp ?: HUMIDITY_MIN_TEMP_C.toDouble()) >= HUMIDITY_MIN_TEMP_C
    val heatAdjusted = isHot || isHumid

    val bandKey = if (isVeryHot) "very_hot" else if (heatAdjusted) "hot" else "normal"
    val band = BANDS.getValue(bandKey)

    return FuelingParams(
        concentrationPct = band.concentrationPct,
        concentration = band.concentrationPct / 100.0,
        sipIntervalMin = band.sipIntervalMin,
        // The JS computes firstSipMin independently but it is always equal to
        // sipIntervalMin in every branch — see spec/fueling.yaml's note.
        firstSipMin = band.sipIntervalMin,
        heatAdjusted = heatAdjusted,
        isHot = isHot,
        isVeryHot = isVeryHot,
        isHumid = isHumid,
        saltIdx = band.saltIndex,
    )
}

/** Millilitres per sip, rounded to the nearest 10 mL. */
fun computeSipMl(carbsPerHour: Double, concentration: Double, sipIntervalMin: Int): Int {
    val volumePerHourMl = carbsPerHour / concentration
    val sipsPerHour = 60.0 / sipIntervalMin
    return (jsRound((volumePerHourMl / sipsPerHour) / 10.0) * 10).toInt()
}

/**
 * One scheduled drink (or null) per lap, walking cumulative elapsed time and
 * firing a sip every [sipIntervalMin] starting at [firstSipMin]. A lap
 * missing a pace target or distance (or with non-positive distance)
 * contributes zero elapsed time and gets no drink.
 */
fun computePerLapDrinks(
    laps: List<FuelingLap>,
    sipIntervalMin: Int,
    firstSipMin: Int,
    perSipMl: Int,
): List<Drink?> {
    if (laps.isEmpty()) return emptyList()

    val drinks = arrayOfNulls<Drink>(laps.size)
    var cumulativeSec = 0.0
    var nextSipSec = firstSipMin * 60.0

    for (i in laps.indices) {
        val lap = laps[i]
        if (lap.targetSecPerKm == null || lap.distanceM == null || lap.distanceM <= 0) {
            continue
        }
        val lapTime = (lap.distanceM / 1000.0) * lap.targetSecPerKm
        val end = cumulativeSec + lapTime

        while (nextSipSec <= end) {
            drinks[i] = Drink(sipMl = perSipMl, atMin = jsRound(nextSipSec / 60.0).toInt())
            nextSipSec += sipIntervalMin * 60.0
        }

        cumulativeSec += lapTime
    }

    return drinks.toList()
}

/**
 * End-to-end: derive carbs/hour (from the plan or [defaultCarbsPerHour]),
 * derive the heat band, derive the per-sip volume, and schedule drinks across
 * [laps]. Empty when there are no laps or no predicted duration.
 */
fun getPerLapDrinks(
    laps: List<FuelingLap>,
    predictedSeconds: Double?,
    fuelingPlanCarbs: Double?,
    weather: WeatherSnapshot?,
): List<Drink?> {
    if (laps.isEmpty() || predictedSeconds == null || predictedSeconds == 0.0) return emptyList()

    val durationHours = predictedSeconds / 3600.0
    val carbsPerHour = fuelingPlanCarbs ?: defaultCarbsPerHour(durationHours).toDouble()
    val params = computeFuelingParams(durationHours, weather)
    val perSipMl = computeSipMl(carbsPerHour, params.concentration, params.sipIntervalMin)
    return computePerLapDrinks(laps, params.sipIntervalMin, params.firstSipMin, perSipMl)
}
