// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
    /** Tracks' collections, each a collection of its own on the watch. */
    collections: List<com.tracks.core.api.TrackCollection> = emptyList(),
    /** Rows just taken off the watch, hidden from "On the watch" until it answers. */
    leaving: Set<String> = emptySet(),
    loading: Boolean,
    notice: String?,
    actions: LibraryActions,
    onDismiss: () -> Unit,
    /** The panel's share of the screen, kept between openings. */
    fraction: Float,
    onFraction: (Float) -> Unit,
    /** The row to open with, as "course-<id>" — the track just edited. */
    initiallyOpen: String? = null,
) {
    val hasDevice = com.tracks.app.ui.components.LocalHasDevice.current
    var chosenScope by remember { mutableStateOf(Scope.Everything) }
    // With no watch there is no "on the watch" half to show.
    val scope = if (hasDevice) chosenScope else Scope.Everything
    // The same atlas the map and the legend draw from, so a place's symbol in
    // this list is the symbol it wears on the map.
    var sprite by remember { mutableStateOf<SpriteSheet?>(null) }
    LaunchedEffect(Unit) { sprite = SpriteSheet.load(container) }

    val hide = { key: String -> scope == Scope.OnTheWatch && key in leaving }
    val context = LocalContext.current
    var sort by remember { mutableStateOf(LibrarySort.load(context)) }
    val here = remember(sort) { if (sort == LibrarySort.Nearest) LibrarySort.lastKnown(context) else null }
    val shownCourses = sort.courses(courses.filter { scope.includes(it) && !hide("course-${it.id}") }, here)
    val shownWaypoints = sort.places(waypoints.filter { scope.includes(it) && !hide("waypoint-${it.id}") }, here)
    // Long-press starts picking several rows; their keys, as "course-<id>".
    var picked by remember { mutableStateOf(setOf<String>()) }
    val picking = picked.isNotEmpty()
    fun togglePick(key: String) { picked = if (key in picked) picked - key else picked + key }
    // One row open at a time, across both tabs: opening another closes this.
    var open by remember(initiallyOpen) { mutableStateOf(initiallyOpen) }
    var moving by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    // An item filed under a collection this phone has not got (deleted
    // elsewhere, not yet synced) shows in the default, where it will land.
    val known = collections.mapTo(HashSet()) { it.id }
    fun groupOf(folderId: Int?): Int? = folderId?.takeIf { it in known }

    ResizableSheet(fraction = fraction, onFraction = onFraction, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = SHEET_PAD).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            // Title, filter and import on one line. Three stacked blocks of
            // chrome above a list is most of a phone screen spent on furniture.
            if (picking) {
                SelectionBar(
                    count = picked.size,
                    hasDevice = hasDevice,
                    onSend = {
                        courses.filter { "course-${it.id}" in picked && !it.loadToDevice }.forEach(actions.onCourseSendNow)
                        waypoints.filter { "waypoint-${it.id}" in picked && !it.loadToDevice }.forEach(actions.onWaypointSendNow)
                        picked = emptySet()
                    },
                    onRemove = {
                        courses.filter { "course-${it.id}" in picked && it.loadToDevice }.forEach(actions.onCourseRemove)
                        waypoints.filter { "waypoint-${it.id}" in picked && it.loadToDevice }.forEach(actions.onWaypointRemove)
                        picked = emptySet()
                    },
                    onDelete = {
                        courses.filter { "course-${it.id}" in picked }.forEach(actions.onCourseDelete)
                        waypoints.filter { "waypoint-${it.id}" in picked }.forEach(actions.onWaypointDelete)
                        picked = emptySet()
                    },
                    onDone = { picked = emptySet() },
                    onMove = { moving = true },
                    // In list order: what the person sees is the order they meant.
                    onCombine = shownCourses.filter { "course-${it.id}" in picked }
                        .takeIf { it.size >= 2 }
                        ?.let { chosen -> { actions.onCombine(chosen); picked = emptySet() } },
                )
            } else Row(
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
                            // Material's 40 dp is tuned for a page; in a panel
                            // the list is what the height is for.
                            modifier = Modifier.height(30.dp),
                            icon = {},
                        ) {
                            Text(option.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                // Two ways in, and they are genuinely different sources: a file
                // somebody sent you, and the watch you are already wearing.
                if (hasDevice) IconButton(onClick = actions.onReadWatch, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Read the tracks and places already on the watch",
                        modifier = Modifier.size(19.dp),
                    )
                }
                androidx.compose.material3.TextButton(
                    onClick = { sort = sort.next().also { it.save(context) } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(sort.label, style = MaterialTheme.typography.labelLarge)
                }
                // Says what it imports: a bare plus read as "new track".
                androidx.compose.material3.TextButton(
                    onClick = actions.onImport,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text("+GPX", style = MaterialTheme.typography.labelLarge)
                }
            }

            // The receipt for whatever was last asked for, and the count. These
            // operations all happen somewhere the user cannot see — a server
            // row, a watch — so without this, success and failure look alike.
            // Receipts for watch and sync work are toasts over the map now
            // (see StatusToast); this line is only the count.
            Text(
                // While picking, the count of picked rows rides here: the bar
                // above is all buttons, and squeezed in there "1 selected"
                // wrapped one syllable to a line.
                scope.summary(courses, waypoints) + if (picking) " · ${picked.size} selected" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (loading && courses.isEmpty() && waypoints.isEmpty()) {
                Text("Loading…", style = MaterialTheme.typography.labelSmall)
                return@Column
            }

            // Reopened on a track just edited: bring its row into view.
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(initiallyOpen) {
                val index = shownCourses.indexOfFirst { "course-${it.id}" == initiallyOpen }
                if (index >= 0) listState.scrollToItem(index + 1)
            }
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (shownCourses.isEmpty() && shownWaypoints.isEmpty()) {
                    item { Empty(scope.emptyTracks) }
                }
                // One section per collection, the default first. A collection
                // with nothing in this view is shown only under "Everything",
                // where a new, empty one has to be visible to be filled.
                val sections = listOf<com.tracks.core.api.TrackCollection?>(null) +
                    collections.sortedWith(compareBy(NaturalOrder) { it.name })
                for (section in sections) {
                    val id = section?.id
                    val sCourses = shownCourses.filter { groupOf(it.folderId) == id }
                    val sPlaces = shownWaypoints.filter { groupOf(it.folderId) == id }
                    if (sCourses.isEmpty() && sPlaces.isEmpty() && (scope != Scope.Everything || section == null)) continue
                    item(key = "collection-${id ?: "default"}") {
                        CollectionHeader(
                            name = section?.name ?: DEFAULT_COLLECTION,
                            count = "${sCourses.size} track${if (sCourses.size == 1) "" else "s"} · " +
                                "${sPlaces.size} place${if (sPlaces.size == 1) "" else "s"}",
                            // A named collection is on the watch whole or not
                            // at all; the default's items are each their own call.
                            onTheWatch = if (hasDevice) section?.loadToDevice else null,
                            onWatch = { on -> actions.onCollectionOnWatch(id, on) },
                            onRename = section?.let { c -> { name: String -> actions.onCollectionRename(c.id, name) } },
                            onDelete = section?.let { c -> { actions.onCollectionDelete(c.id) } },
                        )
                    }
                    items(sCourses, key = { "course-${it.id}" }) { course ->
                        val key = "course-${course.id}"
                        PickableRow(picking, key in picked, onPick = { togglePick(key) }) {
                            CourseRow(course, sprite, actions, open == key && !picking, onLongPress = { togglePick(key) }) {
                                if (picking) togglePick(key) else open = if (open == key) null else key
                            }
                        }
                    }
                    items(sPlaces, key = { "waypoint-${it.id}" }) { waypoint ->
                        val key = "waypoint-${waypoint.id}"
                        PickableRow(picking, key in picked, onPick = { togglePick(key) }) {
                            WaypointRow(waypoint, sprite, actions, open == key && !picking, onLongPress = { togglePick(key) }) {
                                if (picking) togglePick(key) else open = if (open == key) null else key
                            }
                        }
                    }
                    item(key = "gap-${id ?: "default"}") { Divider(Modifier.padding(vertical = 5.dp)) }
                }
                item(key = "new-collection") {
                    androidx.compose.material3.TextButton(onClick = { naming = true }) {
                        Text("+ New collection", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (moving) {
        MovePicker(
            collections = collections,
            onPick = { target ->
                actions.onMove(
                    courses.filter { "course-${it.id}" in picked }.map { it.id },
                    waypoints.filter { "waypoint-${it.id}" in picked }.map { it.id },
                    target,
                )
                picked = emptySet()
                moving = false
            },
            onNew = { moving = false; naming = true },
            onDismiss = { moving = false },
        )
    }
    if (naming) {
        NameDialog(
            title = "New collection",
            onSave = { actions.onCollectionCreate(it); naming = false },
            onDismiss = { naming = false },
        )
    }
}

/**
 * Which collection an item is in, as a drop-down: "Tracks", every collection,
 * and a new one (named, then the item filed in it). The label sits above the
 * box, and the two together take the height of the editor's name field they
 * stand beside.
 */
@Composable
internal fun CollectionDropdown(
    current: Int?,
    collections: List<com.tracks.core.api.TrackCollection>,
    onPick: (Int?) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val here = collections.firstOrNull { it.id == current }
    Box(modifier.height(COLLECTION_FIELD_HEIGHT)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Collection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier
                    .weight(1f)
                    .widthIn(min = 96.dp, max = 160.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .clickable { open = true }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    here?.name ?: DEFAULT_COLLECTION, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose a collection", modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf<com.tracks.core.api.TrackCollection?>(null).plus(collections.sortedWith(compareBy(NaturalOrder) { it.name })).forEach { c ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(c?.name ?: DEFAULT_COLLECTION, fontWeight = if (c?.id == here?.id) FontWeight.SemiBold else null) },
                    onClick = { open = false; if (c?.id != here?.id) onPick(c?.id) },
                )
            }
            Divider()
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("+ New collection") },
                onClick = { open = false; naming = true },
            )
        }
    }
    if (naming) {
        NameDialog(title = "New collection", onSave = { onCreate(it); naming = false }, onDismiss = { naming = false })
    }
}

/** An outlined text field's height, which the collection picker beside one matches. */
private val COLLECTION_FIELD_HEIGHT = 56.dp

/** The collection everything is in until it is filed elsewhere. */
const val DEFAULT_COLLECTION = "Tracks"

/**
 * A collection's heading: its name (tap to rename, except the default), what
 * is in it, and — for a named collection — one switch that keeps everything in
 * it on the watch, or none of it. [onTheWatch] is null for no switch.
 */
@Composable
private fun CollectionHeader(
    name: String,
    count: String,
    onTheWatch: Boolean?,
    onWatch: (Boolean) -> Unit,
    onRename: ((String) -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var confirming by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            if (onRename != null) {
                EditableName(name = name, style = MaterialTheme.typography.titleSmall, onRename = onRename)
            } else {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (confirming && onDelete != null) {
            NeutralButton("Keep", onClick = { confirming = false }, small = true)
            DangerButton("Delete", onClick = { confirming = false; onDelete() }, small = true)
            return@Row
        }
        onTheWatch?.let { on ->
            Text("Watch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.tracks.app.ui.components.TracksSwitch(checked = on, onCheckedChange = onWatch)
        }
        if (onDelete != null) {
            IconButton(onClick = { confirming = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    // Says what happens to the contents, which is the question.
                    contentDescription = "Delete collection; its tracks and places move to $DEFAULT_COLLECTION",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where to file the picked rows. */
@Composable
private fun MovePicker(
    collections: List<com.tracks.core.api.TrackCollection>,
    onPick: (Int?) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            Column {
                listOf<com.tracks.core.api.TrackCollection?>(null).plus(collections).forEach { c ->
                    androidx.compose.material3.TextButton(onClick = { onPick(c?.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(c?.name ?: DEFAULT_COLLECTION, Modifier.fillMaxWidth())
                    }
                }
                androidx.compose.material3.TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Text("+ New collection", Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NameDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Which half of the list to show.
 *
 * "On the watch" is what the watch has confirmed it holds — read out of the
 * watch's own collection, not assumed from a transfer (see
 * `com.tracks.device.garmin.explore.ExploreSession`). That includes a track
 * still coming off, because it is still there. It excludes tracks once pulled
 * *from* the watch: those were a snapshot of an earlier watch, and listing
 * them here filled the tab with courses that had long since gone.
 */
enum class Scope(val label: String) {
    Everything("Everything"),
    OnTheWatch("On the watch"),
    ;

    fun includes(course: CourseSummary): Boolean =
        this == Everything || course.deviceStatus in setOf("on_device", "pending_remove")

    fun includes(waypoint: Waypoint): Boolean =
        this == Everything || waypoint.onWatch

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
    /** Open the track in the editor: line, name, colour, all in one place. */
    val onCourseEditLine: (CourseSummary) -> Unit,
    val onCourseExport: (CourseSummary) -> Unit,
    /** Open the place in the editor: position, name, colour, symbol. */
    val onWaypointEdit: (Waypoint) -> Unit,
    val onWaypointExport: (Waypoint) -> Unit,
    /** Join tracks, in the order given, into one new track. */
    val onCombine: (List<CourseSummary>) -> Unit = {},
    /** Finger on the open track's height profile: metres along it, or null. */
    val onProfileScrub: (Double?) -> Unit = {},
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
    /** Open a recorded activity's own screen, by its id there. */
    val onOpenActivity: (Int) -> Unit = {},
    val onMakeTrack: (Selection.Activity) -> Unit = {},
    val onCollectionCreate: (String) -> Unit = {},
    val onCollectionRename: (Int, String) -> Unit = { _, _ -> },
    val onCollectionDelete: (Int) -> Unit = {},
    /** Everything in a collection on or off the watch; null is the default. */
    val onCollectionOnWatch: (Int?, Boolean) -> Unit = { _, _ -> },
    /** File these tracks and places under a collection; null is the default. */
    val onMove: (List<Int>, List<Int>, Int?) -> Unit = { _, _, _ -> },
    /** Make a collection of this name and file these tracks and places in it. */
    val onNewCollectionFor: (String, List<Int>, List<Int>) -> Unit = { _, _, _ -> },
    /** Every collection, for the pickers. */
    val collections: List<com.tracks.core.api.TrackCollection> = emptyList(),
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
private fun CourseRow(
    course: CourseSummary,
    sprite: SpriteSheet?,
    actions: LibraryActions,
    editing: Boolean,
    onLongPress: () -> Unit = {},
    onToggle: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        ItemHeader(
            onLongPress = onLongPress,
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
            // Opening a track is looking at it: the map goes there at once,
            // which is what the magnifier button used to be for.
            onToggle = {
                if (!editing) actions.onCourseShow(course)
                onToggle()
            },
            onRename = { actions.onCourseRename(course, it) },
        )
        // The name is not repeated below the header: the header's own name is
        // the editable one while the row is open.
        if (editing) CourseActions(course, actions, withName = false)
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
            // One button, whose job follows whether Tracks wants the track on
            // the watch. Reliable now that "on the watch" is the watch's own
            // word: the caption says whether it has confirmed yet.
            //
            // A track once pulled off a watch is an ordinary Tracks track now:
            // sending it puts Tracks' own copy in Tracks' collection, which
            // Tracks can then take off again. None for one not yet saved: there
            // is no id to put on the watch.
            onWatch = course.loadToDevice,
            canToggle = course.id >= 0,
            onSend = { actions.onCourseSendNow(course) },
            onRemove = { actions.onCourseRemove(course) },
            onDelete = { actions.onCourseDelete(course) },
            deleteQuestion = if (course.loadToDevice) "Delete from Tracks and the watch?" else "Delete for good?",
            onEdit = { actions.onCourseEditLine(course) }.takeIf { course.id >= 0 },
            onExport = { actions.onCourseExport(course) },
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
private fun WaypointRow(
    waypoint: Waypoint,
    sprite: SpriteSheet?,
    actions: LibraryActions,
    editing: Boolean,
    onLongPress: () -> Unit = {},
    onToggle: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        ItemHeader(
            onLongPress = onLongPress,
            color = waypoint.color,
            icon = waypoint.icon,
            sprite = sprite,
            name = waypoint.name,
            caption = deviceLabel(waypoint).takeIf { com.tracks.app.ui.components.LocalHasDevice.current }.orEmpty(),
            expanded = editing,
            onToggle = {
                if (!editing) actions.onWaypointShow(waypoint)
                onToggle()
            },
            onRename = { actions.onWaypointRename(waypoint, it) },
        )
        if (editing) WaypointActions(waypoint, sprite, actions, withName = false)
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
        // Symbol and colour are changed in the editor (Edit), with the
        // position and name — one place for everything about the place.
        ActionRow(
            // See the note on the track's own [ActionRow].
            onWatch = waypoint.loadToDevice,
            canToggle = waypoint.id >= 0,
            onSend = { actions.onWaypointSendNow(waypoint) },
            onRemove = { actions.onWaypointRemove(waypoint) },
            onDelete = { actions.onWaypointDelete(waypoint) },
            deleteQuestion = if (waypoint.loadToDevice) "Delete from Tracks and the watch?" else "Delete for good?",
            onEdit = { actions.onWaypointEdit(waypoint) }.takeIf { waypoint.id >= 0 },
            onExport = { actions.onWaypointExport(waypoint) },
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ItemHeader(
    color: String,
    icon: String?,
    sprite: SpriteSheet?,
    name: String,
    caption: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** While open, the name itself is the rename control, so it is not shown twice. */
    onRename: ((String) -> Unit)? = null,
    /** While open, the marker is the colour control. */
    onColor: (() -> Unit)? = null,
    /** Long press: start picking several rows. */
    onLongPress: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onToggle, onLongClick = onLongPress).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // As tall as the row's text: the symbol is how a row is found.
        Marker(color = color, icon = icon, sprite = sprite, onClick = onColor.takeIf { expanded }, size = 36.dp)
        Column(Modifier.weight(1f)) {
            if (expanded && onRename != null) {
                // Larger while open: this is the row being worked on.
                EditableName(name = name, style = MaterialTheme.typography.titleMedium, onRename = onRename)
            } else Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
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
    /** Set where the marker is also a control. */
    onClick: (() -> Unit)? = null,
    size: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val context = LocalContext.current
    val tile = MaterialTheme.colorScheme.surface.toArgb()
    val tint = parseHex(color)
    // The outline the map gives its symbols, in the theme's own ink: light on
    // a dark theme, dark on a light one. A black symbol on a dark sheet was
    // invisible without it.
    val halo = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    val image = icon?.let { spriteFor(it) }?.let { sprite?.icon(it) }
    val glyph = size * 0.82f
    val ring = 1.5.dp

    Box(
        Modifier
            .size(size)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> {
                for ((dx, dy) in HALO_OFFSETS) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(halo),
                        modifier = Modifier.size(glyph).offset(ring * dx, ring * dy),
                    )
                }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(tint),
                    modifier = Modifier.size(glyph),
                )
            }

            // A track has no symbol; its colour is the line's, and a dot is the
            // usual way a legend stands in for a line — ringed, like a symbol.
            icon == null -> Box(
                Modifier
                    .size(size * 0.62f)
                    .background(halo, CircleShape)
                    .padding(ring)
                    .background(tint, CircleShape),
            )

            // A place with no particular symbol wears the pin, as on the map;
            // the pin bitmap carries its own white edge.
            else -> Image(
                bitmap = remember(color) {
                    WaypointPins.bitmap(context, color, tile).asImageBitmap()
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.9f),
            )
        }
    }
}

/** The eight directions the outline is drawn in, around the symbol. */
private val HALO_OFFSETS = listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f, -0.7f to -0.7f, 0.7f to -0.7f, -0.7f to 0.7f, 0.7f to 0.7f)

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
    course.deviceStatus == "on_device" -> "on the watch"
    course.deviceStatus == "pending_upload" -> "waiting for the watch"
    course.deviceStatus == "pending_remove" -> "coming off"
    // Pulled off a watch at some point: a copy, not a statement about what the
    // watch holds now.
    course.isExternal -> "imported from a watch"
    else -> "not on the watch"
}

internal fun deviceLabel(waypoint: Waypoint): String = when {
    waypoint.id < 0 -> "not yet saved"
    waypoint.onWatch && waypoint.loadToDevice -> "on the watch"
    waypoint.onWatch -> "coming off"
    waypoint.loadToDevice -> "waiting for the watch"
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
    /** Whether Tracks wants this on the watch — decides what the one button does. */
    onWatch: Boolean,
    canToggle: Boolean,
    onSend: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    deleteQuestion: String,
    /** Open the editor. Shares the row with send/remove, export and delete. */
    onEdit: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
) {
    var confirming by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (confirming) {
            Text(deleteQuestion, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            NeutralButton("Keep", onClick = { confirming = false })
            DangerButton("Delete", onClick = { confirming = false; onDelete() })
            return@Row
        }

        if (com.tracks.app.ui.components.LocalHasDevice.current && canToggle) {
            // Short enough to fit beside Edit at any width: "Remove from
            // device" was cut to "Remove from" on most phones.
            if (onWatch) {
                NeutralButton("Off watch", onClick = onRemove, modifier = Modifier.weight(1f).heightIn(min = 44.dp))
            } else {
                TonalButton("To watch", onClick = onSend, modifier = Modifier.weight(1f).heightIn(min = 44.dp))
            }
        } else if (onEdit == null) {
            Spacer(Modifier.weight(1f))
        }
        if (onEdit != null) {
            NeutralButton("Edit", onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = 44.dp))
        }
        if (onExport != null) {
            IconButton(onClick = onExport, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "Export as GPX",
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        IconButton(onClick = { confirming = true }, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
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
    var draft by remember(name) {
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(name, androidx.compose.ui.text.TextRange(name.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // onFocusChanged reports "not focused" once before focus arrives; acting
    // on that closed the editor the instant it opened, so the keyboard never
    // came up. Only a loss of focus the field actually had commits.
    var hadFocus by remember(name) { mutableStateOf(false) }

    fun commit() {
        val trimmed = draft.text.trim()
        // A blank name is a rename nobody meant; the old one stands.
        if (trimmed.isNotEmpty() && trimmed != name) onRename(trimmed)
        editing = false
    }

    if (!editing) {
        Row(
            modifier.clickable {
                draft = androidx.compose.ui.text.input.TextFieldValue(name, androidx.compose.ui.text.TextRange(name.length))
                hadFocus = false
                editing = true
            },
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

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
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
            .onFocusChanged {
                if (it.isFocused) hadFocus = true
                else if (hadFocus && editing) commit()
            },
    )
}

/**
 * A row that can be picked: a long press starts picking, and while picking a
 * check box sits in front of it. The row's own tap is routed by its caller.
 */
@Composable
private fun PickableRow(picking: Boolean, picked: Boolean, onPick: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (picking) {
            androidx.compose.material3.Checkbox(checked = picked, onCheckedChange = { onPick() })
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** What to do with every picked row at once. */
@Composable
private fun SelectionBar(
    count: Int,
    hasDevice: Boolean,
    onSend: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onMove: () -> Unit,
    /** Shown when two or more tracks are picked. */
    onCombine: (() -> Unit)? = null,
) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (confirming) {
            Text("Delete $count for good?", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            NeutralButton("Keep", onClick = { confirming = false })
            DangerButton("Delete", onClick = { confirming = false; onDelete() })
            return@Row
        }
        Spacer(Modifier.weight(1f))
        onCombine?.let { NeutralButton("Combine", onClick = it, small = true) }
        NeutralButton("Move", onClick = onMove, small = true)
        if (hasDevice) {
            TonalButton("To watch", onClick = onSend, small = true)
            NeutralButton("Off watch", onClick = onRemove, small = true)
        }
        IconButton(onClick = { confirming = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
        }
        IconButton(onClick = onDone, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Stop selecting")
        }
    }
}

/**
 * Names in the order a person reads them: case aside, and a run of digits by
 * its value, so "Trail 2" comes before "Trail 10" rather than after "Trail 19".
 */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val ea = run { var k = i; while (k < a.length && a[k].isDigit()) k++; k }
                val eb = run { var k = j; while (k < b.length && b[k].isDigit()) k++; k }
                val na = a.substring(i, ea).trimStart('0')
                val nb = b.substring(j, eb).trimStart('0')
                // Longer (zero-stripped) runs are bigger numbers; equal lengths compare digit by digit.
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ea; j = eb
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** How the list is ordered; remembered between openings. */
enum class LibrarySort(val label: String) {
    Name("A–Z"),
    Newest("Newest"),
    Nearest("Nearest"),
    ;

    fun next(): LibrarySort = entries[(ordinal + 1) % entries.size]

    fun courses(list: List<CourseSummary>, here: Pair<Double, Double>?): List<CourseSummary> = when (this) {
        Name -> list.sortedWith(compareBy(NaturalOrder) { it.name })
        // Ids are handed out in creation order; one made offline is newest.
        Newest -> list.sortedByDescending { if (it.id < 0) Int.MAX_VALUE else it.id }
        Nearest -> if (here == null) list else list.sortedBy { c ->
            c.bounds?.takeIf { it.size == 4 }?.let { b -> distance(here, (b[1] + b[3]) / 2, (b[0] + b[2]) / 2) } ?: Double.MAX_VALUE
        }
    }

    fun places(list: List<Waypoint>, here: Pair<Double, Double>?): List<Waypoint> = when (this) {
        Name -> list.sortedWith(compareBy(NaturalOrder) { it.name })
        Newest -> list.sortedByDescending { if (it.id < 0) Int.MAX_VALUE else it.id }
        Nearest -> if (here == null) list else list.sortedBy { distance(here, it.lat, it.lng) }
    }

    fun save(context: android.content.Context) =
        context.getSharedPreferences("map_ui", android.content.Context.MODE_PRIVATE).edit().putString("library_sort", name).apply()

    companion object {
        fun load(context: android.content.Context): LibrarySort =
            context.getSharedPreferences("map_ui", android.content.Context.MODE_PRIVATE).getString("library_sort", null)
                ?.let { n -> entries.firstOrNull { it.name == n } } ?: Name

        /** The phone's last known position, or null without one or without permission. */
        fun lastKnown(context: android.content.Context): Pair<Double, Double>? = runCatching {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()

        private fun distance(here: Pair<Double, Double>, lat: Double, lng: Double): Double {
            val dx = (lng - here.second) * kotlin.math.cos(Math.toRadians(here.first))
            val dy = lat - here.first
            return dx * dx + dy * dy
        }
    }
}

