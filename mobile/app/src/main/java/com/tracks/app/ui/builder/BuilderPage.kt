// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.builder

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import com.tracks.app.ui.theme.Tokens
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.EvenGrid
import com.tracks.app.ui.components.OptionCell
import com.tracks.app.ui.components.OptionGrid
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.tracks.app.ui.components.SlideOver
import com.tracks.app.ui.components.rememberSlideOverState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.tracks.app.ui.body.StackedMusclePicker
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.components.DangerButton
import com.tracks.core.spec.muscleLabel
import com.tracks.core.api.StepGroup
import kotlin.math.abs

/** A row with a key that survives edits, for the list and for dragging. */
data class Keyed<T>(val key: String, val value: T)

/** Everything a saved workout or flow says, as the builder edits it. */
data class BuilderDraft<T>(
    val id: Int? = null,
    val name: String = "",
    val notes: String = "",
    val tags: Set<String> = emptySet(),
    val includeInPlan: Boolean = true,
    val syncToWatch: Boolean = false,
    val items: List<Keyed<T>> = emptyList(),
)

/** A library entry as the add panel lists it. */
data class LibraryEntry(
    val name: String,
    val primary: List<String>,
    val secondary: List<String>,
    /** One short line — a prescription or a hold — under the name. */
    val detail: String,
)

/**
 * What differs between a strength workout and a stretch flow. Everything else
 * — the page, the structure, the blocks, the add panel — is one code path,
 * because the two are meant to behave identically.
 */
interface BuilderKind<T> {
    /** "workout" / "flow". */
    val thing: String
    /** "exercise" / "stretch". */
    val noun: String
    val tagChoices: List<Pair<String, String>>
    fun nameOf(item: T): String?
    fun isRest(item: T): Boolean
    fun group(item: T): StepGroup?
    fun withGroup(item: T, group: StepGroup?): T
    fun restItem(seconds: Int): T
    fun restSeconds(item: T): Int
    fun withRestSeconds(item: T, seconds: Int): T
    fun newItem(entry: LibraryEntry): T
    /** The prescription in one line. */
    fun line(item: T): String

    @Composable
    fun EditDialog(item: T, onSave: (T) -> Unit, onDismiss: () -> Unit)
}

private var keySeq = 0L
internal fun newKey(): String = "k${keySeq++}-${System.nanoTime()}"
internal fun newGroupUid(): String = java.util.UUID.randomUUID().toString()

/** "exercises", "stretches". */
internal fun plural(noun: String) = if (noun.endsWith("ch") || noun.endsWith("s")) "${noun}es" else "${noun}s"

/**
 * Write or edit a saved workout or flow — a whole page, not a sheet.
 *
 * The user found the old sheet-over-sheet-over-popup nesting too deep, so this
 * is one page: a one-line top bar whose title *is* the name, typed into in
 * place (a separate name field cost a row of height; a rename popup was one
 * step too many, per the user), notes and chips under it, and the STRUCTURE filling the rest.
 *
 * Adding opens the picker over the whole page — figures beside a compact list
 * — and it stays open while several are added, since they usually are. It
 * opens from "Add exercises" or a leftward swipe anywhere on the page, and
 * closes with Done, back or a rightward swipe. The header is hidden meanwhile
 * so the selection has the full height.
 *
 * Blocks: Rest, Superset and Repeat are all added the same way, from the row
 * above the structure. Superset and Repeat are containers; exercises are
 * dragged into and out of them by their handles, and where one is dropped
 * decides what it belongs to. The rules are [BuilderBlocks]'s, the dragging
 * [Reorder]'s.
 */
@Composable
fun <T> BuilderPage(
    kind: BuilderKind<T>,
    initial: BuilderDraft<T>,
    library: List<LibraryEntry>,
    onSave: (BuilderDraft<T>) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BuilderContent(kind, initial, library, onSave, onDelete, onBack)
    }
}

