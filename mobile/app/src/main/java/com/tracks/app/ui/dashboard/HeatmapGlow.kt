// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * The dashboard's routes as the web draws them: soft glowing lines whose light
 * *adds up*, so one pass is a faint orange thread and a street run every week
 * burns through yellow to white. A port of `HeatmapGlowLayer.js`, value modes'
 * averaging included (see [tone]).
 *
 * Lines, not points: MapLibre's `heatmap` layer was the first attempt and
 * collapsed a year of GPS into saturated blobs that showed which towns
 * someone trained in and nothing about where — it is built for scattered
 * observations, not traces that already have shape.
 *
 * Nor a MapLibre `line` layer, which this replaced: a line layer blends each
 * stroke *over* the one below, so overlap only ever approaches the stroke's
 * own colour. Fifty runs down one street read the same flat orange as five.
 * Accumulation is the whole look, and MapLibre Android has no way to ask for
 * it — its custom layers are native code only.
 *
 * So the routes are rasterised here, into float buffers, and the result is
 * pinned to the map as an [ImageSource]. MapLibre moves and scales it with the
 * map for free, so panning is smooth; when the camera settles it is redrawn
 * for the new view, which is when a zoom sharpens. The image covers a
 * [MARGIN] beyond the view on every side, so a pan shows routes, not an edge.
 *
 * Rasterised by hand ([splat]) rather than stroked with a Canvas in an
 * additive blend mode, which was the first version and had two faults. A
 * value mode cuts each route into a line per colour step, and stroked lines
 * have caps: where two pieces met, both caps added, and every route came out
 * beaded with bright dots, plainly so when zoomed in. And eight bits per
 * channel cannot hold an average — summing colours there saturates, which is
 * why dense streets went white whatever pace was run on them. Here each
 * segment covers exactly its own stretch, as the web's quads do, and the sums
 * are floats.
 */
internal class HeatmapGlow(private val scope: CoroutineScope, private val density: Float) {

    private class Line(val xs: DoubleArray, val ys: DoubleArray, val glow: Int, val ink: Int) {
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
    }

    /** One drawn view: the bitmap and where on the map it goes. */
    private class Frame(val bitmap: Bitmap, val quad: LatLngQuad)

    private var lines: List<Line> = emptyList()
    private var dark = true
    private var valueColoured = false
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var job: Job? = null
    private val onIdle = MapLibreMap.OnCameraIdleListener { redraw() }

    /**
     * Per pixel: how much route passes ([weight]), and that weight times each
     * colour channel. Kept between draws, since they are tens of megabytes and
     * a redraw follows every pan; [buffers] keeps a cancelled draw that has not
     * yet noticed from sharing them with its replacement.
     */
    private var weight = FloatArray(0)
    private var red = FloatArray(0)
    private var green = FloatArray(0)
    private var blue = FloatArray(0)
    private val buffers = Mutex()

    /** Install on a freshly loaded style; a theme switch loads a new one. */
    fun attach(map: MapLibreMap, style: Style) {
        if (this.map !== map) {
            this.map?.removeOnCameraIdleListener(onIdle)
            map.addOnCameraIdleListener(onIdle)
        }
        this.map = map
        this.style = style
        if (style.getSource(SOURCE) == null) {
            val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            style.addSource(ImageSource(SOURCE, LatLngQuad(LatLng(1.0, 0.0), LatLng(1.0, 1.0), LatLng(0.0, 1.0), LatLng(0.0, 0.0)), blank))
            style.addLayer(
                RasterLayer(LAYER, SOURCE).withProperties(
                    // Each redraw replaces the image; a fade would flash the
                    // routes out and back in every time the camera settled.
                    PropertyFactory.rasterFadeDuration(0f),
                    PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
                ),
            )
        }
    }

    fun detach() {
        job?.cancel()
        map?.removeOnCameraIdleListener(onIdle)
        map = null
        style = null
    }

    /** New routes (the dashboard's GeoJSON, see `HeatmapRoutes`), drawn at once. */
    suspend fun setRoutes(geoJson: String, dark: Boolean, valueColoured: Boolean) {
        lines = withContext(Dispatchers.Default) { parse(geoJson) }
        this.dark = dark
        this.valueColoured = valueColoured
        redraw()
    }

