// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tracks.core.api.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * The shape of a route, drawn from its own points.
 *
 * Deliberately not a map view. This sits inside the detail screen's scroll,
 * where a GL surface would be an expensive, gesture-stealing thing to embed —
 * and, more to the point, a basemap needs tiles, which need either a network
 * or a downloaded region. The track alone needs neither: it renders from data
 * already fetched, in a tent, with the radio off. The full basemap lives on
 * the Map tab, where it has the screen to itself and a reason to be there.
 *
 * Web Mercator rather than raw lat/lng, so the drawn shape matches what the
 * same route looks like on the real map. At these zoom levels the difference
 * is small but visible on anything far from the equator — a north-south
 * out-and-back would otherwise render noticeably squashed.
 */
@Composable
fun TrackMap(
    track: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val points = remember(track) {
        track.mapNotNull { p ->
            val lat = p.lat ?: return@mapNotNull null
            val lng = p.lng ?: return@mapNotNull null
            // Guard the poles: the Mercator projection diverges at ±90°, and a
            // single bad sample there would flatten the whole track to a line.
            if (abs(lat) > 85.0) null else Mercator(x = lng, y = mercatorY(lat))
        }
    }
    if (points.size < 2) return

    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(Tokens.Radius.xl)),
    ) {
        drawRect(surface)

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        // A stationary activity — a treadmill with one GPS fix, say — has no
        // extent to scale to; max() keeps the divisor off zero.
        val spanX = max(maxX - minX, 1e-9)
        val spanY = max(maxY - minY, 1e-9)

        val padding = 16.dp.toPx()
        val usableW = size.width - padding * 2
        val usableH = size.height - padding * 2

        // One scale for both axes, so the route keeps its real proportions
        // instead of being stretched to fill the box.
        val scale = minOf(usableW / spanX, usableH / spanY)
        val drawnW = spanX * scale
        val drawnH = spanY * scale
        val originX = padding + (usableW - drawnW) / 2
        val originY = padding + (usableH - drawnH) / 2

        fun project(p: Mercator) = Offset(
            (originX + (p.x - minX) * scale).toFloat(),
            // Mercator y grows northward, screen y grows downward.
            (originY + drawnH - (p.y - minY) * scale).toFloat(),
        )

        val path = Path().apply {
            val first = project(points.first())
            moveTo(first.x, first.y)
            points.drop(1).forEach { p ->
                val o = project(p)
                lineTo(o.x, o.y)
            }
        }

        drawPath(
            path = path,
            color = line,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

private data class Mercator(val x: Double, val y: Double)

/** Web Mercator's y, in the same units as longitude so one scale fits both. */
private fun mercatorY(latDegrees: Double): Double {
    val lat = latDegrees * PI / 180.0
    return ln(tan(lat) + 1.0 / cos(lat)) * 180.0 / PI
}
