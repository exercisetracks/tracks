// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Point

/**
 * Deterministic thinning for public-land area names (`publiclands_labels`) —
 * this app's port of the web app's `usePublicLandLabelRank.js`, which owns
 * the same layer there. See that file and `frontend/.../publiclands.js` for
 * the full design rationale; the short version: the layer's baked style
 * filter starts matching nothing (`false`) on purpose, because its
 * `min_zoom` grade alone cannot stop adjacent same-grade areas — a
 * Wilderness and its enclosing National Forest, say — from printing their
 * names on top of each other. Something has to look at what actually loaded
 * and grant each name a slot in a world-aligned grid before the layer shows
 * anything at all.
 *
 * Without a port of that something, the mobile map never got past "matches
 * nothing" — the layer was never broken, it was waiting for a ranking pass
 * that only ever existed in the browser. This is that pass, run against
 * MapLibre Android's [VectorSource.querySourceFeatures] instead of the JS
 * SDK's `map.querySourceFeatures`.
 *
 * ## What is simplified relative to the web version
 *
 * No `sourcedata` trigger — MapLibre Android's public API has no clean
 * per-source "a new tile just loaded" event to hook the same way, so this
 * recomputes on camera idle only. A recompute a beat behind a tile that
 * streamed in without further panning is a smaller gap than the one this
 * exists to close.
 */
class PublicLandLabelRank(private val scope: CoroutineScope) {

    private val granted = mutableMapOf<String, Grant>()
    private var job: Job? = null

    private data class Grant(val zoom: Int, val lng: Double, val lat: Double)
    private data class Candidate(val name: String, val grade: Int, val lng: Double, val lat: Double)

    /** Debounced, the same 250ms the web version waits before re-ranking. */
    fun schedule(map: MapLibreMap, style: Style) {
        job?.cancel()
        job = scope.launch {
            delay(DEBOUNCE_MS)
            recompute(map, style)
        }
    }

    private fun recompute(map: MapLibreMap, style: Style) {
        val layer = style.getLayer(LAYER_ID) as? SymbolLayer ?: return
        val zoom = floor(map.cameraPosition.zoom).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
        val taken = mutableSetOf<String>()

        // Visible town names claim their cells first, so an area name can
        // never sit on a city label — matching the JS version's ordering.
        (style.getSourceAs<VectorSource>("basemap"))?.let { basemap ->
            runCatching {
                basemap.querySourceFeatures(
                    arrayOf("places"),
                    Expression.eq(Expression.get("kind"), "locality"),
                )
            }.getOrDefault(emptyList()).forEach { feature ->
                val point = feature.geometry() as? Point ?: return@forEach
                val minZoom = feature.getNumberProperty("min_zoom")?.toInt() ?: 0
                if (minZoom > zoom) return@forEach
                taken += cellKey(point.longitude(), point.latitude(), zoom, CELL_PX)
            }
        }

        // Already-granted names keep their cells at any deeper zoom — grants
        // are monotonic within the session, so a label already shown never
        // disappears from under the finger panning around it.
        for (grant in granted.values) {
            if (grant.zoom <= zoom) taken += cellKey(grant.lng, grant.lat, zoom, CELL_PX)
        }

        val overlay = style.getSourceAs<VectorSource>("overlay") ?: return
        val isCenterLabel = Expression.all(
            Expression.eq(Expression.geometryType(), "Point"),
            Expression.eq(Expression.get("label"), Expression.literal(1)),
        )
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<Candidate>()
        runCatching { overlay.querySourceFeatures(arrayOf("landuse"), isCenterLabel) }
            .getOrDefault(emptyList())
            .forEach { feature ->
                val name = feature.getStringProperty("name")
                    ?: feature.getStringProperty("land_type")
                    ?: return@forEach
                if (!seen.add(name) || granted.containsKey(name)) return@forEach
                val point = feature.geometry() as? Point ?: return@forEach
                val grade = feature.getNumberProperty("min_zoom")?.toInt() ?: DEFAULT_GRADE
                candidates += Candidate(name, grade, point.longitude(), point.latitude())
            }
        candidates.sortWith(compareBy({ it.grade }, { it.name }))

        for (candidate in candidates) {
            if (ceil(candidate.grade.toDouble()).toInt() > zoom) continue
            val key = cellKey(candidate.lng, candidate.lat, zoom, CELL_PX)
            if (!taken.add(key)) continue
            granted[candidate.name] = Grant(zoom, candidate.lng, candidate.lat)
        }

        val allowed = granted.filterValues { it.zoom <= zoom }.keys.toList()
        layer.setFilter(
            Expression.all(
                isCenterLabel,
                Expression.`in`(
                    Expression.coalesce(Expression.get("name"), Expression.get("land_type")),
                    Expression.literal(allowed.toTypedArray()),
                ),
            ),
        )
    }

    /**
     * A point's world-aligned grid cell at zoom [z] — the exact projection
     * `cellKey` in `frontend/.../utils/labelRank.js` uses, so the same
     * points claim the same cells regardless of which client is asking.
     */
    private fun cellKey(lng: Double, lat: Double, z: Int, cellPx: Double): String {
        val cells = (WORLD_PX * 2.0.pow(z)) / cellPx
        val x = floor(((lng + 180.0) / 360.0) * cells).toLong()
        val s = sin(lat * PI / 180.0)
        val y = floor((0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)) * cells).toLong()
        return "$x:$y"
    }

    private companion object {
        const val LAYER_ID = "publiclands_labels"

        /**
         * ~footprint of one wrapped area name + halo, screen px — matches the
         * web version, which uses this same cell size for a blocking town
         * name too.
         */
        const val CELL_PX = 120.0

        const val MIN_ZOOM = 6
        const val MAX_ZOOM = 16
        const val DEFAULT_GRADE = 9
        const val WORLD_PX = 512.0
        const val DEBOUNCE_MS = 250L
    }
}
