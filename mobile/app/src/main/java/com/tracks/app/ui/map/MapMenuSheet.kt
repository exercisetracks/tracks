// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.OptionGrid
import org.maplibre.android.maps.Style

/**
 * Everything about how the map is drawn, in one place.
 *
 * The rail had six floating buttons — location, search, route, layers, 3D,
 * downloads — over a screen whose entire content is the map underneath. Most of
 * those are not things you do *to* the map, they are things you set about it,
 * and settings do not each need a permanent button covering terrain.
 *
 * So the rail keeps the three actions you take mid-task and this sheet holds the
 * rest: the layer switches, what the symbols mean, and the way in to offline
 * downloads. Grouping them is also what makes the legend findable — a floating
 * button for it would have been the wrong answer to "the phone needs a legend
 * too".
 *
 * There is no 3D switch. Tilting is a gesture — two fingers dragged up the
 * screen — and a control that duplicates a gesture is worse than no control: it
 * takes a state the camera already holds continuously and pretends it is a
 * boolean, so tilting by hand and then opening this sheet showed "off" over a
 * map that plainly was not.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapMenuSheet(
    container: com.tracks.app.AppContainer,
    style: Style?,
    hidden: Set<String>,
    onToggleLayer: (LayerGroup, Boolean) -> Unit,
    onOfflineMaps: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLegend by remember { mutableStateOf(false) }

    // Only the overlays, and only the ones this style actually carries — the
    // served style is trimmed to the archives that exist, so contours and the
    // detailed trail layers genuinely are not there until a region is saved.
    val available = TOGGLEABLE_GROUPS.filter { group ->
        style == null || group.layers.any { style.getLayer(it) != null }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SHEET_PAD)
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // One way in, not two. Tracks and waypoints had a row here as well
            // as their own button on the rail, and a duplicate entry point is
            // worse than either alone: it makes the sheet longer and leaves the
            // reader wondering whether the two lead to different places.
            MenuRow("Offline maps", "Manage", onOfflineMaps)

            HorizontalDivider()
            SectionLabel("Overlays")
            // Chips rather than a switch per row. Six overlays as switches was
            // a scrolling list where every entry looked like a setting; as
            // chips the whole set is one glance and the lit ones are the answer
            // to "what am I looking at".
            OptionGrid(
                available.map { it to it.label },
                isSelected = { it.id !in hidden },
                onPick = { group -> onToggleLayer(group, group.id in hidden) },
                modifier = Modifier.padding(vertical = 2.dp),
                multi = true,
            )
            Text(
                "Roads, water, terrain, contours and labels are always drawn.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )

            HorizontalDivider()
            MenuRow("Legend", if (showLegend) "Hide" else "Show") { showLegend = !showLegend }
            // Collapsed by default. A legend is something you consult once and
            // then stop needing, so it should not push the layer switches — the
            // thing people open this sheet for — off the screen every time.
            if (showLegend) MapLegend(container)
        }
    }
}

/**
 * A row that opens something else.
 *
 * 14dp of padding above and below each of these was 28dp of nothing per row on
 * a sheet floating over the map — enough, across three rows, to have pushed the
 * legend off a short screen entirely. 10dp still clears the 48dp touch target
 * once the text is inside it.
 */
@Composable
private fun MenuRow(label: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            action,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/**
 * The key to the map's lines and fills.
 *
 * Deliberately not a port of the browser's legend, which is 130-odd POI symbols
 * drawn out of the USGS sprite sheet. Those are the entries you *can* guess — a
 * tent is a campground — and reproducing them here would mean loading and
 * slicing a sprite for a panel nobody scrolls to the bottom of.
 *
 * What is here is what the map cannot say for itself: which of several similar
 * lines is a trail and which is a 4x4 track, what the brown squiggles are, and
 * what the app's own overlays mean. Drawn with Compose rather than sampled from
 * the style, so it needs no sprite and no loaded map — the trade is that these
 * colours are transcribed from the style's layer definitions and could drift
 * from them.
 */
