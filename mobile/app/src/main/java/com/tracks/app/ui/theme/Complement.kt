// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The colour opposite [accent] on the colour wheel, for a second series that
 * must not be mistaken for the first — the weekly-volume chart's duration line
 * over its distance bars, which were both the accent.
 *
 * The hue turns 180° in HSL. Saturation keeps the accent's but never drops
 * below 0.55, and lightness is fixed per theme rather than inherited: an
 * accent's own lightness is tuned for a fill, and its complement at the same
 * value can vanish into the background (amber's is a navy on a dark surface).
 *
 * The web computes the same thing in frontend/src/lib/complement.js; both are
 * pinned to the same vectors, so a user sees one pair of colours on both.
 */
fun complementOf(accent: Color, dark: Boolean): Color {
    val r = accent.red.toDouble()
    val g = accent.green.toDouble()
    val b = accent.blue.toDouble()
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val l0 = (hi + lo) / 2
    val d = hi - lo
    val s0 = if (d == 0.0) 0.0 else if (l0 <= 0.5) d / (hi + lo) else d / (2.0 - hi - lo)
    var h = when {
        d == 0.0 -> 0.0
        hi == r -> ((g - b) / d) / 6.0
        hi == g -> (2.0 + (b - r) / d) / 6.0
        else -> (4.0 + (r - g) / d) / 6.0
    }
    if (h < 0) h += 1.0
    h = (h + 0.5) % 1.0
    val s = max(s0, 0.55)
    val l = if (dark) 0.68 else 0.42
    val m2 = if (l <= 0.5) l * (1 + s) else l + s - l * s
    val m1 = 2 * l - m2
    fun channel(hue: Double): Float {
        val x = ((hue % 1.0) + 1.0) % 1.0
        val v = when {
            x < 1.0 / 6 -> m1 + (m2 - m1) * x * 6
            x < 0.5 -> m2
            x < 2.0 / 3 -> m1 + (m2 - m1) * (2.0 / 3 - x) * 6
            else -> m1
        }
        return (floor(v * 255 + 0.5) / 255.0).toFloat()
    }
    return Color(channel(h + 1.0 / 3), channel(h), channel(h - 1.0 / 3))
}
