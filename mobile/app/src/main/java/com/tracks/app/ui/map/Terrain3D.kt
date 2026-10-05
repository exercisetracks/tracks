// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.util.Log
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.TerrainLoadMode
import org.maplibre.android.style.terrain.Terrain

/**
 * Real relief under the camera — but only while the camera is looking at it.
 *
 * ## What terrain actually costs
 *
 * Turning terrain on does not add a layer. It changes how the entire map
 * rasterises: every layer except symbols stops drawing to the screen and starts
 * drawing into a per-tile offscreen texture, which is then painted onto the
 * terrain mesh. That pipeline — draping — is the unfinished part of the branch
 * this renderer is built from, and it has a signature set of artifacts, all of
 * which are one problem wearing five hats:
 *
 *  - contour and fill **seams**, along the edges between drape tiles
 *  - a hillside changing colour across a dead **straight line**, where two
 *    adjacent drape tiles resolved their DEM from different zoom levels
 *  - everything draped looking slightly **soft**, because a drape target is a
 *    fixed size and the screen is not
 *  - the viewport **filling in gradually** as you zoom, because drape targets
 *    are built under a per-frame budget
 *  - zoom-driven **fades not animating**, because a draped layer is only
 *    re-rasterised when something asks it to, not every frame
 *
 * None of that happens on the flat map. There the layers draw straight to the
 * screen the way they always did.
 *
 * ## So terrain follows the pitch
 *
 * Looking straight down, terrain is worth nothing. Relief is already carried by
 * the hillshade, and a displaced mesh seen from directly above is the same
 * picture with worse pixels — every cost above paid for no benefit at all.
 *
 * Tilt the camera and that inverts: the mesh becomes the entire point, and
 * softness that was intolerable on a map you were reading is unremarkable in an
 * oblique view of a mountain range.
 *
 * So the flat map takes the path it always took, pixel for pixel, and terrain
 * arrives with the two-finger tilt that asked for it — the gesture in
 * [MapGestures] is now the mode switch too. This is the simplification that
 * removes the whole class of artifacts instead of fixing them one at a time.
 *
 * ## Which DEM
 *
 * Tracks has two archives: the planet at z0-7, and a sharp one from z8 that
 * exists **only where a region has been downloaded**. A style carries one
 * terrain source, so it has to follow the camera — the sharpest DEM whose own
 * zoom range reaches the view, and none when neither does. Draping onto a mesh
 * with no DEM renders nothing at all, which is a blank map; draping onto one
 * stretched far past its maxzoom shatters the line work into dashes. Both were
 * found the hard way.
 *
 * ## One instance per map view
 *
 * A class rather than an object, held for the life of the view. The camera
 * listeners are registered once, in [attach]. A style document can be replaced
 * — downloading a region adds whole sources and layers — and registering per
 * style would leave one live listener per document, each with its own idea of
 * the current source, fighting over `setTerrain` every frame.
 *
 * MapLibre does have `removeOnCameraMoveListener`, so a per-style registration
 * could in principle be unwound; an earlier note here claimed otherwise and was
 * wrong. Registering once is still the right shape — there is nothing to unwind
 * if there is only ever one, and the listener's whole job spans the life of the
 * view rather than the life of a document.
 */
class Terrain3D {

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var sources: List<DemSource> = emptyList()
    private var current: DemSource? = null
    private var pitched = false

    /** Register the camera listeners. Call once, when the map is created. */
    fun attach(map: MapLibreMap) {
        this.map = map
        // Quality is the default and the sharpest. The budgeted modes only
        // spread drape work across frames, which is the progressive fill-in
        // this map should never show.
        map.setTerrainLoadMode(TerrainLoadMode.QUALITY)
        // On every camera move, not only when it settles: the tilt gesture is
        // continuous, and waiting for idle would mean tilting into a flat map
        // whose ground rises a moment after the fingers lift. `sync` returns
        // immediately unless the answer actually changes.
        map.addOnCameraMoveListener { sync() }
        map.addOnCameraIdleListener { sync() }
    }

    /**
     * Point at a freshly loaded style and re-evaluate.
     *
     * [styleJson] is the document the style was built from. A source's zoom
     * range is not exposed by the Style API, and it is not a constant here
     * either: the server narrows every source to the archives it actually
     * holds, so the same code sees z8-12 on one install and nothing on another.
     */
    fun onStyle(style: Style, styleJson: String) {
        this.style = style
        sources = demSources(styleJson).filter { style.getSource(it.id) != null }
        // A new document is a new terrain state — the old style object is gone,
        // and with it whatever terrain was set on it.
        current = null
        if (sources.isEmpty()) {
            // Not a warning. Also not silent — "why is the map flat" is a
            // question worth being able to answer from a log.
            Log.i(TAG, "no DEM source in the style; terrain stays off")
            return
        }
        sync()
    }

