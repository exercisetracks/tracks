// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import com.tracks.app.ui.components.BarPill
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.tracks.core.api.TrackPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * One activity's route, on the real basemap.
 *
 * Replaces the Canvas polyline that stood in before MapLibre was wired up. The
 * polyline was honest about the shape of a route and useless for the question
 * people actually ask of it — *where* was this — which needs the terrain and
 * the place names under the line.
 *
 * [styleJson] is passed in rather than fetched here: the detail screen already
 * knows how to get it (cached to disk by [com.tracks.app.MapStyleCache]), and
 * a map component that fetches its own style would make every activity row a
 * potential network call.
 *
 * The route is coloured by a metric the user picks from pills on the map, as
 * on the web (see [RouteColour]); a track with none of those channels — a
 * race plan's preview — draws the plain accent line and shows no pills.
 *
 * Gestures are left enabled. A route the user cannot zoom into is a picture of
 * a map rather than a map — and the detail screen scrolls vertically while the
 * map pans in both axes, which MapLibre's own touch handling resolves without
 * help as long as it is allowed to consume the events.
 */
@Composable
fun ActivityTrackMap(
    track: List<TrackPoint>,
    styleJson: String?,
    modifier: Modifier = Modifier,
) {
    val positions = remember(track) {
        track.mapNotNull { p ->
            val lat = p.lat ?: return@mapNotNull null
            val lng = p.lng ?: return@mapNotNull null
            LatLng(lat, lng)
        }
    }
    if (positions.size < 2) return

    // No style yet — offline on a cold install, before the Map tab has ever
    // been opened. The route still has a shape worth showing, so fall back to
    // the projection-only drawing rather than showing nothing.
    if (styleJson == null) {
        TrackMap(track = track, modifier = modifier)
        return
    }

    // The web's colour pills (ActivityRoute.jsx): the route coloured by heart
    // rate, speed, cadence or gradient — and grit and flow on a mountain-bike
    // ride — so where the effort went can be read off the map itself. Only
    // the channels this recording has, and heart rate first when it has it,
    // as the web opens.
    val modes = remember(track) { availableRouteColours(track) }
    var mode by remember(track) { mutableStateOf(modes.firstOrNull()) }
    val plainColor = MaterialTheme.colorScheme.primary.toArgb()
    val routeGeoJson = remember(track, mode, plainColor) { routeFeatures(track, mode, plainColor) }

    var loaded by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    // Framed once. A theme switch swaps the style and a pill swaps the
    // colours; neither should throw away where the user had zoomed to.
    var framed by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(Tokens.Radius.xl)),
    ) {
        MapLibreView(
            embedded = true,
            styleJson = styleJson,
            modifier = Modifier.fillMaxSize(),
        ) { map, style -> loaded = map to style }

        if (modes.isNotEmpty()) {
            RouteColourPills(
                modes = modes,
                selected = mode,
                onPick = { mode = it },
                modifier = Modifier.align(Alignment.TopStart).padding(Tokens.Space.s2),
            )
            mode?.let {
                RouteColourLegend(it, Modifier.align(Alignment.BottomStart).padding(Tokens.Space.s2))
            }
        }

        // Keyed on the style as well as the colours: a theme switch hands
        // MapLibreView a new document, which drops the route's source.
        LaunchedEffect(loaded, routeGeoJson) {
            val (map, style) = loaded ?: return@LaunchedEffect
            drawTrack(style, routeGeoJson)
            if (!framed) {
                frameTrack(map, positions)
                framed = true
            }
        }
    }
}

/**
 * The pills, floating on the map's top edge like the web's control panel.
 * Wrapped rather than scrolled: six of them (a mountain-bike ride) do not fit
 * one line of a phone-width map, and a row that scrolls inside a map that pans
 * is two gestures fighting over one finger.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteColourPills(
    modes: List<RouteColour>,
    selected: RouteColour?,
    onPick: (RouteColour) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(Tokens.Radius.xl))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        modes.forEach { m ->
            BarPill(text = m.label, selected = m == selected, onClick = { onPick(m) })
        }
    }
}

/** What the colours mean, at the map's foot — the web's legend. */
@Composable
private fun RouteColourLegend(mode: RouteColour, modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(140.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(Tokens.Radius.lg))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(Tokens.Radius.lg))
                .background(Brush.horizontalGradient(mode.ramp.map { Color(it) })),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            mode.legend.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val TRACK_SOURCE = "activity_track_line"
private const val TRACK_LAYER = "activity_track_line_layer"

/**
 * The route as GeoJSON: one two-point line per segment, each carrying its own
 * colour — MapLibre's equivalent of the web's per-segment features. With no
 * mode, or no values for it, one plain line in the theme's accent, as before.
 */
private fun routeFeatures(track: List<TrackPoint>, mode: RouteColour?, plainArgb: Int): FeatureCollection {
    val segments = mode?.let { colourRoute(track, it) }.orEmpty()
    if (segments.isEmpty()) {
        val line = LineString.fromLngLats(
            track.mapNotNull { p -> p.lat?.let { lat -> p.lng?.let { Point.fromLngLat(it, lat) } } },
        )
        return FeatureCollection.fromFeatures(listOf(coloured(Feature.fromGeometry(line), plainArgb)))
    }
    return FeatureCollection.fromFeatures(
        segments.map { s ->
            val line = LineString.fromLngLats(
                listOf(Point.fromLngLat(s.fromLng, s.fromLat), Point.fromLngLat(s.toLng, s.toLat)),
            )
            coloured(Feature.fromGeometry(line), s.argb)
        },
    )
}

private fun coloured(feature: Feature, argb: Int): Feature = feature.also {
    it.addStringProperty("color", String.format("#%06X", argb and 0xFFFFFF))
}

private fun drawTrack(style: Style, features: FeatureCollection) {
    (style.getSource(TRACK_SOURCE) as? GeoJsonSource)?.let {
        it.setGeoJson(features)
        return
    }
    // Tolerance 0: the route is many tiny segments, and the default
    // simplification drops them at low zoom until the line breaks up — the
    // web turned it off for the same reason.
    style.addSource(GeoJsonSource(TRACK_SOURCE, features, GeoJsonOptions().withTolerance(0f)))
    style.addLayer(
        LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.lineWidth(3.5f),
            // Round joins matter more than they sound on a GPS trace: a track
            // is thousands of short segments, and mitred joins spike visibly
            // at every switchback.
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )
    )
}

/**
 * The card is small, so the padding is too — 48px of a 16:10 thumbnail is a
 * meaningful fraction of it, and any more crushes a long route into the middle
 * third. [frameCamera] handles the rest: a lap of the block would otherwise fit
 * at a zoom well past the deepest tile that exists, opening on a stretched
 * fragment with no street or label to say where it is.
 */
private fun frameTrack(map: MapLibreMap, positions: List<LatLng>) {
    frameCamera(map, positions, paddingPx = 48)
}
