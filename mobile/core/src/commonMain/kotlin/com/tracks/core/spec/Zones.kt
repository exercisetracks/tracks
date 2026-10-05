// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import kotlin.math.round

/**
 * Training zone arithmetic over the generated tables (spec/zones.yaml →
 * ZonesData.kt).
 *
 * Mirrors backend/app/calculators/zones.py. The percentages are shared; only
 * the arithmetic is written per language, so a phone computing zones offline
 * gets the same boundaries the server would have returned.
 */

/**
 * One zone. [minPct] is inclusive and [maxPct] exclusive, both as a fraction of
 * the model's basis (LTHR, max HR, or FTP). A null [maxPct] means unbounded —
 * only ever the last zone. [color] is set on display models only.
 */
data class Zone(
    val number: Int,
    val name: String,
    val description: String,
    val minPct: Double,
    val maxPct: Double?,
    val color: String? = null,
)

/**
 * A named set of zones. [basis] is what the percentages are of — "lthr",
 * "max_hr", or "ftp" — and is why two HR models can both be correct and still
 * disagree. [source] is the attribution for the numbers.
 */
data class ZoneModel(
    val basis: String,
    val source: String,
    val zones: List<Zone>,
)

/**
 * A TSB form band, in absolute TSB points rather than percentages. [min] is
 * exclusive and [max] inclusive — the opposite of [Zone] — because the
 * classification reads "TSB > 25 is Transition". Nulls are the unbounded ends.
 */
data class TsbBand(
    val key: String,
    val label: String,
    val min: Int?,
    val max: Int?,
    val color: String,
    val bg: String,
    val desc: String,
)

/** A computed zone boundary in real units (bpm or watts). */
data class ZoneRange(
    val number: Int,
    val name: String,
    val description: String,
    val min: Int,
    /** Null on the open-ended top zone. */
    val max: Int?,
    val color: String? = null,
)

val hrModels: Map<String, ZoneModel> get() = HR_MODELS
val powerModels: Map<String, ZoneModel> get() = POWER_MODELS
val tsbBands: List<TsbBand> get() = TSB_BANDS

/**
 * Round half to even, matching Python's `round()`.
 *
 * This is NOT `roundToInt()`, which rounds halves away from zero. The
 * difference is not academic: 170 × 0.85 is exactly 144.5, and zone boundaries
 * land on exact halves constantly because the percentages are two-decimal
 * values and LTHR/FTP are round numbers. `roundToInt()` gave zone 1 a ceiling
 * of 144 bpm where the server said 143, and Coggan zone 4 a ceiling of 262 W
 * where the server said 261 — a phone and a server disagreeing by one unit on
 * every tie, silently. Caught by the shared fixture check.
 *
 * The server is authoritative, so Kotlin matches Python rather than the other
 * way round.
 */
private fun Double.roundHalfEven(): Int = round(this).toInt()

/**
 * Scale a model's percentages into real units.
 *
 * The `- 1` on each upper bound matches the Python implementation: the spec's
 * bounds are half-open (a zone runs up to but excluding its max), and these
 * ranges are inclusive on both ends so they can be shown to a user as
 * "143–152 bpm" without an off-by-one at the seam.
 */
private fun ZoneModel.scaledTo(basisValue: Int): List<ZoneRange> = zones.map { z ->
    ZoneRange(
        number = z.number,
        name = z.name,
        description = z.description,
        min = (basisValue * z.minPct).roundHalfEven(),
        max = z.maxPct?.let { (basisValue * it).roundHalfEven() - 1 },
        color = z.color,
    )
}

/**
 * Friel HR zones in bpm for a given LTHR.
 *
 * [sport] selects the running or cycling boundary set; cycling LTHR sits a few
 * bpm lower relative to max HR, so its zone 1/2 boundary is lower.
 */
fun hrZones(lthr: Int, sport: String = "running"): List<ZoneRange> {
    val model = if (sport == "cycling") "friel_lthr_bike" else "friel_lthr_run"
    return HR_MODELS.getValue(model).scaledTo(lthr)
}

/** Coggan power zones in watts for a given FTP. */
fun powerZones(ftp: Int): List<ZoneRange> =
    POWER_MODELS.getValue("coggan_ftp").scaledTo(ftp)

/**
 * The five-zone %HRmax display model used by the activity histogram.
 *
 * Deliberately not [hrZones]: this needs only max HR, so it works for every
 * activity, and it is not comparable to the Friel zones. See spec/zones.yaml.
 */
fun displayHrZones(maxHr: Int): List<ZoneRange> =
    HR_MODELS.getValue("display_maxhr").scaledTo(maxHr)

/** The zone number a reading falls in, or null if it is below every zone. */
fun zoneNumberFor(value: Int, zones: List<ZoneRange>): Int? =
    zones.firstOrNull { value >= it.min && (it.max == null || value <= it.max) }?.number

/**
 * The form band a TSB value falls in.
 *
 * Bands are ordered high to low with an exclusive lower bound, so the first
 * whose floor the value clears is the match. A null TSB gets the neutral band.
 */
fun tsbBandFor(tsb: Double?): TsbBand {
    if (tsb == null) {
        return TSB_BANDS.first { it.key == TSB_UNKNOWN_BAND }
    }
    return TSB_BANDS.firstOrNull { it.min == null || tsb > it.min } ?: TSB_BANDS.last()
}