    private fun sync() {
        val map = map ?: return
        val style = style ?: return
        if (sources.isEmpty()) return
        val camera = map.cameraPosition

        // Hysteresis in and out, because a tilt gesture ends with the camera
        // drifting to a stop: a bare `> 0` test would toggle the whole
        // rasterisation path several times on the tail of one shove.
        pitched = if (pitched) camera.tilt > PITCH_OFF else camera.tilt >= PITCH_ON

        // Same reasoning for the source, and a sharper trap. Turning terrain on
        // *changes the reported zoom*, because zoom is measured against the
        // ground and the ground just moved — so choosing purely on the current
        // zoom oscillates at the boundary. It ran at about a swap a second on
        // the phone, and every swap rebuilt the terrain mesh.
        val keep = current?.takeIf { pitched && it.holds(camera.zoom) }
        val wanted = when {
            keep != null -> keep
            !pitched -> null
            else -> sources
                .filter { it.holds(camera.zoom) }
                // Sharpest first: a source that reaches this zoom with real
                // tiles beats one stretched to get here.
                .maxByOrNull { it.maxZoom }
        }

        if (wanted?.id == current?.id) return
        runCatching {
            style.setTerrain(wanted?.let { Terrain(it.id, EXAGGERATION) })
            current = wanted
            Log.i(
                TAG,
                if (wanted != null) {
                    "terrain on from '${wanted.id}' " +
                        "(pitch ${camera.tilt.toInt()}, z${camera.zoom.toInt()})"
                } else {
                    "terrain off (pitch ${camera.tilt.toInt()}, z${camera.zoom.toInt()})"
                },
            )
        }.onFailure {
            // The renderer is a draft branch. If terrain throws, the map should
            // still be a map — flat, hillshaded, and usable — rather than a
            // crash on the screen someone opened to find their way.
            Log.w(TAG, "terrain could not be set; leaving the map flat", it)
        }
    }

    private data class DemSource(val id: String, val minZoom: Double, val maxZoom: Double) {
        /**
         * Whether this source can carry the mesh at [zoom], with a margin.
         *
         * The margin is wider than the gap between sources on purpose, so the
         * ranges overlap and a source in use keeps working slightly outside the
         * band that would have selected it. That overlap is the hysteresis.
         */
        fun holds(zoom: Double): Boolean =
            zoom >= minZoom - HYSTERESIS && zoom <= maxZoom + OVERZOOM_ALLOWANCE + HYSTERESIS
    }

    private fun demSources(styleJson: String): List<DemSource> = runCatching {
        val sources = JSONObject(styleJson).optJSONObject("sources")
            ?: return@runCatching emptyList()
        sources.keys().asSequence().mapNotNull { id ->
            val source = sources.optJSONObject(id) ?: return@mapNotNull null
            if (source.optString("type") != "raster-dem") return@mapNotNull null
            DemSource(
                id = id,
                minZoom = source.optDouble("minzoom", 0.0),
                maxZoom = source.optDouble("maxzoom", DEFAULT_MAX_ZOOM),
            )
        }.toList()
    }.getOrDefault(emptyList())

    private companion object {
    /**
     * The pitch band that turns the third dimension on and off.
     *
     * Low, because the gesture is the intent: someone who has tilted at all has
     * asked to look at the terrain. The gap between the two values is what
     * stops a camera settling after a shove from flipping the rasterisation
     * path back and forth.
     */
    private const val PITCH_ON = 5.0
    private const val PITCH_OFF = 2.0

    /**
     * How far past its own maxzoom a DEM may be stretched before the drape
     * starts shattering the line work.
     *
     * Two levels is measured rather than chosen: the planet DEM stretched six
     * levels past z7 turned every road into dashes.
     */
    private const val OVERZOOM_ALLOWANCE = 2.0

    /** Half a zoom level: more than the wobble enabling terrain introduces,
     *  far less than the distance between the two archives. */
    private const val HYSTERESIS = 0.5

    /** The style spec's default when a source omits `maxzoom`. */
    private const val DEFAULT_MAX_ZOOM = 22.0

    /**
     * True elevation, not a dramatised one.
     *
     * Map apps commonly exaggerate 1.2x-1.5x because it looks better in a
     * screenshot. This one is for deciding whether to walk over a ridge or
     * around it, and a slope that renders steeper than it is answers that
     * question wrongly.
     */
    private const val EXAGGERATION = 1.0f

    private const val TAG = "TracksTerrain"
    }
}
