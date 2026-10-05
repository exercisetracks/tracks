// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image

/**
 * The map's shared chrome: how dense it is, and the two pickers.
 *
 * Gathered here because density is a decision that has to be made once. Every
 * sheet over this map is competing with the map for a phone screen, and
 * Material's defaults are sized for a form on a tablet — a stack of full-height
 * buttons and 20dp gutters turns a list of six tracks into two screens of
 * scrolling. These tokens are what "compact but still tappable" means in this
 * app, and nothing over the map should invent its own.
 *
 * The floor is the touch target, not the pixel count: 32dp squares and 4dp gaps
 * stay comfortably above the 24dp that a finger starts missing.
 */

/** Button padding for chrome floating over the map. */
internal val TIGHT = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/** Material's switch is form-sized; over a map it only has to be readable. */
internal const val SWITCH_SCALE = 0.75f

/** Gap between rows inside a sheet. */
internal val ROW_GAP = 6.dp

/** A sheet's side gutter — Material suggests 24dp, which is a lot of nothing. */
internal val SHEET_PAD = 14.dp

private val SWATCH = 24.dp
private val ICON_TILE = 34.dp

/**
 * Hex to a colour, tolerating what the server might hold.
 *
 * Colours arrive as text from a database column with no format check, so a
 * malformed one is possible and must not take a sheet down — a grey dot is a
 * fine way to render "somebody typed this by hand".
 */
internal fun parseHex(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF94A3B8))

/**
 * The palette offered for tracks and waypoints.
 *
 * Chosen to stay apart from each other *and* from the map underneath: a green
 * track on woodland or a brown one over a trail is invisible exactly where it
 * matters. These are all saturated enough to read over terrain.
 */
internal val TRACK_COLORS = listOf(
    "#2563EB", "#DC2626", "#EA580C", "#CA8A04",
    "#16A34A", "#0891B2", "#7C3AED", "#DB2777",
    "#0F172A", "#F8FAFC",
)

/**
 * The symbols a waypoint can take — the map's own sprite names.
 *
 * Not a third-party icon set, and that is the point. This app already ships a
 * sprite atlas that the map, the legend and the cartography all draw from, so
 * borrowing from it means a saved place looks like it belongs on the map it is
 * drawn on rather than like something pasted over it. Importing Maki or
 * Material would give waypoints symbols in a second visual language, sitting
 * next to the first.
 *
 * "marker" is the odd one out: there is no marker sprite, and none is wanted —
 * it means the plain coloured dot, which is what a place with nothing
 * particular to say about itself should look like.
 */
internal val WAYPOINT_ICONS = listOf(
    "marker", "peak", "viewpoint", "campground", "shelter", "alpine_hut",
    "drinking_water", "spring", "trailhead", "parking", "picnic", "toilets",
    "fuel", "restaurant", "cafe", "information", "park", "beach",
)

/** The sprite an icon name draws as, or null for the plain dot. */
internal fun spriteFor(icon: String): String? = icon.takeIf { it != "marker" }

/**
 * Colours, in one wrapping row of dots, with a way out to the whole spectrum.
 *
 * Used where a colour is chosen as part of making something — naming a new
 * track, saving a place — and the palette being open costs nothing because the
 * row exists for exactly one decision. Where the same control was repeated
 * under every item in a list it has been folded behind [ColorDot] instead.
 *
 * The rainbow dot at the end is the browser's own affordance, in the same
 * position: the ten presets cover the common case and the eleventh says the
 * case is not closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorRow(selected: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TRACK_COLORS.forEach { hex ->
            val chosen = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier
                    .size(SWATCH)
                    .background(parseHex(hex), CircleShape)
                    .then(
                        // A ring rather than an inner dot: the pale swatches
                        // lose a white centre entirely, so the marker has to
                        // live outside the colour it is marking.
                        if (chosen) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onPick(hex) },
            )
        }
        Box(
            Modifier
                .size(SWATCH)
                .background(SPECTRUM, CircleShape)
                .then(
                    // Ringed when the current colour is not one of the presets,
                    // so a custom colour still shows as chosen somewhere.
                    if (TRACK_COLORS.none { it.equals(selected, ignoreCase = true) }) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable { picking = true },
        )
    }

    if (picking) {
        ColorPickerDialog(
            current = selected,
            onPick = onPick,
            onDismiss = { picking = false },
        )
    }
}

/**
 * The colour of a thing, tapped to change it.
 *
 * Sits beside an editable name and works the way the name does: what you see is
 * what you touch. That symmetry is the whole design — nothing on the row
 * announces itself as a control, and both halves of it are one.
 */
