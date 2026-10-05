// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.tracks.app.AppContainer
import com.tracks.core.api.CourseDetail
import com.tracks.core.api.CourseProfile
import com.tracks.core.api.Waypoint
import com.tracks.core.format.distance
import com.tracks.core.format.elevation

/**
 * The saved thing you just tapped on the map.
 *
 * ## Why this exists separately from the library
 *
 * The library answers "what do I have"; this answers "what is *that*". Before
 * it, tapping a track drawn on the map fell through to the point inspector,
 * which dutifully reported the elevation and land cover under your finger and
 * said nothing whatsoever about the route you were pointing at — the map could
 * draw your tracks and could not tell you anything about one.
 *
 * ## Why the actions are not written here
 *
 * They come from [CourseActions] and [WaypointActions], the same blocks the
 * library rows use. Two surfaces offering different subsets of the same actions
 * is how somebody learns to check both before trusting either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionSheet(
    container: AppContainer,
    selection: Selection,
    notice: String?,
    actions: LibraryActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The same atlas the map draws from, so the symbol here is the symbol out
    // there — see [IconRow].
    var sprite by remember { mutableStateOf<SpriteSheet?>(null) }
    LaunchedEffect(Unit) { sprite = SpriteSheet.load(container) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_PAD)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            when (selection) {
                is Selection.Track -> TrackBody(selection.course, sprite, actions)
                is Selection.Place -> PlaceBody(selection.waypoint, sprite, actions)
            }

            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TrackBody(course: CourseDetail, sprite: SpriteSheet?, actions: LibraryActions) {
    var picking by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tap the colour to change it, exactly as you tap the name to rename
        // it. The two controls on this row are the two things about a track
        // that are yours to choose.
        Marker(
            color = course.color,
            icon = null,
            sprite = sprite,
            onClick = { picking = true },
        )
        // Tap the name to rename it. A labelled text box lower down was a
        // second place the name lived, and the one at the top — the one you are
        // looking at — was the one you could not touch.
        EditableName(
            name = course.name,
            style = MaterialTheme.typography.titleSmall,
            onRename = { actions.onCourseRename(course.summary(), it) },
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        buildString {
            append(distance(course.distanceMetres))
            if (course.ascentMetres > 0) append(" · ↑${elevation(course.ascentMetres)}")
            if (course.descentMetres > 0) append(" · ↓${elevation(course.descentMetres)}")
            if (com.tracks.app.ui.components.LocalHasDevice.current) append(" · ${deviceLabel(course.summary())}")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // The shape of the climbing, which is the thing a distance and a total
    // ascent cannot tell you: 340 m as one steady pull and 340 m as four sharp
    // ones are different days out.
    val points = profilePoints(course.profile)
    if (points.size > 1) {
        ElevationProfile(points = points, modifier = Modifier.fillMaxWidth())
    } else if (course.profile == null) {
        // Absent because it has not arrived, not because the track is flat.
        Text(
            "Loading the height profile…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    course.notes?.takeIf { it.isNotBlank() }?.let { notes ->
        Text(notes, style = MaterialTheme.typography.bodySmall)
    }

    // The title above is the editable name here.
    CourseActions(course.summary(), actions, withName = false)

    if (picking) {
        ColorPickerDialog(
            current = course.color,
            title = "Track colour",
            onPick = { actions.onCourseColor(course.summary(), it) },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A stored profile as the chart wants it: metres in, metres up.
 *
 * Samples with no elevation are dropped rather than drawn at zero. The server
 * leaves them null where a track runs off the edge of the DEM, and a profile
 * that plunges to sea level for the gap is a worse answer than a shorter one —
 * it invents a cliff that is not there and wrecks the vertical scale for every
 * real point beside it.
 */
internal fun profilePoints(profile: CourseProfile?): List<RoutePoint> =
    profile?.points.orEmpty().mapNotNull { point ->
        point.elevationMetres?.let { RoutePoint(point.distanceKm * 1000, it) }
    }

@Composable
private fun PlaceBody(waypoint: Waypoint, sprite: SpriteSheet?, actions: LibraryActions) {
    var picking by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Marker(
            color = waypoint.color,
            icon = waypoint.icon,
            sprite = sprite,
            onClick = { picking = true },
        )
        EditableName(
            name = waypoint.name,
            style = MaterialTheme.typography.titleSmall,
            onRename = { actions.onWaypointRename(waypoint, it) },
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        buildString {
            append(coordinateLabel(waypoint.lat, waypoint.lng))
            waypoint.elevationMetres?.let { append(" · ${elevation(it)}") }
            if (com.tracks.app.ui.components.LocalHasDevice.current) append(" · ${deviceLabel(waypoint)}")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    waypoint.notes?.takeIf { it.isNotBlank() }?.let { notes ->
        Text(notes, style = MaterialTheme.typography.labelSmall)
    }

    WaypointActions(waypoint, sprite, actions, withName = false)

    if (picking) {
        ColorPickerDialog(
            current = waypoint.color,
            title = "Place colour",
            onPick = { actions.onWaypointStyle(waypoint, it, null) },
            onDismiss = { picking = false },
        )
    }
}
