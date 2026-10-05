// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracks.app.AppContainer

/**
 * The full USGS quad legend.
 *
 * ## Paper, not theme
 *
 * Fixed light colours, exactly as the web legend does. Every symbol on this
 * sheet — the black culture glyphs, the dark boundary hatching, the pale
 * vegetation fills — was drawn to sit on white paper. Theming the panel and
 * keeping the swatches true was tried and read badly: half the entries lost
 * their detail against a dark surface, because a hairline contour or a 6%
 * wetland tint is only legible on the paper it was designed for. The rest of
 * the app follows the system theme; this one panel is a quad sheet and looks
 * like one.
 *
 * ## Why it is this long
 *
 * The map draws eight distinguishable trail types, four boundary weights and
 * fifty point symbols. The phone previously showed twelve entries total, which
 * meant the difference between a hiking path and a motorised one — encoded in
 * hue, and unguessable — simply was not available on the device you carry into
 * the field. Parity with the desktop sheet is the whole point.
 */
@Composable
fun MapLegend(container: AppContainer) {
    var sprite by remember { mutableStateOf<SpriteSheet?>(null) }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sprite = SpriteSheet.load(container)
        tried = true
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(PAPER, RoundedCornerShape(Tokens.Radius.xl))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LEGEND_LINES.forEach { (title, specs) ->
            LegendSection(title) {
                specs.forEach { spec ->
                    LegendRow(spec.label) { LineSwatch(spec) }
                }
            }
        }

        LegendSection("Vegetation & wetlands") {
            LEGEND_AREAS.forEach { (label, color) ->
                LegendRow(label) { AreaSwatch(color) }
            }
        }

        LegendSection("Public lands") {
            LEGEND_PUBLIC_LANDS.forEach { (label, color) ->
                LegendRow(label) { AreaSwatch(color) }
            }
        }

        LegendSection("Wildfire & smoke (live)") {
            LegendRow("Active fire (sized by acres)") {
                Canvas(
                    Modifier
                        .size(SWATCH_WIDTH, SWATCH_HEIGHT)
                        .background(SWATCH_BACKING, RoundedCornerShape(Tokens.Radius.base))
                ) {
                    drawCircle(LegendPalette.wildfireGlow.copy(alpha = 0.35f), size.minDimension / 2)
                    drawCircle(LegendPalette.wildfireCore, size.minDimension / 4)
                }
            }
            LegendRow("Fire perimeter") {
                Canvas(
                    Modifier
                        .size(SWATCH_WIDTH, SWATCH_HEIGHT)
                        .background(SWATCH_BACKING, RoundedCornerShape(Tokens.Radius.base))
                ) {
                    drawRect(LegendPalette.wildfirePerimeter.copy(alpha = 0.18f))
                    drawRect(
                        LegendPalette.wildfirePerimeter,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }
            LEGEND_SMOKE.forEach { (label, color) ->
                LegendRow(label) { AreaSwatch(color) }
            }
        }

        // Point symbols last: they are the longest run and the one part that
        // needs the network, so everything above is readable while they load.
        LEGEND_POINTS.forEach { (title, icons) ->
            LegendSection(title) {
                icons.forEach { icon ->
                    LegendRow(icon.label) { IconSwatch(sprite?.icon(icon.sprite)) }
                }
            }
        }

        if (tried && sprite == null) {
            Text(
                "Symbol icons could not be loaded from the server — the lines " +
                    "and area fills above are still accurate.",
                style = MaterialTheme.typography.labelSmall,
                color = INK_FAINT,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun LegendSection(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = INK_FAINT,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    content()
}

@Composable
private fun LegendRow(label: String, swatch: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        swatch()
        Text(label, style = MaterialTheme.typography.bodySmall, color = INK)
    }
}

@Composable
private fun LineSwatch(spec: LegendLineSpec) {
    Canvas(
        Modifier
            .width(SWATCH_WIDTH)
            .height(SWATCH_HEIGHT)
            .background(SWATCH_BACKING, RoundedCornerShape(Tokens.Radius.base))
    ) {
        val mid = size.height / 2
        spec.casing?.let {
            line(it, spec.casingWidth, null, mid)
        }
        if (spec.doubled) {
            // Two parallel rails, which is how the map draws both a graded
            // service road and a railway — the pair *is* the symbol.
            line(spec.color, spec.width, spec.dash, mid - 2.dp.toPx())
            line(spec.color, spec.width, spec.dash, mid + 2.dp.toPx())
        } else {
            line(spec.color, spec.width, spec.dash, mid)
        }
    }
}

private fun DrawScope.line(color: Color, width: Float, dash: FloatArray?, y: Float) {
    drawLine(
        color = color,
        start = Offset(1f, y),
        end = Offset(size.width - 1f, y),
        strokeWidth = width.dp.toPx(),
        cap = if (dash == null) StrokeCap.Round else StrokeCap.Butt,
        pathEffect = dash?.let {
            PathEffect.dashPathEffect(it.map { v -> v.dp.toPx() }.toFloatArray())
        },
    )
}

@Composable
private fun AreaSwatch(color: Color) {
    // Two layers: the paper tile, then the map's colour over it. Many of these
    // fills are barely-there tints that only mean anything against white.
    Box(
        Modifier
            .size(SWATCH_WIDTH, SWATCH_HEIGHT)
            .background(SWATCH_BACKING, RoundedCornerShape(Tokens.Radius.base))
            .background(color, RoundedCornerShape(Tokens.Radius.base))
    )
}

@Composable
private fun IconSwatch(icon: androidx.compose.ui.graphics.ImageBitmap?) {
    Box(
        Modifier
            .size(SWATCH_WIDTH, SWATCH_HEIGHT)
            .background(SWATCH_BACKING, RoundedCornerShape(Tokens.Radius.base)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            // A placeholder rather than a gap, so the row still reads as "a
            // symbol exists for this" while the sheet is in flight.
            Box(Modifier.size(9.dp).background(Color(0xFFD6D3D1), CircleShape))
        }
    }
}

/** Quad-sheet paper and its ink, fixed against the app's theme on purpose. */
private val PAPER = Color(0xFFF7F4EC)
private val INK = Color(0xFF44403C)
private val INK_FAINT = Color(0xFF78716C)
private val SWATCH_BACKING = PAPER

private val SWATCH_WIDTH = 46.dp
private val SWATCH_HEIGHT = 16.dp
