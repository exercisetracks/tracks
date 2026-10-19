// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Your activities on the map tab once it is tilted or turned.
 *
 * Flat and north-up, they are the heatmap glow ([HeatmapGlow][com.tracks.app.ui.dashboard.HeatmapGlow]):
 * one picture of the whole view, painted onto an image source whose corners
 * are the view's bounds. That picture is only right for the camera it assumes
 * — straight down, north up — and in 3D it was not drawn at all: the terrain
 * renderer drapes layers onto the ground, and the glow's single screen-sized
 * image is not something it drapes. Tilt the map and every activity vanished.
 *
 * So off the flat view the glow steps aside for plain lines from the same
 * `activity_tracks` source the taps already use, which the terrain drapes like
 * any trail. The web map makes the same swap (`activity_tracks_draped`); the
 * layer is the phone's own here, so a served style that predates that one
 * still gets it.
 *
 * Both go under the first label — see [labelAnchor].
 */
internal object ActivityLines3D {

    const val LAYER = "tracks_activity_lines_3d"
    private const val SOURCE = "activity_tracks"

    /** The glow's own camera: looking (nearly) straight down, north up. */
    fun isFlat(tilt: Double, bearing: Double): Boolean {
        val d = ((bearing % 360) + 360) % 360
        val offNorth = minOf(d, 360 - d)
        return tilt < FLAT_TILT && offNorth < FLAT_BEARING
    }

    /**
     * Where activity drawings go: under the first label, so a peak's or a
     * trail's name is never painted over by a year of runs; and under the
     * selected-activity highlight, wherever a served style put it.
     */
    fun labelAnchor(style: Style): String? {
        val layers = style.layers
        val firstLabel = layers.indexOfFirst { it is SymbolLayer }
        val hover = layers.indexOfFirst { it.id == "activity_tracks_hover" }
        val at = listOf(firstLabel, hover).filter { it >= 0 }.minOrNull() ?: return null
        return layers[at].id
    }

    /** Add the lines (hidden) if this style does not have them yet. */
    fun install(style: Style, inkRgb: Int) {
        if (style.getLayer(LAYER) != null || style.getSource(SOURCE) == null) return
        val ink = String.format("#%06X", inkRgb and 0xFFFFFF)
        val layer = LineLayer(LAYER, SOURCE).withProperties(
            PropertyFactory.lineColor(ink),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.exponential(1.4f), Expression.zoom(),
                    Expression.stop(6, 1.5f), Expression.stop(12, 3f), Expression.stop(16, 5f),
                ),
            ),
            PropertyFactory.visibility(Property.NONE),
        )
        val below = labelAnchor(style)
        if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    /** Glow or lines for this camera; neither when the group is hidden. */
    fun apply(style: Style, shown: Boolean, flat: Boolean, glowLayer: String) {
        if (!style.isFullyLoaded) return
        val lines = if (shown && !flat) Property.VISIBLE else Property.NONE
        val glow = if (shown && flat) Property.VISIBLE else Property.NONE
        style.getLayer(LAYER)?.setProperties(PropertyFactory.visibility(lines))
        style.getLayer(glowLayer)?.setProperties(PropertyFactory.visibility(glow))
    }

    /** Degrees: past these the glow's straight-down, north-up picture is visibly wrong. */
    private const val FLAT_TILT = 2.0
    private const val FLAT_BEARING = 1.0
}
