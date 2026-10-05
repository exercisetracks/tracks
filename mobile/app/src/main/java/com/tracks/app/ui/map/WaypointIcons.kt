// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.tracks.core.api.Waypoint
import org.maplibre.android.maps.Style

/**
 * A place's symbol, in the colour its owner chose.
 *
 * ## The thing that changed
 *
 * The map's sprites are ordinary images, not signed distance fields, so
 * MapLibre cannot tint them. That limitation is why a waypoint's colour used to
 * appear in a *disc behind* its symbol: the colour had to go somewhere, and the
 * symbol was the one place it could not go.
 *
 * The limitation is MapLibre's, not the pixels'. `Style.getImage` hands back
 * the atlas bitmap the style has already loaded, and a bitmap can be recoloured
 * by anyone — so a tinted copy is registered under its own name and the symbol
 * layer draws that. The disc is gone, and the mark on the map is now the symbol
 * itself rather than a symbol sitting on a badge.
 *
 * ## Why alpha-preserving rather than a hue shift
 *
 * `SRC_IN` keeps the source's alpha and replaces every colour with one. These
 * symbols are monochrome silhouettes, so that is exactly a recolour — and it
 * stays a clean recolour for a symbol drawn in any shade, which a hue rotation
 * would not.
 *
 * ## No fallback drawing
 *
 * A sprite the style does not carry cannot be tinted, and the caller is told so
 * rather than handed a blank: [ensure] returns the ids that really exist, and a
 * place whose symbol is missing falls back to the pin. An empty square on a map
 * is worse than a plain mark.
 */
internal object WaypointIcons {

    /** The image id a sprite-and-colour pair is registered under. */
    fun idFor(sprite: String, hex: String): String =
        "tracks-icon-$sprite-${hex.lowercase().removePrefix("#")}"

    /**
     * Register a tinted copy of every symbol in use.
     *
     * @return the ids now on the style, so the caller can tell which places
     *         actually have a symbol to draw.
     */
    fun ensure(context: Context, style: Style, waypoints: List<Waypoint>): Set<String> {
        if (!style.isFullyLoaded) return emptySet()

        val available = mutableSetOf<String>()
        waypoints.forEach { waypoint ->
            val sprite = spriteFor(waypoint.icon) ?: return@forEach
            val id = idFor(sprite, waypoint.color)
            // Re-checked rather than cached in a field: a style reload takes
            // every added image with it, and a layer naming one that is gone
            // draws nothing at all.
            if (style.getImage(id) != null) {
                available += id
                return@forEach
            }
            val source = style.getImage(sprite) ?: return@forEach
            style.addImage(id, tint(context, source, waypoint.color))
            available += id
        }
        return available
    }

    /**
     * The same shape, every pixel in [hex], transparency untouched — and sized
     * for this screen rather than for the atlas.
     *
     * The scaling is the part that is easy to leave out and impossible to miss
     * afterwards. Atlas sprites are around twenty pixels square, which is a
     * sensible size for a POI drawn among thousands of others and far too small
     * for a mark somebody placed deliberately. Registered as-is they came out
     * roughly a third the size of the pin beside them, because the pin is built
     * in device pixels and these are not.
     *
     * So both are built to the same physical size, and a saved place looks the
     * same whether it wears a symbol or the default pin.
     */
    private fun tint(context: Context, source: Bitmap, hex: String): Bitmap {
        val density = context.resources.displayMetrics.density
        // Matched to the pin's head rather than its full height: a pin is tall
        // because it stands on a point, while a symbol is centred on one.
        val target = (ICON_DP * density).toInt().coerceAtLeast(1)
        val scale = target.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)

        // Room for the outline on every side, or it would be clipped away at
        // exactly the edges it exists to define.
        val halo = (HALO_DP * density).coerceAtLeast(1f)
        val pad = kotlin.math.ceil(halo).toInt()
        val out = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val src = Rect(0, 0, source.width, source.height)
        val dst = Rect(pad, pad, pad + width, pad + height)

        fun paintOf(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

        // The outline is the same silhouette in white, stamped in a ring around
        // where the symbol will go — a dilation, done the cheap way. Eight
        // stamps rather than four: at four the diagonals stay bare and the
        // outline reads as a cross-shaped smudge.
        val white = paintOf(android.graphics.Color.WHITE)
        for (step in 0 until HALO_STAMPS) {
            val angle = 2.0 * Math.PI * step / HALO_STAMPS
            canvas.withOffset(
                (kotlin.math.cos(angle) * halo).toFloat(),
                (kotlin.math.sin(angle) * halo).toFloat(),
            ) { canvas.drawBitmap(source, src, dst, white) }
        }

        canvas.drawBitmap(
            source,
            src,
            dst,
            paintOf(
                runCatching { android.graphics.Color.parseColor(hex) }
                    .getOrDefault(android.graphics.Color.BLACK)
            ),
        )
        return out
    }

    private inline fun Canvas.withOffset(dx: Float, dy: Float, draw: () -> Unit) {
        save()
        translate(dx, dy)
        draw()
        restore()
    }

    /** As tall as the pin's head, so the two read as the same weight of mark. */
    private const val ICON_DP = 22f

    /**
     * The white edge every mark carries.
     *
     * The same reasoning as the pin's, which is where this came from: terrain
     * is variously pale rock, dark forest and mid-grey scree, and a symbol with
     * no edge disappears into whichever it lands on. A dark symbol on woodland
     * was the case that prompted it.
     */
    private const val HALO_DP = 1.4f

    /** Eight points of the compass — at four, the diagonals go bare. */
    private const val HALO_STAMPS = 8
}
