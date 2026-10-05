// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
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
import org.maplibre.android.style.sources.GeoJsonSource
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

    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    var loaded by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(Tokens.Radius.xl)),
    ) {
        MapLibreView(
            styleJson = styleJson,
            modifier = Modifier.fillMaxSize(),
        ) { map, style -> loaded = map to style }

        LaunchedEffect(loaded, positions) {
            val (map, style) = loaded ?: return@LaunchedEffect
            drawTrack(style, positions, lineColor)
            frameTrack(map, positions)
        }
    }
}

private const val TRACK_SOURCE = "activity_track_line"
private const val TRACK_LAYER = "activity_track_line_layer"

private fun drawTrack(style: Style, positions: List<LatLng>, colorArgb: Int) {
    if (style.getSource(TRACK_SOURCE) != null) return
    val line = LineString.fromLngLats(positions.map { Point.fromLngLat(it.longitude, it.latitude) })
    style.addSource(GeoJsonSource(TRACK_SOURCE, line))
    style.addLayer(
        LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(colorArgb),
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
