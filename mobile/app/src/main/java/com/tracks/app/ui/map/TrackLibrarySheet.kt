// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.app.AppContainer
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.core.api.CourseSummary
import com.tracks.core.api.Waypoint
import com.tracks.core.format.distance

/**
 * Everything the user has drawn, saved or brought back off the watch.
 *
 * ## Why this is a screen rather than a row of switches
 *
 * It began as a list with a "put this on the watch" toggle each, which answered
 * exactly one question and made every other one impossible: there was no way to
 * rename a track, no way to see what the watch was actually carrying without
 * reading each row's caption, and no way to send one thing without waiting for
 * a whole sync. Managing a library and flagging things for a device are the
 * same list viewed two ways, and [Scope] is that view rather than two screens.
 *
 * ## Flagged, sent, and the difference
 *
 * A switch sets what the user *wants*; the caption under each name says what is
 * actually on the watch. Those genuinely differ — the phone is usually nowhere
 * near the watch when somebody decides they want tomorrow's route on it — and
 * collapsing them into one indicator is what makes a sync feel like it lied.
 * "Send now" is the impatient path: it flags and then pushes over Bluetooth if
 * the watch happens to be in range, and says "queued" when it is not.
 *
 * ## Why removal reads differently for the two
 *
 * A Bluetooth link can write a file to a watch and cannot delete one. Removing
 * a place is therefore immediate — the locations file is rebuilt without it and
 * written — while removing a track is a tombstone the watch reads on its next
 * sync rather than an immediate write. The wording
 * differs because the truth does, and a uniform "removed" would be a promise
 * the user only discovers is empty on the trail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackLibrarySheet(
    container: AppContainer,
    courses: List<CourseSummary>,
    waypoints: List<Waypoint>,
    loading: Boolean,
    notice: String?,
    actions: LibraryActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasDevice = com.tracks.app.ui.components.LocalHasDevice.current
    var chosenScope by remember { mutableStateOf(Scope.Everything) }
    // With no watch there is no "on the watch" half to show.
    val scope = if (hasDevice) chosenScope else Scope.Everything
    // The same atlas the map and the legend draw from, so a place's symbol in
    // this list is the symbol it wears on the map.
    var sprite by remember { mutableStateOf<SpriteSheet?>(null) }
    LaunchedEffect(Unit) { sprite = SpriteSheet.load(container) }

    val shownCourses = courses.filter { scope.includes(it) }
    val shownWaypoints = waypoints.filter { scope.includes(it) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = SHEET_PAD).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            // Title, filter and import on one line. Three stacked blocks of
            // chrome above a list is most of a phone screen spent on furniture.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!hasDevice) Spacer(Modifier.weight(1f))
                if (hasDevice) SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    Scope.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = scope == option,
                            onClick = { chosenScope = option },
                            shape = SegmentedButtonDefaults.itemShape(index, Scope.entries.size),
                        ) {
                            Text(option.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                // Two ways in, and they are genuinely different sources: a file
                // somebody sent you, and the watch you are already wearing.
                if (hasDevice) IconButton(onClick = actions.onReadWatch, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Read the tracks and places already on the watch",
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(onClick = actions.onImport, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Import a GPX or FIT file",
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // The receipt for whatever was last asked for, and the count. These
            // operations all happen somewhere the user cannot see — a server
            // row, a watch — so without this, success and failure look alike.
            Text(
                notice ?: scope.summary(courses, waypoints),
                style = MaterialTheme.typography.labelSmall,
                color = if (notice != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { actions.onDismissNotice() },
            )

            if (loading && courses.isEmpty() && waypoints.isEmpty()) {
                Text("Loading…", style = MaterialTheme.typography.labelSmall)
                return@Column
            }

            LazyColumn(
                Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { Header("Tracks") }
                if (shownCourses.isEmpty()) {
                    item { Empty(scope.emptyTracks) }
                } else {
                    items(shownCourses, key = { "course-${it.id}" }) { course ->
                        CourseRow(course, sprite, actions)
                    }
                }

                item { Divider(Modifier.padding(vertical = 5.dp)) }

                item { Header("Waypoints") }
                if (shownWaypoints.isEmpty()) {
                    item { Empty(scope.emptyPlaces) }
                } else {
                    items(shownWaypoints, key = { "waypoint-${it.id}" }) { waypoint ->
                        WaypointRow(waypoint, sprite, actions)
                    }
                }
            }
        }
    }
}

/**
 * Which half of the list to show.
 *
 * "On the watch" deliberately includes things merely queued for it. Somebody
 * checking this before walking out of the door is asking "will I have this with
 * me", and a queued track answers yes — the caption on the row is where the
 * distinction between queued and carried belongs.
 */
