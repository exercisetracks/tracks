// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tracks.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.UiState
import com.tracks.app.ui.components.BarPill
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.EmptyState
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.dashboard.sportLabel
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.ActivitySummary
import com.tracks.core.api.TrackShape
import com.tracks.core.format.distance
import com.tracks.core.format.elapsed
import com.tracks.core.local.TripSummary
import com.tracks.core.spec.sportType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The training log.
 *
 * ## What changed, and why
 *
 * Every row used to be a title, a sport, and three labelled figures in a wall
 * of identical cards. Scanning it meant reading, because nothing distinguished
 * one row from another until you had read both — and the thing that actually
 * identifies a ride is its shape. A route thumbnail on each GPS row turns the
 * list into something you recognise instead of parse; two loops from the same
 * trailhead look different at a glance in a way "14.2 km · 1:12" never will.
 *
 * The figures are still there, just quieter: one line, no labels, because
 * kilometres and a duration do not need to be told apart.
 *
 * ## The thumbnail is a drawing, not a map
 *
 * It was briefly a real rendered basemap, and it was the wrong picture. At a
 * stamp's size a map is a smear of landcover with a line somewhere in it — the
 * detail that makes a map worth reading needs room this row does not have, and
 * it competes with the route rather than framing it. What identifies a ride is
 * its *shape*, and the shape reads best against nothing at all.
 *
 * So: the outline, on a dark panel. The panel is a gradient rather than flat
 * black and slightly translucent rather than opaque, which is the difference
 * between a hole punched in the card and something sitting on it.
 *
 * Reads only from the local mirror, which is the whole point of the mirror. A
 * cold start in a tent with no signal shows the user's history rather than a
 * spinner; the thumbnails are the one thing that needs a server, and when they
 * cannot be fetched the rows lose a picture, not their contents.
 */