/** The page's content, apart from its window — which is what a screenshot test can render. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> BuilderContent(
    kind: BuilderKind<T>,
    initial: BuilderDraft<T>,
    library: List<LibraryEntry>,
    onSave: (BuilderDraft<T>) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
    // Closed, even for a new workout: the user asked (2026-09-28) that the
    // page open first — name, chips, the empty structure — with the picker
    // one tap away rather than slid over it unasked.
    startAdding: Boolean = false,
) {
    val rows = remember(kind) {
        BlockRows<Keyed<T>>(
            key = { it.key },
            group = { kind.group(it.value) },
            withGroup = { k, g -> k.copy(value = kind.withGroup(k.value, g)) },
        )
    }
    var draft by remember { mutableStateOf(initial) }
    var slots by remember { mutableStateOf(BuilderBlocks.slots(initial.items, rows)) }
    // The picker slides over the builder exactly as the filter does over a
    // library page, and as the navigation drawer does from the left: see SlideOver.
    val slide = rememberSlideOverState(startAdding)
    val scope = rememberCoroutineScope()
    fun show(picker: Boolean) {
        scope.launch { slide.animateTo(picker) }
    }
    var tagsOpen by remember { mutableStateOf(false) }
    var notesOpen by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A brief pulse behind the title when Save is pressed with no name, so
    // the reason nothing saved is where the eye already is.
    val nameFlash = remember { Animatable(0f) }
    fun askForName() {
        titleFocus.requestFocus()
        keyboard?.show()
        scope.launch {
            repeat(2) {
                nameFlash.animateTo(1f, tween(140))
                nameFlash.animateTo(0f, tween(260))
            }
        }
    }
    var editing by remember { mutableStateOf<Keyed<T>?>(null) }

    val hasItems = slots.any { it is Slot.Item && !kind.isRest(it.value.value) }
    fun save(name: String = draft.name) = onSave(draft.copy(name = name, items = BuilderBlocks.items(slots, rows)))

    // Back shuts the picker first, empty or not: the page is always under it
    // now, so leaving the builder from the picker would skip a screen.
    BackHandler { if (slide.isOpen) show(false) else onBack() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
        SlideOver(
            state = slide,
            overlay = {
                AddPanel(
                    kind = kind,
                    library = library,
                    counts = slots.mapNotNull { if (it is Slot.Item) it.value else null }.groupingBy { kind.nameOf(it.value) }.eachCount(),
                    onAdd = { entry ->
                        val k = Keyed(newKey(), kind.newItem(entry))
                        slots = slots + Slot.Item(k, k.key)
                    },
                    onDone = { show(false) },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding(),
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                // ── Top bar: the title is the name ─────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeutralButton("Cancel", onClick = onBack, small = true)
                    Row(
                        Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val flashColor = MaterialTheme.colorScheme.error
                        val titleStyle = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        // Sized by an invisible copy of what is shown (the name, or
                        // the placeholder), so the pencil sits right beside the
                        // text: a text field measures itself by its own content,
                        // which for an empty name clipped "New workout" to "New".
                        Box(
                            Modifier.weight(1f, fill = false)
                                .drawBehind {
                                    if (nameFlash.value > 0f) {
                                        drawRoundRect(
                                            color = flashColor.copy(alpha = 0.22f * nameFlash.value),
                                            topLeft = Offset(-6.dp.toPx(), -2.dp.toPx()),
                                            size = Size(size.width + 12.dp.toPx(), size.height + 4.dp.toPx()),
                                            cornerRadius = CornerRadius(8.dp.toPx()),
                                        )
                                    }
                                },
                        ) {
                            Text(
                                draft.name.ifEmpty { "New ${kind.thing}" } + " ",
                                style = titleStyle, maxLines = 1,
                                modifier = Modifier.alpha(0f),
                            )
                            BasicTextField(
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                                singleLine = true,
                                textStyle = titleStyle,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                modifier = Modifier.matchParentSize().focusRequester(titleFocus),
                                decorationBox = { field ->
                                    Box(contentAlignment = Alignment.Center) {
                                        if (draft.name.isEmpty()) {
                                            Text("New ${kind.thing}", style = titleStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), maxLines = 1)
                                        }
                                        field()
                                    }
                                },
                            )
                        }
                        Icon(
                            Icons.Filled.Edit, contentDescription = "Rename",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp).clickable { titleFocus.requestFocus() },
                        )
                    }
                    PrimaryButton(
                        "Save",
                        onClick = {
                            // Unnamed: put the cursor in the title rather than saving
                            // "New workout" — a second one would clash with it.
                            if (draft.name.isBlank()) askForName() else save(draft.name.trim())
                        },
                        enabled = hasItems,
                        small = true,
                    )
                }

                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Notes as a chip beside Tags, not a text box: set rarely,
                    // and the box cost two rows of the structure's height.
                    EvenGrid(spacing = 6.dp, minColumns = 2) {
                        OptionCell(
                            if (draft.tags.isEmpty()) "Tags" else "Tags · ${draft.tags.size}",
                            selected = draft.tags.isNotEmpty(),
                            onClick = { tagsOpen = true },
                        )
                        OptionCell("Notes", selected = draft.notes.isNotBlank(), onClick = { notesOpen = true })
                        // Named for what they do, not where the workout ends
                        // up: "In my plan" / "On the watch" read as a status
                        // to new users. Short enough for a half-width cell at
                        // 360 dp; the web's checkboxes use the same words.
                        OptionCell(
                            "Schedule in my plan",
                            selected = draft.includeInPlan,
                            onClick = { draft = draft.copy(includeInPlan = !draft.includeInPlan) },
                            multi = true,
                        )
                        // No watch, no choice to offer; the draft keeps
                        // whatever it held, so the answer survives a watch
                        // being added later.
                        if (com.tracks.app.ui.components.LocalHasDevice.current) {
                            OptionCell(
                                "Save to my watch",
                                selected = draft.syncToWatch,
                                onClick = { draft = draft.copy(syncToWatch = !draft.syncToWatch) },
                                multi = true,
                            )
                        }
                    }
                    // Every block is added the same way — Rest, Superset and
                    // Repeat side by side — and lands at the end of the structure.
                    // One look and one verb for all four, the picker included:
                    // the user found an accent "Add exercises" beside neutral
                    // "+ Rest" buttons read as two different kinds of control.
                    ButtonRow(Modifier.padding(top = 2.dp)) {
                        NeutralButton("Add ${plural(kind.noun)}", onClick = { show(true) }, small = true)
                        NeutralButton("Add rest", small = true, onClick = {
                            val k = Keyed(newKey(), kind.restItem(60))
                            slots = slots + Slot.Item(k, k.key)
                        })
                        NeutralButton("Add superset", small = true, onClick = {
                            slots = BuilderBlocks.addGroup(slots, StepGroup(newGroupUid(), "superset", rounds = 3, restSeconds = 90))
                        })
                        NeutralButton("Add repeat", small = true, onClick = {
                            slots = BuilderBlocks.addGroup(slots, StepGroup(newGroupUid(), "repeat", rounds = 3, restSeconds = 60))
                        })
                    }
                }

                Structure(
                    kind = kind,
                    slots = slots,
                    onSlots = { slots = it },
                    onEdit = { editing = it },
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    DangerButton("Delete ${kind.thing}", onClick = onDelete, small = true,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
                }
            }
        
        }
        if (notesOpen) {
            NotesDialog(draft.notes, { draft = draft.copy(notes = it) }) { notesOpen = false }
        }
        if (tagsOpen) {
            TagsDialog(kind.tagChoices, draft.tags, { draft = draft.copy(tags = it) }) { tagsOpen = false }
        }
        editing?.let { item ->
            kind.EditDialog(
                item.value,
                onSave = { updated ->
                    slots = BuilderBlocks.update(slots, item.key) { it.copy(value = updated) }
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

// ── The structure list ───────────────────────────────────────────────────────

/**
 * The structure as one flat list — items, and each group's head and end as
 * rows of their own — so an item can be dragged across a group's edge. The
 * rows between a head and its end are drawn on the group's tint, which is what
 * makes the three kinds of row read as one container.
 *
 * The list sits on a card (the app's card colour and radius) so it reads as
 * one thing apart from the chips and add buttons above it, which on a bare
 * page ran straight into the first row. Rows are drawn on the page's surface
 * colour to stand out on that card.
 */
