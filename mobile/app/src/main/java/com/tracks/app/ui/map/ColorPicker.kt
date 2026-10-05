// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hue, saturation and value — the space a colour is *chosen* in.
 *
 * Hex is how a colour is stored and a hopeless way to pick one: nobody nudges
 * a blue slightly darker by editing "2563EB". The picker works in this and
 * converts at the edges.
 */
internal data class Hsv(val hue: Float, val saturation: Float, val value: Float)

/**
 * A hex string as HSV, falling back to the palette's blue.
 *
 * Tolerant of what it is given — with or without the hash, either case, and
 * garbage — because it is fed by a text field somebody is halfway through
 * typing into. A parse failure has to be a colour, not an exception.
 */
internal fun hexToHsv(hex: String?): Hsv {
    val rgb = rgbOf(hex) ?: rgbOf(FALLBACK_HEX)!!
    val (r, g, b) = rgb
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Hsv(
        hue = (hue + 360f) % 360f,
        saturation = if (max == 0f) 0f else delta / max,
        value = max,
    )
}

/** HSV back to the `#RRGGBB` the server stores. Always upper case, always six. */
internal fun hsvToHex(hsv: Hsv): String {
    val c = hsv.value * hsv.saturation
    val x = c * (1f - abs(((hsv.hue / 60f) % 2f) - 1f))
    val m = hsv.value - c
    val (r, g, b) = when {
        hsv.hue < 60f -> Triple(c, x, 0f)
        hsv.hue < 120f -> Triple(x, c, 0f)
        hsv.hue < 180f -> Triple(0f, c, x)
        hsv.hue < 240f -> Triple(0f, x, c)
        hsv.hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(channel(r), channel(g), channel(b))
}

/**
 * `#RRGGBB` upper case, or null if this is not a colour.
 *
 * Null rather than a fallback, because the caller here *needs* the difference:
 * a half-typed "#2f" must leave the picker alone rather than snap it to black.
 */
internal fun normaliseHex(hex: String?): String? {
    val body = hex?.trim()?.removePrefix("#") ?: return null
    if (body.length != 6 || body.any { it.digitToIntOrNull(16) == null }) return null
    return "#${body.uppercase()}"
}

private fun rgbOf(hex: String?): Triple<Float, Float, Float>? {
    val body = normaliseHex(hex)?.removePrefix("#") ?: return null
    return Triple(
        body.substring(0, 2).toInt(16) / 255f,
        body.substring(2, 4).toInt(16) / 255f,
        body.substring(4, 6).toInt(16) / 255f,
    )
}

private const val FALLBACK_HEX = "#2563EB"

/**
 * The last few colours mixed by hand, kept across sessions.
 *
 * ## Why only custom ones
 *
 * The presets are already on the screen; listing them again under "recent"
 * would be a second copy of the palette. What is worth remembering is the
 * colour that took effort to arrive at — and which the user will want again the
 * next time they add a track to the same set.
 *
 * SharedPreferences rather than the encrypted mirror because this is a list of
 * colours: it says nothing about the user, and putting it behind the database
 * key would mean a picker that cannot open until the vault does.
 */
internal object RecentColors {

    private const val PREFS = "tracks_map_colors"
    private const val KEY = "recent"

    /** Twenty, matching the browser's own list so the two feel like one app. */
    const val LIMIT = 20

    fun read(context: Context): List<String> =
        prefs(context).getString(KEY, null)
            ?.split(',')
            ?.mapNotNull { normaliseHex(it) }
            ?.take(LIMIT)
            .orEmpty()

    /** Newest first, de-duplicated, capped. Returns the new list to show. */
    fun remember(context: Context, hex: String): List<String> {
        val colour = normaliseHex(hex) ?: return read(context)
        val updated = (listOf(colour) + read(context).filterNot { it == colour }).take(LIMIT)
        prefs(context).edit().putString(KEY, updated.joinToString(",")).apply()
        return updated
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Pick a colour: the palette, or mix one.
 *
 * ## Why a dialog rather than the row it replaces
 *
 * The row was ten swatches permanently open under every track and every place
 * in the library — the same palette repeated down the sheet, taking two lines
 * each, for a decision made roughly once per track. Folding it behind the
 * swatch you are already looking at costs one tap and gives the list back to
 * the things the list is about.
 *
 * ## Why a spectrum and not just the ten
 *
 * Because the browser has one, and a track coloured on the desktop that cannot
 * be re-coloured to the same shade on the phone makes the two halves of the app
 * disagree about what colours exist. The layout is deliberately the browser's:
 * saturation/value box, hue slider, hex field, recents.
 *
 * A preset applies and closes — it is a decision, complete on the tap. The
 * spectrum does not, because dragging through forty colours on the way to the
 * one you want should not send forty PATCHes to the server; "Use this colour"
 * is the commit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPickerDialog(
    current: String,
    title: String = "Colour",
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var hsv by remember { mutableStateOf(hexToHsv(current)) }
    var typed by remember { mutableStateOf(normaliseHex(current) ?: FALLBACK_HEX) }
    var recents by remember { mutableStateOf(RecentColors.read(context)) }
    val draft = hsvToHex(hsv)

    /** Move the spectrum, and keep the hex field showing where it went. */
    fun moveTo(next: Hsv) {
        hsv = next
        typed = hsvToHex(next)
    }

    fun commit(hex: String, custom: Boolean) {
        if (custom) recents = RecentColors.remember(context, hex)
        onPick(hex)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Tokens.Radius.xl2),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                Modifier.padding(18.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TRACK_COLORS.forEach { hex ->
                        Swatch(
                            hex = hex,
                            selected = hex.equals(draft, ignoreCase = true),
                            onClick = { commit(hex, custom = false) },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                SaturationValueBox(hsv) { s, v -> moveTo(hsv.copy(saturation = s, value = v)) }
                HueSlider(hsv.hue) { moveTo(hsv.copy(hue = it)) }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(Tokens.Radius.lg))
                            .background(parseHex(draft))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                RoundedCornerShape(Tokens.Radius.lg),
                            )
                    )
                    BasicTextField(
                        value = typed,
                        onValueChange = { text ->
                            typed = text
                            // Only a complete colour moves the spectrum. Every
                            // intermediate state of typing "#1A2B3C" parses as
                            // something, and following each one would send the
                            // box skating across the gamut under the user's
                            // thumb.
                            normaliseHex(text)?.let { hsv = hexToHsv(it) }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { normaliseHex(typed)?.let { commit(it, custom = true) } },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(Tokens.Radius.lg),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                if (recents.isNotEmpty()) {
                    Text(
                        "RECENT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recents.forEach { hex ->
                            Swatch(
                                hex = hex,
                                selected = hex.equals(draft, ignoreCase = true),
                                onClick = { commit(hex, custom = true) },
                            )
                        }
                    }
                }

                ButtonRow {
                    NeutralButton("Cancel", onClick = onDismiss)
                    TonalButton("Use this colour", onClick = { commit(normaliseHex(typed) ?: draft, custom = true) })
                }
            }
        }
    }
}

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .background(parseHex(hex), CircleShape)
            .then(
                // A ring rather than an inner dot: the pale swatches lose a
                // white centre entirely, so the marker has to live outside the
                // colour it is marking.
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    )
}

