// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.format

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Unit conversion and display formatting.
 *
 * A port of `frontend/src/utils/formatUtils.js` and `frontend/src/lib/weight.js`,
 * checked against the same corpus those files produce — see
 * `spec/fixtures/format.json` and FormatFixtureTest.
 *
 * These are formulas rather than tables, so they are ported rather than
 * generated: there is nothing to code-generate from, and templating arithmetic
 * across three languages would be worse than writing it twice and testing that
 * the two agree. The fixtures are what make "agree" mean something — the same
 * approach caught Kotlin and Python rounding halves differently in the zone
 * tables.
 *
 * Date formatting is deliberately absent. The JS versions call
 * `toLocaleDateString`, which resolves against the browser's locale and time
 * zone; reproducing that in shared code would mean picking one and being subtly
 * wrong everywhere else. Dates are formatted per-platform.
 */

/**
 * Which units this process shows things in.
 *
 * ## Why a mutable global, of all things
 *
 * Because the alternative is worse. Units are an account preference — one
 * answer for the whole app, changed about once ever — and the honest way to
 * thread that through would be a parameter on every formatter, which means a
 * parameter on every composable that formats anything, which means several
 * hundred call sites carrying a boolean that is the same everywhere. That is
 * not dependency injection, it is ceremony.
 *
 * So the *default* comes from here and every formatter still takes an explicit
 * `imperial` argument. Call sites that care pass it; the fixtures pass it; the
 * app sets this once when settings load and never touches it again. Nothing
 * reads it except a defaulted parameter, so a test that passes the flag is
 * unaffected by whatever a previous test set.
 */
object Units {
    /** Set once from the account's preference; false until then. */
    var imperial: Boolean = false
}

// ── Conversions ──────────────────────────────────────────────────────────────

private const val FEET_PER_METRE = 3.28084
private const val METRES_PER_MILE = 1609.34
private const val KPH_PER_MPS = 3.6
private const val MPH_PER_MPS = 2.23694
private const val LB_PER_KG = 2.20462

fun metresToFeet(m: Double): Double = m * FEET_PER_METRE
fun metresToKm(m: Double): Double = m / 1000.0
fun metresToMiles(m: Double): Double = m / METRES_PER_MILE
fun mpsToKph(mps: Double): Double = mps * KPH_PER_MPS
fun mpsToMph(mps: Double): Double = mps * MPH_PER_MPS

/** Shown wherever a value is missing. Matches the web app's em dash. */
const val EMPTY = "—"

// ── Pace ─────────────────────────────────────────────────────────────────────

/**
 * Pace as `m:ss` per kilometre.
 *
 * Seconds are rounded, which can carry to 60 — `4:60` rather than `5:00`. That
 * is the web app's behaviour and it is reproduced deliberately: the fixtures
 * would flag a "fix" here as a divergence, and a client showing a different
 * pace from the browser for the same ride is the thing this port exists to
 * prevent. Fix it in both places or neither.
 */
fun paceMinPerKm(mps: Double?): String {
    if (mps == null || mps <= 0) return EMPTY
    return formatPaceParts(1000.0 / (mps * 60.0))
}

/** Pace as `m:ss` per mile. Same rounding note as [paceMinPerKm]. */
fun paceMinPerMile(mps: Double?): String {
    if (mps == null || mps <= 0) return EMPTY
    return formatPaceParts(METRES_PER_MILE / (mps * 60.0))
}

private fun formatPaceParts(minutes: Double): String {
    val whole = floor(minutes).toInt()
    val seconds = jsRound((minutes - whole) * 60).toInt()
    return "$whole:${seconds.toString().padStart(2, '0')}"
}

// ── Duration ─────────────────────────────────────────────────────────────────

