// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.tracks.core.api.Waypoint
import org.maplibre.android.maps.Style

/**
 * The classic map pin, drawn rather than fetched.
 *
 * ## Why this is not a sprite
 *
 * Everywhere else a waypoint's symbol comes from the map's own atlas, which is
 * the right call — a saved campsite should look like the campsites already on
 * the map. But the atlas has no pin in it, because cartography has no use for
 * one: every symbol there says what a place *is*, and a pin says only "here".
 *
 * It could not be tinted even if it existed. Those sprites are ordinary images
 * rather than signed distance fields, so MapLibre cannot recolour them — which
 * is why a waypoint's colour currently shows in a disc *under* its symbol. A
 * plain coloured disc is what the default looked like, and a disc is a
 * perfectly good way to say nothing in particular.
 *
 * ## Why one image per colour
 *
 * Rather than one SDF image tinted per feature. A true distance field would
 * have to be generated to look right at any size, and the alternative — handing
 * MapLibre a hard-edged alpha mask and calling it SDF — renders soft and
 * blocky. The palette is ten colours and only the ones actually in use are
 * built, so the honest version costs a handful of small bitmaps.
 */
internal object WaypointPins {

    /** The image id a colour's pin is registered under. */
    fun idFor(hex: String): String = "tracks-pin-${hex.lowercase().removePrefix("#")}"

    /**
     * Make sure every colour in [waypoints] has a pin on the style.
     *
     * Re-checked on every draw rather than once, because a style reload — which
     * a region download causes — takes the images with it, and a symbol layer
     * naming an image the style does not have draws nothing at all.
     */
    fun ensure(context: Context, style: Style, waypoints: List<Waypoint>) {
        if (!style.isFullyLoaded) return

        waypoints.asSequence()
            .filter { spriteFor(it.icon) == null }
            .map { it.color }
            .distinct()
            .forEach { hex ->
                val id = idFor(hex)
                if (style.getImage(id) == null) {
                    style.addImage(id, pin(context, hex, android.graphics.Color.WHITE))
                }
            }
    }

    /**
     * A pin for something other than the map — the icon picker, which shows
     * this so the choice on offer looks like the thing it produces.
     */
    fun bitmap(context: Context, hex: String, accent: Int = android.graphics.Color.WHITE): Bitmap =
        pin(context, hex, accent)

    /**
     * One pin: a round head over a point, in [hex], edged and holed in [accent].
     *
     * The edge is not decoration. On the map these sit on terrain that is
     * variously pale rock, dark forest and mid-grey scree, and a shape with no
     * edge disappears into whichever it lands on — the same reason the saved
     * track lines carry a casing. So there, [accent] is white.
     *
     * It is a parameter because the picker is not the map. A tile draws the pin
     * on a known background in the theme's ink, and a white edge and centre
     * against that read as a pin *filled with white* rather than as a pin —
     * badly enough on a dark theme that the whole shape looked like a white
     * blob. Passing the tile's own colour puts the edge back where it belongs:
     * invisible, leaving the silhouette and its hole.
     */
    private fun pin(context: Context, hex: String, accent: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (PIN_WIDTH_DP * density).toInt().coerceAtLeast(8)
        val height = (PIN_HEIGHT_DP * density).toInt().coerceAtLeast(10)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val stroke = STROKE_DP * density
        val centreX = width / 2f
        val radius = (width - stroke) / 2f
        val centreY = radius + stroke / 2f
        val tipY = height - stroke / 2f

        // Head and tail unioned into one silhouette before anything is drawn.
        // Stroking them separately would leave the outline running *through*
        // the pin where the two overlap.
        val shape = Path().apply { addCircle(centreX, centreY, radius, Path.Direction.CW) }
        val tail = Path().apply {
            moveTo(centreX - radius * SHOULDER, centreY + radius * SHOULDER)
            // Curved sides rather than a straight taper: a triangle under a
            // circle reads as an arrow, and the shape people recognise as a
            // map pin is a teardrop.
            quadTo(centreX - radius * 0.34f, tipY - radius * 0.45f, centreX, tipY)
            quadTo(centreX + radius * 0.34f, tipY - radius * 0.45f,
                centreX + radius * SHOULDER, centreY + radius * SHOULDER)
            close()
        }
        shape.op(tail, Path.Op.UNION)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(hex)
            style = Paint.Style.FILL
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawPath(shape, fill)
        canvas.drawPath(shape, outline)

        // The hole in the head. It is what separates a pin from a balloon, and
        // it keeps the shape legible when the fill colour is close to whatever
        // is underneath.
        canvas.drawCircle(
            centreX, centreY, radius * HOLE,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.FILL
            },
        )
        return bitmap
    }

    /** Sized against the sprite symbols beside it, which draw at about 19dp. */
    private const val PIN_WIDTH_DP = 22f
    private const val PIN_HEIGHT_DP = 30f
    private const val STROKE_DP = 2f

    /** Where the tail leaves the head, as a fraction of its radius. */
    private const val SHOULDER = 0.70f
    private const val HOLE = 0.34f
}