enum class Scope(val label: String) {
    Everything("Everything"),
    OnTheWatch("On the watch"),
    ;

    fun includes(course: CourseSummary): Boolean =
        this == Everything || course.loadToDevice || course.isExternal

    fun includes(waypoint: Waypoint): Boolean =
        this == Everything || waypoint.loadToDevice || waypoint.onWatch

    val emptyTracks: String
        get() = when (this) {
            Everything -> "None yet — long-press the map to draw one."
            OnTheWatch -> "None on the watch."
        }

    val emptyPlaces: String
        get() = when (this) {
            Everything -> "None yet — tap a spot and save it."
            OnTheWatch -> "None on the watch."
        }

    fun summary(courses: List<CourseSummary>, waypoints: List<Waypoint>): String {
        val tracks = courses.count { includes(it) }
        val places = waypoints.count { includes(it) }
        val suffix = if (this == OnTheWatch) " on the watch" else ""
        return "${count(tracks, "track")}, ${count(places, "waypoint")}$suffix"
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}

/**
 * What the sheet can do, gathered rather than passed as eleven lambdas.
 *
 * Eleven parameters is where a composable stops being readable and starts being
 * a form somebody fills in wrong.
 */
data class LibraryActions(
    val onCourseSendNow: (CourseSummary) -> Unit,
    val onCourseRemove: (CourseSummary) -> Unit,
    val onCourseColor: (CourseSummary, String) -> Unit,
    val onCourseShow: (CourseSummary) -> Unit,
    val onCourseRename: (CourseSummary, String) -> Unit,
    val onCourseDelete: (CourseSummary) -> Unit,
    val onWaypointSendNow: (Waypoint) -> Unit,
    val onWaypointRemove: (Waypoint) -> Unit,
    val onWaypointStyle: (Waypoint, String?, String?) -> Unit,
    val onWaypointShow: (Waypoint) -> Unit,
    val onWaypointRename: (Waypoint, String) -> Unit,
    val onWaypointDelete: (Waypoint) -> Unit,
    val onImport: () -> Unit,
    /** Pull the courses and saved places the watch is already carrying. */
    val onReadWatch: () -> Unit,
    val onDismissNotice: () -> Unit,
)

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CourseRow(course: CourseSummary, sprite: SpriteSheet?, actions: LibraryActions) {
    var editing by remember { mutableStateOf(false) }

    Column {
        ItemHeader(
            color = course.color,
            icon = null,
            sprite = sprite,
            name = course.name,
            caption = buildString {
                append(distance(course.distanceMetres))
                if (course.ascentMetres > 0) append(" ↑${course.ascentMetres.toInt()}m")
                if (com.tracks.app.ui.components.LocalHasDevice.current) append(" · ${deviceLabel(course)}")
            },
            expanded = editing,
            onToggle = { editing = !editing },
        )
        if (editing) CourseActions(course, actions)
    }
}

/**
 * Everything you can do to a track, in one block.
 *
 * Shared by the list row and the sheet that opens when you tap the track on the
 * map: two places offering different subsets of the same actions is how a user
 * learns to distrust both.
 */
@Composable
internal fun CourseActions(
    course: CourseSummary,
    actions: LibraryActions,
    /** False where the surface already shows an editable name — see [SelectionSheet]. */
    withName: Boolean = true,
) {
    var picking by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        if (withName) {
            // The colour sits where the name is, and is tapped the same way.
            // A palette permanently open under every row was ten swatches
            // repeated down the sheet for a decision made once per track.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorDot(course.color) { picking = true }
                EditableName(
                    name = course.name,
                    style = MaterialTheme.typography.bodyMedium,
                    onRename = { actions.onCourseRename(course, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ActionRow(
            // A course discovered on the watch is already there and Tracks did
            // not author its file; offering to send it back would be a no-op
            // wearing a convincing button. One drawn or imported offline and
            // not yet saved is the same story from the other end: the watch
            // push list lives on the server, so there is nothing to send
            // until the server has heard of this track at all.
            canSend = course.id >= 0 && !course.isExternal && !course.loadToDevice,
            canRemove = course.id >= 0 && (course.loadToDevice || course.isExternal),
            onSend = { actions.onCourseSendNow(course) },
            onRemove = { actions.onCourseRemove(course) },
            onShow = { actions.onCourseShow(course) },
            onDelete = { actions.onCourseDelete(course) },
        )
    }

    if (picking) {
        ColorPickerDialog(
            current = course.color,
            title = "Track colour",
            onPick = { actions.onCourseColor(course, it) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun WaypointRow(waypoint: Waypoint, sprite: SpriteSheet?, actions: LibraryActions) {
    var editing by remember { mutableStateOf(false) }

    Column {
        ItemHeader(
            color = waypoint.color,
            icon = waypoint.icon,
            sprite = sprite,
            name = waypoint.name,
            caption = deviceLabel(waypoint).takeIf { com.tracks.app.ui.components.LocalHasDevice.current }.orEmpty(),
            expanded = editing,
            onToggle = { editing = !editing },
        )
        if (editing) WaypointActions(waypoint, sprite, actions)
    }
}

/** Everything you can do to a place — see [CourseActions] for why it is shared. */
@Composable
internal fun WaypointActions(
    waypoint: Waypoint,
    sprite: SpriteSheet?,
    actions: LibraryActions,
    /** See [CourseActions]. */
    withName: Boolean = true,
) {
    var picking by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        if (withName) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorDot(waypoint.color) { picking = true }
                EditableName(
                    name = waypoint.name,
                    style = MaterialTheme.typography.bodyMedium,
                    onRename = { actions.onWaypointRename(waypoint, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // The symbol stays open. Unlike the palette it is not a repeated copy
        // of one control — the chosen symbol IS the row's identity on the map,
        // and eighteen tiles in two rows is the whole set.
        IconRow(sprite = sprite, selected = waypoint.icon) {
            actions.onWaypointStyle(waypoint, null, it)
        }
        ActionRow(
            // See the note on the track's own [ActionRow]: nothing local can
            // be pushed until the server knows it exists.
            canSend = waypoint.id >= 0 && !waypoint.loadToDevice,
            canRemove = waypoint.id >= 0 && (waypoint.loadToDevice || waypoint.onWatch),
            onSend = { actions.onWaypointSendNow(waypoint) },
            onRemove = { actions.onWaypointRemove(waypoint) },
            onShow = { actions.onWaypointShow(waypoint) },
            onDelete = { actions.onWaypointDelete(waypoint) },
        )
    }

    if (picking) {
        ColorPickerDialog(
            current = waypoint.color,
            title = "Place colour",
            onPick = { actions.onWaypointStyle(waypoint, it, null) },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The row you scan, at the size you scan it.
 *
 * A waypoint shows its own symbol on its own colour, which is exactly what it
 * looks like on the map — so finding the row for the dot you are looking at is
 * a matter of matching, not of reading names.
 */
@Composable
private fun ItemHeader(
    color: String,
    icon: String?,
    sprite: SpriteSheet?,
    name: String,
    caption: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Marker(color = color, icon = icon, sprite = sprite)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption.isNotEmpty()) Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Hide actions" else "Show actions",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A place or track as it appears on the map: its colour, wearing its symbol. */
@Composable
internal fun Marker(
    color: String,
    icon: String?,
    sprite: SpriteSheet?,
    /** Set where the marker is also the colour control — see [ColorPickerDialog]. */
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val tile = MaterialTheme.colorScheme.surface.toArgb()
    val tint = parseHex(color)
    val image = icon?.let { spriteFor(it) }?.let { sprite?.icon(it) }

    Box(
        Modifier
            .size(22.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // The symbol itself, in the place's colour and on nothing —
            // which is what the map draws now that these can be tinted. The
            // disc that used to sit behind it was a workaround for a symbol
            // that could not take a colour, and a row still showing one would
            // be a legend for a map that no longer looks like that.
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(18.dp),
            )

            // A track has no symbol; its colour is the line's, and a dot is the
            // usual way a legend stands in for a line.
            icon == null -> Box(Modifier.size(14.dp).background(tint, CircleShape))

            // A place with no particular symbol wears the pin, as on the map.
            else -> Image(
                bitmap = remember(color) {
                    WaypointPins.bitmap(context, color, tile).asImageBitmap()
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Where this stands with the watch, in the words somebody checks before
 * leaving.
 *
 * The server has its own vocabulary for a track (`pending_upload`,
 * `on_device`), which is precise and unreadable; this translates it once so the
 * two lists say the same thing about the same situation.
 *
 * A negative id checked first, ahead of everything else: it means this row is
 * a promise the phone made to itself and has not told the server about yet,
 * so none of the watch vocabulary applies — there is nothing for the server
 * to have queued, delivered, or forgotten, because the server does not know
 * this row exists.
 */
internal fun deviceLabel(course: CourseSummary): String = when {
    course.id < 0 -> "not yet saved"
    course.isExternal -> "from the watch"
    course.deviceStatus == "on_device" -> "on the watch"
    course.deviceStatus == "pending_upload" -> "queued"
    course.deviceStatus == "pending_remove" -> "coming off"
    else -> "not on the watch"
}

internal fun deviceLabel(waypoint: Waypoint): String = when {
    waypoint.id < 0 -> "not yet saved"
    waypoint.onWatch && waypoint.loadToDevice -> "on the watch"
    waypoint.onWatch -> "coming off"
    waypoint.loadToDevice -> "queued"
    else -> "not on the watch"
}

/**
 * Send, remove, show, delete — one row instead of four stacked blocks.
 *
 * This was a full-width button pair, a text button and a confirm pair, three
 * rows deep under every expanded item. On a phone that is most of the sheet
 * spent on one row's actions, which is why opening two items at once used to be
 * unusable.
 */
@Composable
private fun ActionRow(
    canSend: Boolean,
    canRemove: Boolean,
    onSend: () -> Unit,
    onRemove: () -> Unit,
    onShow: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (confirming) {
            Text(
                "Delete for good?",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            NeutralButton("Keep", onClick = { confirming = false }, small = true)
            DangerButton("Delete", onClick = { confirming = false; onDelete() }, small = true)
            return@Row
        }

        // "Send" and "Remove" alone left the destination to be inferred,
        // next to a Delete button that removes it from Tracks entirely —
        // three buttons where two act on the watch and one does not.
        if (com.tracks.app.ui.components.LocalHasDevice.current) {
            TonalButton("Send to device", onClick = onSend, modifier = Modifier.weight(1f), enabled = canSend, small = true)
            NeutralButton("Remove from device", onClick = onRemove, modifier = Modifier.weight(1f), enabled = canRemove, small = true)
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onShow, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Show on map",
                modifier = Modifier.size(17.dp),
            )
        }
        IconButton(onClick = { confirming = true }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Rename, committed on the tick rather than per keystroke.
 *
 * Every character would otherwise be a PATCH, and the intermediate names get
 * written to the watch's file on the next sync.
 */
@Composable
internal fun EditableName(
    name: String,
    style: androidx.compose.ui.text.TextStyle,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(name) { mutableStateOf(false) }
    var draft by remember(name) { mutableStateOf(name) }
    val focus = remember { FocusRequester() }

    fun commit() {
        val trimmed = draft.trim()
        // A blank name is a rename nobody meant; the old one stands.
        if (trimmed.isNotEmpty() && trimmed != name) onRename(trimmed)
        editing = false
    }

    if (!editing) {
        Row(
            modifier.clickable { draft = name; editing = true },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Wraps. A track called "Blue Lakes via the north approach" is a
            // name somebody chose, and a sheet is wide enough to read it on two
            // lines — truncating it here would hide the half that distinguishes
            // it from the other three routes up the same hill.
            Text(name, style = style, modifier = Modifier.weight(1f, fill = false))
            Icon(
                Icons.Filled.Create,
                contentDescription = "Rename",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp).padding(top = 2.dp),
            )
        }
        return
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        // Not single-line, for the same reason the label above wraps: the
        // editor should show the whole name being edited rather than scroll a
        // long one sideways past a window three words wide.
        singleLine = false,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            // Committed on the way out as well as on Done: tapping elsewhere in
            // the sheet is how people finish typing, and losing the edit at
            // that point would be losing work for no reason.
            .onFocusChanged { if (!it.isFocused && editing) commit() },
    )
}