    /** Draw the current view, dropping any drawing still under way for an older one. */
    fun redraw() {
        val map = map ?: return
        val w = map.width
        val h = map.height
        if (w <= 0f || h <= 0f) return
        // Rotation and tilt are off on this map, so the visible bounds are an
        // axis-aligned rectangle in Web Mercator and the screen is a linear
        // scale of it.
        val b = map.projection.visibleRegion.latLngBounds
        val view = View(
            x0 = mercX(b.longitudeWest), x1 = mercX(b.longitudeEast),
            y0 = mercY(b.latitudeNorth), y1 = mercY(b.latitudeSouth),
            widthPx = w, heightPx = h, zoom = map.cameraPosition.zoom,
        )
        val lines = lines
        val dark = dark
        val valueColoured = valueColoured
        job?.cancel()
        job = scope.launch {
            val frame = withContext(Dispatchers.Default) {
                buffers.withLock { render(lines, view, dark, valueColoured) }
            } ?: return@launch
            val source = style?.takeIf { it.isFullyLoaded }?.getSourceAs<ImageSource>(SOURCE) ?: return@launch
            // Both on this thread before the next frame, so the map never
            // shows the new image at the old position.
            source.setImage(frame.bitmap)
            source.setCoordinates(frame.quad)
        }
    }

    private class View(
        val x0: Double, val x1: Double, val y0: Double, val y1: Double,
        val widthPx: Float, val heightPx: Float, val zoom: Double,
    )

    private suspend fun render(lines: List<Line>, view: View, dark: Boolean, valueColoured: Boolean): Frame? {
        val spanX = view.x1 - view.x0
        val spanY = view.y1 - view.y0
        if (spanX <= 0 || spanY <= 0) return null
        // Screen pixels per Mercator unit.
        val px = view.widthPx / spanX
        val py = view.heightPx / spanY
        // The margin in whole screen pixels, so that at full resolution every
        // pixel of the image lands on one of the screen's. A fractional offset
        // has MapLibre's linear resampling spread each across two, which is a
        // half-pixel blur over the whole map. Kept on the world, too: past its
        // edge the quad's corners would clamp to the poles and stretch the
        // image off its routes.
        val left = min(floor(view.widthPx * MARGIN), floor(view.x0 * px)).coerceAtLeast(0.0)
        val right = min(floor(view.widthPx * MARGIN), floor((1 - view.x1) * px)).coerceAtLeast(0.0)
        val top = min(floor(view.heightPx * MARGIN), floor(view.y0 * py)).coerceAtLeast(0.0)
        val bottom = min(floor(view.heightPx * MARGIN), floor((1 - view.y1) * py)).coerceAtLeast(0.0)
        val rx0 = view.x0 - left / px
        val rx1 = view.x1 + right / px
        val ry0 = view.y0 - top / py
        val ry1 = view.y1 + bottom / py

        // Full resolution, unless that would make an image bigger than
        // [MAX_PIXELS] — a tablet, not a phone.
        val fullW = view.widthPx + left + right
        val fullH = view.heightPx + top + bottom
        val k = min(1.0, sqrt(MAX_PIXELS / (fullW * fullH)))
        val bw = (fullW * k).roundToInt().coerceAtLeast(1)
        val bh = (fullH * k).roundToInt().coerceAtLeast(1)
        val sx = bw / (rx1 - rx0)
        val sy = bh / (ry1 - ry0)

        val n = bw * bh
        if (weight.size < n) {
            weight = FloatArray(n); red = FloatArray(n); green = FloatArray(n); blue = FloatArray(n)
        } else {
            weight.fill(0f, 0, n); red.fill(0f, 0, n); green.fill(0f, 0, n); blue.fill(0f, 0, n)
        }

        // The web's widths, which widen as the map zooms out so a city's
        // worth of short dense segments does not thin to nothing.
        val baseHalf = if (valueColoured) VALUE_HALF_WIDTH else FREQUENCY_HALF_WIDTH
        val halfDp = min(baseHalf + max(0.0, 11 - view.zoom) * ZOOM_WIDENING, MAX_HALF_WIDTH)
        val radius = halfDp * density * k
        // Points closer than this on screen add nothing a line can show.
        val minStep = 0.75 / sx

        for (line in lines) {
            coroutineContext.ensureActive()
            if (line.maxX < rx0 || line.minX > rx1 || line.maxY < ry0 || line.minY > ry1) continue
            val rgb = if (dark) line.glow else line.ink
            val cr = ((rgb shr 16) and 0xFF) / 255f
            val cg = ((rgb shr 8) and 0xFF) / 255f
            val cb = (rgb and 0xFF) / 255f
            var lastX = line.xs[0]
            var lastY = line.ys[0]
            val count = line.xs.size
            for (i in 1 until count) {
                val x = line.xs[i]
                val y = line.ys[i]
                // The last point is always kept, so a piece still meets the next.
                if (i < count - 1 && abs(x - lastX) < minStep && abs(y - lastY) < minStep) continue
                splat(
                    (lastX - rx0) * sx, (lastY - ry0) * sy, (x - rx0) * sx, (y - ry0) * sy,
                    radius, bw, bh, cr, cg, cb,
                )
                lastX = x
                lastY = y
            }
        }

        coroutineContext.ensureActive()
        val pixels = IntArray(n)
        for (i in 0 until n) {
            val w = weight[i]
            if (w > 0f) pixels[i] = tone(w, red[i] / w, green[i] / w, blue[i] / w, dark, valueColoured)
        }
        val bitmap = Bitmap.createBitmap(pixels, bw, bh, Bitmap.Config.ARGB_8888)

        val quad = LatLngQuad(
            LatLng(lat(ry0), lng(rx0)), LatLng(lat(ry0), lng(rx1)),
            LatLng(lat(ry1), lng(rx1)), LatLng(lat(ry1), lng(rx0)),
        )
        return Frame(bitmap, quad)
    }

