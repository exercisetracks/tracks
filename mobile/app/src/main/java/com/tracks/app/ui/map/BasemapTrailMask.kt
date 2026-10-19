// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import java.util.WeakHashMap

/**
 * No basemap trail under the overlay's own: the phone's half of the browser's
 * `maskedTrailFilters` (frontend style/layers/trails.js), from the same areas.
 *
 * Inside a downloaded area the OSM overlay draws every trail, richer, and the
 * basemap's copy under it was a second line along the same ground -- arriving
 * first, then covered, its name competing with the overlay's. Each basemap
 * trail layer keeps its own filter with "not wholly inside the areas" added;
 * a path crossing an area's edge keeps its basemap line, since the overlay
 * stops at the edge.
 */
internal object BasemapTrailMask {

    /** The basemap trail layers (frontend `buildTrails`). */
    val LAYERS = listOf(
        "trails_casing", "trails_path", "trails_footway", "trails_bridleway",
        "trails_cycleway", "trails_steps", "trails_other", "trails_labels",
    )

    /** Each style's layers' own filters, as the served style had them. */
    private val originals = WeakHashMap<Style, MutableMap<String, Expression?>>()

    /** `["!", ["within", MultiPolygon]]` as JSON, or null for no areas. */
    fun outsideJson(polygons: List<AreaPolygon>): String? {
        if (polygons.isEmpty()) return null
        val coords = polygons.joinToString(",", "[", "]") { polygon ->
            polygon.joinToString(",", "[", "]") { ring ->
                ring.joinToString(",", "[", "]") { (lng, lat) -> "[$lng,$lat]" }
            }
        }
        return """["!",["within",{"type":"MultiPolygon","coordinates":$coords}]]"""
    }

    fun apply(style: Style, polygons: List<AreaPolygon>) {
        if (!style.isFullyLoaded) return
        val outside = outsideJson(polygons)?.let { runCatching { Expression.Converter.convert(it) }.getOrNull() }
        val saved = originals.getOrPut(style) { HashMap() }
        for (id in LAYERS) {
            val layer = style.getLayer(id) ?: continue
            val own = saved.getOrPut(id) {
                when (layer) {
                    is LineLayer -> layer.filter
                    is SymbolLayer -> layer.filter
                    else -> null
                }
            }
            val filter = when {
                outside == null -> own
                own == null -> outside
                else -> Expression.all(own, outside)
            } ?: continue
            when (layer) {
                is LineLayer -> layer.setFilter(filter)
                is SymbolLayer -> layer.setFilter(filter)
            }
        }
    }
}