/** `h:mm:ss` past an hour, `m:ss` below it. */
fun elapsed(seconds: Number?): String {
    val total = seconds?.toDouble() ?: return EMPTY
    if (total <= 0) return EMPTY
    val h = floor(total / 3600).toInt()
    val m = floor((total % 3600) / 60).toInt()
    val s = jsRound(total % 60).toInt()
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

// ── Distance, elevation, speed ───────────────────────────────────────────────

fun distance(metres: Double?, imperial: Boolean = Units.imperial): String {
    if (metres == null || metres == 0.0) return EMPTY
    return if (imperial) "${fixed(metresToMiles(metres), 2)} mi"
    else "${fixed(metresToKm(metres), 2)} km"
}

fun elevation(metres: Double?, imperial: Boolean = Units.imperial): String {
    if (metres == null) return EMPTY
    return if (imperial) "${jsRound(metresToFeet(metres)).toInt()} ft"
    else "${jsRound(metres).toInt()} m"
}

fun speed(mps: Double?, imperial: Boolean = Units.imperial): String {
    if (mps == null || mps == 0.0) return EMPTY
    return if (imperial) "${fixed(mpsToMph(mps), 1)} mph"
    else "${fixed(mpsToKph(mps), 1)} km/h"
}

/**
 * Pace for running, speed for everything else.
 *
 * The split is a display convention, not a unit conversion: a runner reads
 * minutes per kilometre and a cyclist reads kilometres per hour, and showing
 * either the wrong way round is instantly unreadable to its audience.
 */
fun pace(mps: Double?, isRunning: Boolean = true, imperial: Boolean = Units.imperial): String {
    if (mps == null || mps <= 0) return EMPTY
    if (!isRunning) return speed(mps, imperial)
    return if (imperial) paceMinPerMile(mps) else paceMinPerKm(mps)
}

// ── Weight ───────────────────────────────────────────────────────────────────
//
// The backend stores kilograms (the FIT spec's unit) even for weights the user
// entered in pounds, so stored values are often ugly floats — 9.0718 kg is
// exactly 20 lb. These convert cleanly both ways.

/** Formatted weight, or `BW` for bodyweight (null or zero). */
fun weight(kg: Double?, imperial: Boolean = Units.imperial): String {
    if (kg == null || kg <= 0) return "BW"
    val v = if (imperial) kg * LB_PER_KG else kg
    // Within a rounding hair of whole, show it whole: "20 lb", not "20.0 lb".
    val num = if (abs(v - jsRound(v)) < 0.05) jsRound(v).toInt().toString() else fixed(v, 1)
    return "$num ${if (imperial) "lb" else "kg"}"
}

/** Stored kg as a number in the user's unit, for prefilling an entry field. */
fun kgToDisplay(kg: Double?, imperial: Boolean = Units.imperial): Double {
    if (kg == null || kg <= 0) return 0.0
    val v = if (imperial) kg * LB_PER_KG else kg
    return if (abs(v - jsRound(v)) < 0.05) jsRound(v) else jsRound(v * 10) / 10
}

/** A number the user typed in their unit, back to kg for storage. */
fun displayToKg(value: Double?, imperial: Boolean = Units.imperial): Double {
    val n = value ?: 0.0
    return if (imperial) n / LB_PER_KG else n
}

fun weightUnit(imperial: Boolean): String = if (imperial) "lb" else "kg"

// ── Heart rate colour ────────────────────────────────────────────────────────

/**
 * Heart-rate ratio to an `rgb(...)` string: green through yellow to red.
 *
 * Returns CSS rather than a colour type because that is what the fixtures
 * compare and what keeps this identical to the web app. Platform UI converts.
 */
fun hrColor(ratio: Double): String {
    val r = min(1.0, max(0.0, ratio))
    return if (r < 0.5) {
        val t = r * 2
        "rgb(${jsRound(255 * t).toInt()}, ${jsRound(185 * (1 - t) + 255 * t).toInt()}, 0)"
    } else {
        val t = (r - 0.5) * 2
        "rgb(255, ${jsRound(255 * (1 - t)).toInt()}, 0)"
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Fixed-decimal formatting matching JavaScript's `Number.toFixed`.
 *
 * Kotlin has no multiplatform equivalent — `String.format` is JVM-only — and
 * the difference is not cosmetic: `toFixed` rounds half away from zero, so
 * building this out of `jsRound()` is what keeps a distance reading the same on
 * the phone as in the browser.
 */

/**
 * `Math.round` from JavaScript: ties go UP, toward positive infinity.
 *
 * Kotlin's `round()` rounds ties to EVEN, and the difference is not academic —
 * it is the third time this exact class of divergence has shown up between the
 * server, the web app, and this port. `round(0.5)` is 0 in Kotlin and 1 in
 * JavaScript, which turned a 0.5 m elevation gain into "0 m" on the phone and
 * "1 m" in the browser.
 *
 * Note this is NOT the same rounding as [fixed], which implements `toFixed` and
 * rounds ties away from zero. JavaScript genuinely uses both, and they differ
 * for negative values, so the two must stay separate.
 */
private fun jsRound(x: Double): Double = floor(x + 0.5)

internal fun fixed(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    var factor = 1L
    repeat(decimals) { factor *= 10 }

    val scaled = value * factor
    // round() is half-up for positives and half-down for negatives, which is
    // exactly "half away from zero" once the sign is handled.
    val rounded = if (scaled < 0) -round(-scaled) else round(scaled)
    val asLong = rounded.toLong()

    if (decimals == 0) return asLong.toString()

    val negative = asLong < 0
    val digits = abs(asLong).toString().padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals)
    val frac = digits.takeLast(decimals)
    return "${if (negative) "-" else ""}$whole.$frac"
}