@Composable
private fun <T> Structure(
    kind: BuilderKind<T>,
    slots: List<Slot<Keyed<T>>>,
    onSlots: (List<Slot<Keyed<T>>>) -> Unit,
    onEdit: (Keyed<T>) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    val reorder = rememberReorder(listState, slots, onSlots)

    val card = RoundedCornerShape(Tokens.Radius.xl)
    val framed = modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant, card)
        .clip(card)
    if (slots.isEmpty()) {
        // One short line, not the old instructions: an empty card on its own
        // read as a loading or broken state. The space still takes the swipe.
        Box(framed, contentAlignment = Alignment.Center) {
            Text(
                "No ${plural(kind.noun)} added yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val inside = BuilderBlocks.enclosing(slots)
    // Opaque, so a lifted group's shadow does not show through its own rows.
    val tint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f).compositeOver(MaterialTheme.colorScheme.surface)
    val settle = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))

    LazyColumn(
        framed,
        state = listState,
        contentPadding = PaddingValues(8.dp),
    ) {
        itemsIndexed(slots, key = { _, s -> s.key }) { index, slot ->
            val moving = reorder.isMoving(slot.key)
            val shape: Shape = when {
                slot is Slot.Head -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                slot is Slot.End -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                inside[index] != null -> RectangleShape
                else -> RoundedCornerShape(10.dp)
            }
            Box(
                Modifier
                    .zIndex(if (moving) 1f else 0f)
                    // Neighbours glide out of the way; the dragged rows follow
                    // the finger instead, so they get no placement animation.
                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (moving) null else settle)
                    .graphicsLayer { reorder.draw(this, slot.key, shape) },
            ) {
                when (slot) {
                    is Slot.Head -> GroupHead(
                        group = slot.group,
                        tint = tint,
                        handle = Modifier.dragHandle(reorder, slot.key),
                        onGroup = { onSlots(BuilderBlocks.setGroup(slots, it)) },
                        onRemove = { onSlots(BuilderBlocks.removeGroup(slots, slot.group.uid)) },
                    )
                    is Slot.End -> GroupEnd(
                        empty = slots.getOrNull(index - 1) is Slot.Head,
                        nouns = plural(kind.noun),
                        tint = tint,
                    )
                    is Slot.Item -> {
                        val item = slot.value
                        val grouped = inside[index] != null
                        Box(
                            Modifier.fillMaxWidth()
                                .then(if (grouped) Modifier.background(tint) else Modifier)
                                .padding(horizontal = if (grouped) 8.dp else 0.dp, vertical = 3.dp),
                        ) {
                            val handle = Modifier.dragHandle(reorder, slot.key)
                            if (kind.isRest(item.value)) {
                                RestRow(
                                    seconds = kind.restSeconds(item.value),
                                    handle = handle,
                                    onSeconds = { s -> onSlots(BuilderBlocks.update(slots, item.key) { it.copy(value = kind.withRestSeconds(it.value, s)) }) },
                                    onRemove = { onSlots(BuilderBlocks.remove(slots, item.key)) },
                                )
                            } else {
                                ItemRow(
                                    title = kind.nameOf(item.value) ?: "",
                                    line = kind.line(kind.withGroup(item.value, inside[index])),
                                    handle = handle,
                                    onClick = { onEdit(item) },
                                    onRemove = { onSlots(BuilderBlocks.remove(slots, item.key)) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "footer") { Spacer(Modifier.height(24.dp)) }
    }
}

/** The ⠿ grip — the one place a drag starts, so it gets a full-size touch target. */
@Composable
private fun Grip(handle: Modifier) {
    Box(handle.size(width = 36.dp, height = 44.dp), contentAlignment = Alignment.Center) {
        Text("⠿", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TrashButton(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.Delete, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemRow(title: String, line: String, handle: Modifier, onClick: () -> Unit, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grip(handle)
            Column(Modifier.weight(1f).clickable(onClick = onClick).padding(end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TrashButton("Remove $title", onRemove)
        }
    }
}

@Composable
private fun RestRow(seconds: Int, handle: Modifier, onSeconds: (Int) -> Unit, onRemove: () -> Unit) {
    // Outlined on the card's own colour — a gap between items, not an item.
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grip(handle)
            Text("Rest", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Stepper(formatSeconds(seconds), { onSeconds((seconds - 15).coerceAtLeast(15)) }, { onSeconds((seconds + 15).coerceAtMost(900)) })
            TrashButton("Remove rest", onRemove)
        }
    }
}

/** A group's top edge: what it is, its rounds and rest, and its grip — dragging this moves the whole group. */
@Composable
private fun GroupHead(group: StepGroup, tint: Color, handle: Modifier, onGroup: (StepGroup) -> Unit, onRemove: () -> Unit) {
    val superset = group.kind == "superset"
    Column(
        Modifier.fillMaxWidth().padding(top = 3.dp)
            .background(tint, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .padding(end = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Grip(handle)
            Text(
                if (superset) "Superset" else "Repeat",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TrashButton(if (superset) "Remove superset" else "Remove repeat", onRemove)
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (superset) "Sets" else "Rounds", style = MaterialTheme.typography.labelMedium)
            Stepper("× ${group.rounds}", { onGroup(group.copy(rounds = (group.rounds - 1).coerceAtLeast(1))) },
                { onGroup(group.copy(rounds = (group.rounds + 1).coerceAtMost(20))) })
            Spacer(Modifier.weight(1f))
            Text("Rest", style = MaterialTheme.typography.labelMedium)
            Stepper(formatSeconds(group.restSeconds), { onGroup(group.copy(restSeconds = (group.restSeconds - 15).coerceAtLeast(0))) },
                { onGroup(group.copy(restSeconds = (group.restSeconds + 15).coerceAtMost(900))) })
        }
    }
}

/** A group's bottom edge. Empty, it says what to do — it is the drop target a new group starts as. */
@Composable
private fun GroupEnd(empty: Boolean, nouns: String, tint: Color) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 3.dp)
            .background(tint, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .height(if (empty) 48.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (empty) {
            Text(
                "Drag $nouns here by ⠿",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", onMinus)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        StepButton("+", onPlus)
    }
}

/** A bare −/+ glyph: a pill here would be wider than the number it changes. */
@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

internal fun formatSeconds(s: Int): String = if (s >= 60 && s % 60 == 0) "${s / 60} min" else if (s >= 60) "${s / 60}:${(s % 60).toString().padStart(2, '0')}" else "${s}s"

// ── The add panel ────────────────────────────────────────────────────────────

/**
 * Figures and a compact list side by side, over the whole builder page.
 * Tap a muscle to narrow the list (again to clear it), tap a row to add it —
 * the panel stays open, with a count on each row already in the structure.
 */
@Composable
private fun <T> AddPanel(
    kind: BuilderKind<T>,
    library: List<LibraryEntry>,
    counts: Map<String?, Int>,
    onAdd: (LibraryEntry) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    var muscles by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    val shown = remember(library, muscles, search) {
        library.filter { e ->
            (muscles.isEmpty() || (e.primary + e.secondary).any { it in muscles }) &&
                (search.isBlank() || e.name.contains(search.trim(), ignoreCase = true))
        }.sortedWith(compareBy({ e -> muscles.isNotEmpty() && e.primary.none { it in muscles } }, { it.name }))
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (muscles.isEmpty()) "Add ${plural(kind.noun)}" else muscles.joinToString(" · ") { muscleLabel(it) },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (muscles.isNotEmpty()) NeutralButton("Clear", onClick = { muscles = emptySet() }, small = true)
            PrimaryButton("Done", onClick = onDone, small = true)
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                // Says what the figures are for; they read as decoration without it.
                Text(
                    "FILTER BY MUSCLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                )
                StackedMusclePicker(
                    selected = muscles,
                    onSelectedChange = { muscles = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            Column(Modifier.weight(0.58f).fillMaxHeight().padding(end = 8.dp)) {
                OutlinedTextField(
                    search, { search = it }, placeholder = { Text("Search") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(shown, key = { it.name }) { entry ->
                        val count = counts[entry.name] ?: 0
                        Row(
                            Modifier.fillMaxWidth().clickable { onAdd(entry) }.padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    entry.primary.take(2).joinToString(" · ") { muscleLabel(it) },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                if (count > 0) "✓ $count" else "+",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 6.dp).alpha(if (count > 0) 1f else 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Tags ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesDialog(notes: String, onChange: (String) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes") },
        text = {
            OutlinedTextField(
                notes, onChange,
                placeholder = { Text("Cues, setup, anything to remember") },
                minLines = 3, maxLines = 8,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { PrimaryButton("Done", onClick = onDismiss) },
    )
    // Straight to typing: opening this is the decision to write something.
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun TagsDialog(
    choices: List<Pair<String, String>>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            OptionGrid(
                choices,
                isSelected = { it in selected },
                onPick = { value -> onChange(if (value in selected) selected - value else selected + value) },
                multi = true,
            )
        },
        confirmButton = { PrimaryButton("Done", onClick = onDismiss) },
    )
}