@Composable
internal fun ColorDot(hex: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(SWATCH)
            .background(parseHex(hex), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
            .clickable(onClick = onClick),
    )
}

/**
 * The rainbow that means "not one of these ten".
 *
 * A sweep gradient, which is what the browser draws for the same button, so the
 * affordance is recognisably the same control on both.
 */
private val SPECTRUM = Brush.sweepGradient(
    listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF),
        Color(0xFFFF0000),
    )
)

/**
 * Symbols, drawn as themselves.
 *
 * The names were spelled out in words before — "drinking water", "alpine hut" —
 * which was three lines of text where eighteen tiles now fit in two rows, and
 * still did not tell you what the thing would look like on the map. Falls back
 * to the name when the sheet has not loaded or lacks a symbol, since a picker
 * of blank squares would be worse than a wordy one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconRow(sprite: SpriteSheet?, selected: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val ink = MaterialTheme.colorScheme.onSurface.hex()
    val tile = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WAYPOINT_ICONS.forEach { icon ->
            val chosen = icon == selected
            Box(
                Modifier
                    .size(ICON_TILE)
                    // Back on the theme's own surface. This was forced to a
                    // pale tile because the sprites were near-black pixels that
                    // could not be recoloured, so half the set vanished on a
                    // dark theme and the background was the only side of the
                    // contrast that could move. They are tinted now — see
                    // [WaypointIcons] — so the tile can be what it should be.
                    .background(
                        if (chosen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(Tokens.Radius.md),
                    )
                    .then(
                        if (chosen) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(Tokens.Radius.md),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onPick(icon) },
                contentAlignment = Alignment.Center,
            ) {
                val image = spriteFor(icon)?.let { sprite?.icon(it) }
                when {
                    // Tinted to the theme rather than shown as drawn: the
                    // sheet answers "which symbol", and the colour question is
                    // the swatches directly above it.
                    image != null -> Image(
                        bitmap = image,
                        contentDescription = icon.replace('_', ' '),
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.size(19.dp),
                    )

                    // The same bitmap the map draws, not an approximation of
                    // it: a picker that shows a dot where the map shows a pin
                    // is the picker lying about the choice being made.
                    icon == "marker" -> Image(
                        // Ink on the tile's own colour. Built with white — as
                        // the map's are — the edge and the centre hole both
                        // read as white *fill* against a tile, which on a dark
                        // theme made the whole pin a white blob.
                        bitmap = remember(context, ink, tile) {
                            WaypointPins.bitmap(context, ink, tile).asImageBitmap()
                        },
                        contentDescription = "Plain waypoint pin",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(22.dp),
                    )

                    else -> Text(
                        icon.take(3),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
    }
}

/**
 * A place's colour when nobody has chosen one.
 *
 * Ink, because the symbol is now drawn *in* this colour rather than on a
 * coloured badge behind it — so an unstyled place should read as a mark on the
 * map rather than as one that has been highlighted. Matches the server's own
 * default, so a place created here and one arriving off a watch look alike.
 */
internal const val DEFAULT_WAYPOINT_COLOR = "#0F172A"

/** `#rrggbb` for a themed colour, which the pin builder draws with. */
private fun Color.hex(): String =
    "#%06X".format(0xFFFFFF and android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    ))