    /**
     * Add one segment, A to B in image pixels, to the buffers.
     *
     * The web's shader, on the CPU: a pixel within [r] of the segment gets
     * `(1 − d/r)²` of it, bright down the middle and nothing at the edge, which
     * is also all the antialiasing a line this soft needs. Only pixels that
     * project *onto* the segment count — no caps — so consecutive segments
     * butt together and nothing is counted twice where they meet.
     *
     * Walked row by row: in a row, both the distance across the line and the
     * position along it are linear in x, so the pixels it covers are one
     * interval, found directly rather than by testing a bounding box that is
     * mostly empty for any diagonal.
     */
    private fun splat(
        ax: Double, ay: Double, bx: Double, by: Double, r: Double,
        bw: Int, bh: Int, cr: Float, cg: Float, cb: Float,
    ) {
        val len = hypot(bx - ax, by - ay)
        if (len < 1e-6) return
        val ux = (bx - ax) / len
        val uy = (by - ay) / len
        val yFrom = max(0, floor(min(ay, by) - r).toInt())
        val yTo = min(bh - 1, ceil(max(ay, by) + r).toInt())
        for (y in yFrom..yTo) {
            val dy = y + 0.5 - ay
            var lo = Double.NEGATIVE_INFINITY
            var hi = Double.POSITIVE_INFINITY
            // Along: ux·(px − ax) + uy·dy in [0, len).
            val along = uy * dy
            if (abs(ux) > 1e-9) {
                val p1 = ax - along / ux
                val p2 = ax + (len - along) / ux
                lo = max(lo, min(p1, p2)); hi = min(hi, max(p1, p2))
            } else if (along < 0 || along >= len) continue
            // Across: −uy·(px − ax) + ux·dy in [−r, r].
            val across = ux * dy
            if (abs(uy) > 1e-9) {
                val q1 = ax + (r + across) / uy
                val q2 = ax - (r - across) / uy
                lo = max(lo, min(q1, q2)); hi = min(hi, max(q1, q2))
            } else if (abs(across) > r) continue
            val xFrom = max(0, ceil(lo - 0.5).toInt())
            val xTo = min(bw - 1, floor(hi - 0.5).toInt())
            val row = y * bw
            for (x in xFrom..xTo) {
                val s = -uy * (x + 0.5 - ax) + across
                val edge = 1 - abs(s) / r
                if (edge <= 0) continue
                val w = (edge * edge).toFloat()
                val i = row + x
                weight[i] += w
                red[i] += w * cr
                green[i] += w * cg
                blue[i] += w * cb
            }
        }
    }