/**
 * The square: saturation left to right, value top to bottom.
 *
 * Drawn rather than composed — three stacked gradients and a ring is a paint
 * job, and doing it with nested Boxes would be four layout nodes per frame of a
 * drag.
 *
 * Tap *and* drag, both. A tap is how somebody picks a colour they can already
 * see; a drag is how they hunt for one. Wiring only the second makes the
 * control feel broken to everyone who tried the first.
 */
@Composable
private fun SaturationValueBox(hsv: Hsv, onMove: (Float, Float) -> Unit) {
    val pure = parseHex(hsvToHex(Hsv(hsv.hue, 1f, 1f)))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .pointerInput(Unit) {
                detectTapGestures { at -> onMove(fraction(at.x, size.width), 1f - fraction(at.y, size.height)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val at = change.position
                    onMove(fraction(at.x, size.width), 1f - fraction(at.y, size.height))
                }
            }
    ) {
        drawRect(pure)
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(
            color = Color.White,
            radius = 7.dp.toPx(),
            center = Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** The rainbow, and where on it you are. */
@Composable
private fun HueSlider(hue: Float, onMove: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectTapGestures { at -> onMove(fraction(at.x, size.width) * 360f) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onMove(fraction(change.position.x, size.width) * 360f)
                }
            }
    ) {
        drawRect(
            Brush.horizontalGradient(
                (0..6).map { parseHex(hsvToHex(Hsv(it * 60f % 360f, 1f, 1f))) }
            )
        )
        drawCircle(
            color = Color.White,
            radius = 7.dp.toPx(),
            center = Offset((hue / 360f) * size.width, size.height / 2f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Where a touch landed, as 0..1 — a drag off the edge clamps rather than wraps. */
private fun fraction(position: Float, extent: Int): Float =
    if (extent <= 0) 0f else (position / extent).coerceIn(0f, 1f)