@Composable
fun ActivitiesScreen(
    state: UiState,
    vm: ActivitiesViewModel,
    onActivityClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val local by vm.state.collectAsStateWithLifecycle()

    if (state.activities.isEmpty()) {
        EmptyState(
            title = "Nothing cached yet",
            body = if (state.serverUrl.isBlank()) {
                "Point Tracks at your server in Settings, then sign in."
            } else {
                "Sync to pull your activities down for offline use."
            },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val visible = remember(state.activities, local.sort, local.types) {
        vm.visible(state.activities, local)
    }

    Column(modifier.fillMaxSize()) {
        // Only when something is hidden. A permanent "showing 128 of 128" is a
        // line of chrome that says nothing; a line that appears exactly when the
        // list is not the whole list is the answer to "where did the rest go".
        if (local.types.isNotEmpty()) {
            FilterSummary(
                shown = visible.size,
                total = state.activities.size,
                types = local.types,
                onClear = vm::clearTypes,
            )
        }

        if (visible.isEmpty()) {
            EmptyState(
                title = "Nothing of that type",
                body = "No activities match the types you picked. Clear the filter " +
                    "to see everything again.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        // Selection mode: long-press starts it, and a trip needs two or more.
        if (local.selected.isNotEmpty()) {
            MergeBar(
                count = local.selected.size,
                onMerge = vm::mergeSelected,
                onCancel = vm::clearSelection,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (local.trips.isNotEmpty() && local.selected.isEmpty()) {
                items(local.trips, key = { "trip-${it.id}" }) { trip ->
                    TripRow(trip, onDelete = { vm.deleteTrip(trip.id) })
                }
            }
            items(visible, key = { it.id }) { activity ->
                ActivityRow(
                    activity = activity,
                    shape = local.shapes[activity.id],
                    selected = activity.id in local.selected,
                    onClick = {
                        if (local.selected.isNotEmpty()) vm.toggleSelected(activity.id)
                        else onActivityClick(activity.id)
                    },
                    onLongClick = { vm.toggleSelected(activity.id) },
                )
            }
        }
    }
}

/**
 * The list's controls, for the app bar.
 *
 * They live in the bar rather than in a strip above the rows, which is where
 * they started. Two reasons, and the second is the one that mattered: a strip
 * costs a row of a screen that is a list, and it scrolls away with the content
 * — so changing the sort halfway down meant flinging back to the top first. In
 * the bar they are always reachable and cost nothing.
 *
 * Small on purpose. These are three secondary controls sharing a line with the
 * page's name; at the size of a normal chip they read as the point of the
 * screen, which they are not.
 */
@Composable
fun ActivityListActions(
    vm: ActivitiesViewModel,
    activities: List<ActivitySummary>,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val counts = remember(activities) { sportTypeCounts(activities) }

    Row(
        modifier.padding(end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActivitySort.entries.forEach { option ->
            BarPill(
                text = option.label,
                selected = option == state.sort,
                onClick = { vm.setSort(option) },
            )
        }

        Box {
            BarPill(
                text = if (state.types.isEmpty()) "Type" else "Type · ${state.types.size}",
                selected = state.types.isNotEmpty(),
                onClick = { menuOpen = true },
            )
            TypeMenu(
                expanded = menuOpen,
                counts = counts,
                selected = state.types,
                onToggle = vm::toggleType,
                onClear = { vm.clearTypes(); menuOpen = false },
                onDismiss = { menuOpen = false },
            )
        }
    }
}

/**
 * Which types to show.
 *
 * Multi-select, and it stays open while you pick: "swims and rides" is a normal
 * thing to ask for, and a menu that closes on every tap turns it into three
 * gestures per type. It closes when you tap away, which is what a menu with no
 * confirm button should do.
 */
@Composable
private fun TypeMenu(
    expanded: Boolean,
    counts: List<SportTypeCount>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("All types") },
            onClick = onClear,
            trailingIcon = {
                if (selected.isEmpty()) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                }
            },
        )
        HorizontalDivider()
        counts.forEach { entry ->
            DropdownMenuItem(
                text = { Text(sportLabel(entry.type)) },
                onClick = { onToggle(entry.type) },
                leadingIcon = {
                    // Not clickable itself: the whole row is the target, and a
                    // checkbox that swallows the tap makes the label dead.
                    Checkbox(checked = entry.type in selected, onCheckedChange = null)
                },
                trailingIcon = {
                    Text(
                        "${entry.count}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}


/** What the filter is doing to the list, and one tap out of it. */
@Composable
private fun FilterSummary(
    shown: Int,
    total: Int,
    types: Set<String>,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$shown of $total · ${types.sorted().joinToString(", ") { sportLabel(it) }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        NeutralButton("Clear", onClick = onClear)
    }
}

@Composable
private fun ActivityRow(
    activity: ActivitySummary,
    shape: TrackShape?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        // The row is as tall as its text and the map fills it, rather than the
        // map having a height of its own that the text has to be padded out to
        // meet. That is what removes the last of the space around it.
        Row(
            Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                // The padding lives on the text rather than on the row, so the
                // map runs to the card's own edge and is clipped by its corner
                // radius. A thumbnail with a margin around it inside a card
                // with a margin around it is two margins for one picture, and
                // on a list this long that is a row's worth of screen every
                // four activities.
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    activity.name ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Sport comes from the shared spec, so the phone and the
                    // browser classify the same ride the same way.
                    Text(
                        sportType(activity.sport, activity.subSport),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    activity.startedAt?.let {
                        Text(
                            "· ${friendlyDate(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // One quiet line rather than three labelled figures. Distance
                // and a duration are self-evident; labelling them spent three
                // lines of a row on saying "Distance" under a number in
                // kilometres.
                Text(
                    buildList {
                        activity.distanceMeters?.takeIf { it > 0 }?.let { add(distance(it)) }
                        activity.durationSeconds?.let { add(elapsed(it)) }
                        activity.avgHeartRate?.let { add("$it bpm") }
                        activity.totalAscent?.takeIf { it > 0 }?.let { add("${it.toInt()} m ↑") }
                    }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only for activities that went somewhere. A treadmill run has no
            // shape, and an empty box where a picture belongs reads as a
            // failure rather than as an absence.
            if (shape != null) TrackThumbnail(shape)
        }
    }
}

/**
 * The route, on a dark panel.
 *
 * The panel is a diagonal gradient — lighter at the top-left, near-black at the
 * bottom-right — because a flat rectangle at this size reads as a missing
 * image. The gradient gives it a direction and a surface, and costs nothing:
 * it is two colours and a shader, not a render.
 *
 * Both colours are translucent, so the card's own tint comes through and the
 * panel belongs to the row rather than being a hole cut in it. Translucent
 * rather than a lighter opaque black specifically because the card is themed:
 * on a light theme the same two colours settle onto a light surface and stay a
 * dark panel, and on a dark one they barely lift off it.
 */
@Composable
private fun TrackThumbnail(shape: TrackShape) {
    Box(
        Modifier
            .width(THUMB_WIDTH)
            .fillMaxHeight()
            .background(
                Brush.linearGradient(
                    listOf(PANEL_TOP, PANEL_BOTTOM),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                )
            ),
    ) {
        RouteOutline(shape)
    }
}

/**
 * The shape alone.
 *
 * The start is marked so an out-and-back can be told from a loop run the other
 * way, and the joins are round because at this size a mitre on a switchback
 * throws a spike several pixels past the track.
 */
@Composable
private fun RouteOutline(shape: TrackShape) {
    val line = MaterialTheme.colorScheme.primary
    val start = Color.White

    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
        if (shape.points.size < 2) return@Canvas
        fun place(p: Pair<Float, Float>) = Offset(
            (shape.insetX + p.first * (1f - 2 * shape.insetX)) * size.width,
            (shape.insetY + p.second * (1f - 2 * shape.insetY)) * size.height,
        )

        val path = Path()
        shape.points.forEachIndexed { i, point ->
            val at = place(point)
            if (i == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        // A dark casing under the bright line: the panel is a gradient, so the
        // route crosses two rather different backgrounds on its way across.
        drawPath(
            path,
            color = CASING,
            style = Stroke(
                width = (STROKE + CASING_EXTRA).toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawPath(
            path,
            color = line,
            style = Stroke(
                width = STROKE.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawCircle(color = start, radius = DOT.toPx(), center = place(shape.points.first()))
    }
}

/**
 * "Today", "Yesterday", a weekday within the week, then a date.
 *
 * Relative reads faster for the rows people actually look at, and everything
 * older than a week is far enough back that the date is the useful form.
 */
private fun friendlyDate(iso: String): String {
    // The local day, not the UTC one the timestamp is stored in — see startedLocal.
    val date = com.tracks.app.ui.components.startedLocal(iso)?.toLocalDate()
        ?: runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
        ?: return iso.take(10)
    val today = LocalDate.now()
    val age = today.toEpochDay() - date.toEpochDay()
    return when {
        age == 0L -> "Today"
        age == 1L -> "Yesterday"
        age in 2..6 -> date.format(DateTimeFormatter.ofPattern("EEEE"))
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM"))
        else -> date.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

/**
 * The map's box: wider than it is tall, and as tall as the text beside it.
 *
 * Landscape because routes are: a valley ride is long and thin, and a square
 * spends half its pixels on empty ground either side.
 *
 * Only the width is fixed. The height is the row's, whatever the text and the
 * reader's font size make it, so the panel fills the card from edge to edge
 * without ever setting the row's height itself.
 */
private val THUMB_WIDTH = 92.dp
private val STROKE = 2.dp
private val CASING_EXTRA = 1.5.dp
private val DOT = 2.5.dp
private val CASING = Color(0x99000000)

/**
 * The panel, top-left to bottom-right.
 *
 * Alpha in both, deliberately — see [TrackThumbnail]. The top is a cool slate
 * rather than grey, which is what stops the panel reading as a dead area and
 * gives the orange route something to sit against.
 */
private val PANEL_TOP = Color(0xC4222831)
private val PANEL_BOTTOM = Color(0xF20A0C0F)

/**
 * Shown while activities are picked: how many, and the merge itself.
 *
 * Merging makes a trip — a grouping for looking back at a multi-day outing —
 * and never touches the activities, so there is no confirm step: undoing it is
 * deleting the trip.
 */
@Composable
private fun MergeBar(count: Int, onMerge: (String) -> Unit, onCancel: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        NeutralButton("Cancel", onClick = onCancel)
        PrimaryButton("Merge into trip", onClick = { naming = true }, enabled = count >= 2)
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name this trip") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                PrimaryButton("Merge", onClick = { naming = false; onMerge(name) }, enabled = name.isNotBlank())
            },
            dismissButton = { NeutralButton("Cancel", onClick = { naming = false }) },
        )
    }
}

/** A trip in the list: its name, span and totals. Long-press offers to delete it. */
@Composable
private fun TripRow(trip: TripSummary, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { confirm = true }),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(trip.name, style = MaterialTheme.typography.titleSmall)
            Text(
                buildList {
                    add("Trip · ${trip.activities.size} activities")
                    trip.startedAt?.let { add(friendlyDate(it)) }
                    trip.distanceMeters?.takeIf { it > 0 }?.let { add(distance(it)) }
                    trip.durationSeconds?.let { add(elapsed(it)) }
                }.joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this trip?") },
            text = { Text("Its activities stay; only the grouping goes.") },
            confirmButton = { DangerButton("Delete", onClick = { confirm = false; onDelete() }) },
            dismissButton = { NeutralButton("Cancel", onClick = { confirm = false }) },
        )
    }
}