    private companion object {
        const val SOURCE = "tracks_heat_glow"
        const val LAYER = "tracks_heat_glow_layer"

        /**
         * How far past the view the image reaches, as a share of the view, per
         * side. Enough to cover a flick until the redraw lands; more costs
         * buffer memory on every pixel of it.
         */
        const val MARGIN = 0.15

        /**
         * Caps the image at about 50 MB of buffers. A phone's card with its
         * margin fits under it, so phones draw at full resolution: this was
         * once 2 M, which drew a phone's at 78 % and the map visibly soft.
         */
        const val MAX_PIXELS = 3_000_000.0

        // The web's half-widths are device pixels on a desktop screen; in dp
        // on a phone they read heavy on a card a third the size, hence 0.6×.
        const val FREQUENCY_HALF_WIDTH = 2.75 * 0.6
        const val VALUE_HALF_WIDTH = 2.5 * 0.6
        const val ZOOM_WIDENING = 0.225 * 0.6
        const val MAX_HALF_WIDTH = 6.25 * 0.6

        /** The web's frequency intensity: faint alone, building where routes overlap. */
        const val FREQUENCY_INTENSITY = 0.18f

        // The next three are the web's AVERAGE_STYLE; keep them in step.

        /** How fast a value mode's colour becomes opaque: one line is about half, two most of the way. */
        const val VALUE_OPACITY = 0.8f

        /** How much brighter a value colour gets where many routes pile up — brighter, never whiter. */
        const val VALUE_LIFT = 0.35f

        /** How many routes it takes for the lift to be mostly there. */
        const val VALUE_LIFT_ROUTES = 8f

        /**
         * One pixel's colour, from how much route passes through it ([w]) and
         * the weighted average of their colours.
         *
         * Frequency is the web's additive glow, exactly: orange times the
         * accumulated intensity, each channel clamped, so red saturates first
         * and density climbs through yellow to white. In light mode that much
         * is *subtracted* from white, and since an overlay cannot subtract,
         * it becomes the colour that, laid over white at the largest channel's
         * strength `a`, leaves the same result: `(a − S) / a`.
         *
         * The value modes average rather than add, as the web's do. Adding
         * colours there turned every busy street white, which threw away the one thing
         * those modes are for — a street run at a steady green-to-orange pace
         * read as no pace at all. Here a pixel shows the *average* of the
         * colours through it, so a busy street is the typical pace run on it;
         * density makes it more opaque and somewhat brighter (darker on a
         * light map), never a different colour.
         */
        fun tone(w: Float, r: Float, g: Float, b: Float, dark: Boolean, valueColoured: Boolean): Int {
            if (!valueColoured) {
                val i = w * FREQUENCY_INTENSITY
                val sr = min(1f, r * i)
                val sg = min(1f, g * i)
                val sb = min(1f, b * i)
                if (dark) {
                    val a = min(1f, i)
                    return argb(a, sr / a, sg / a, sb / a)
                }
                // In light mode r, g, b are the ink; the web subtracts its complement.
                val tr = min(1f, (1 - r) * i)
                val tg = min(1f, (1 - g) * i)
                val tb = min(1f, (1 - b) * i)
                val a = max(tr, max(tg, tb))
                if (a <= 0f) return 0
                return argb(a, (a - tr) / a, (a - tg) / a, (a - tb) / a)
            }
            val a = 1 - exp(-w * VALUE_OPACITY)
            val lift = VALUE_LIFT * (1 - exp(-max(0f, w - 1) / VALUE_LIFT_ROUTES))
            val f = if (dark) 1 + lift else 1 - lift * 0.5f
            return argb(a, min(1f, r * f), min(1f, g * f), min(1f, b * f))
        }

        /** Unpremultiplied, as [Bitmap.createBitmap] takes it. */
        fun argb(a: Float, r: Float, g: Float, b: Float): Int =
            ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or
                ((r * 255).roundToInt().coerceIn(0, 255) shl 16) or
                ((g * 255).roundToInt().coerceIn(0, 255) shl 8) or
                (b * 255).roundToInt().coerceIn(0, 255)

        fun mercX(lng: Double) = (lng + 180) / 360
        fun mercY(lat: Double): Double {
            val s = sin(lat.coerceIn(-85.05, 85.05) * PI / 180)
            return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        }
        fun lng(x: Double) = x * 360 - 180
        fun lat(y: Double) = (atan(sinh(PI * (1 - 2 * y))) * 180 / PI).coerceIn(-85.05, 85.05)
    }

    private fun parse(geoJson: String): List<Line> {
        val features = JSONObject(geoJson).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Line>(features.length())
        for (f in 0 until features.length()) {
            val feature = features.getJSONObject(f)
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val props = feature.optJSONObject("properties")
            val xs = DoubleArray(coords.length())
            val ys = DoubleArray(coords.length())
            for (i in 0 until coords.length()) {
                val p = coords.getJSONArray(i)
                xs[i] = mercX(p.getDouble(0))
                ys[i] = mercY(p.getDouble(1))
            }
            out += Line(xs, ys, color(props?.optString("color"), 0xFF660D), color(props?.optString("ink"), 0xEA480C))
        }
        return out
    }

    private fun color(hex: String?, fallback: Int): Int =
        hex?.removePrefix("#")?.toIntOrNull(16) ?: fallback
}
