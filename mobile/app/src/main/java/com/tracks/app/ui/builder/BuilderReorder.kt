// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.builder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drag to reorder the builder's structure, by its handle.
 *
 * ## Why by hand, and why on the handle
 *
 * The first version lifted a row on a long press anywhere on it
 * (`detectDragGesturesAfterLongPress`). The user found the half-second wait
 * before anything moved made it feel broken, so the row now lifts the moment
 * the handle is touched and follows the finger from the first pixel. That can
 * only live on the handle — the rest of the row still has to tap (edit) and let
 * the list scroll. No reorder library is used: the list is not a plain list of
 * equal rows but slots with group markers ([Slot]), where an item steps one
 * slot at a time across markers and a group steps a whole unit at a time, and
 * neither rule fits a library's "swap index a with index b".
 *
 * ## Staying under the finger
 *
 * The state is where the dragged row's top *should* be on screen
 * ([fingerTop]), not an offset from where it was laid out. Each step of the
 * drag may move the row to a new place in the list, and the auto-scroll moves
 * everything; drawing it at `fingerTop − its current layout position` keeps it
 * under the finger through both without correcting an offset after every move.
 */
internal class Reorder<T>(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
) {
    /** The structure as last composed, and where a step writes the new one. */
    var slots: List<Slot<T>> = emptyList()
    var onSlots: (List<Slot<T>>) -> Unit = {}

    /** The row being dragged, or settling back after a drop. */
    var draggingKey by mutableStateOf<String?>(null)
        private set
    /** Every row moving with it — a whole group when its head is dragged. */
    var block by mutableStateOf<Set<String>>(emptySet())
        private set
    /** A finger is on the handle. */
    var active by mutableStateOf(false)
        private set
    private var fingerTop by mutableFloatStateOf(0f)

    /** 0 at rest, 1 lifted: drives the enlargement and the shadow. */
    private val lift = Animatable(0f)
    private var settling: Job? = null
    /** The layout a step last acted on; another step waits for a fresh one. */
    private var acted: LazyListLayoutInfo? = null

    fun isMoving(key: String) = key in block

    private fun info(key: String?): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun start(key: String) {
        settling?.cancel()
        val me = info(key) ?: return
        val i = slots.indexOfFirst { it.key == key }
        if (i < 0) return
        val range = if (slots[i] is Slot.Head) BuilderBlocks.units(slots).first { it.first == i } else i..i
        draggingKey = key
        block = range.map { slots[it].key }.toSet()
        fingerTop = me.offset.toFloat()
        acted = null
        active = true
        scope.launch { lift.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
    }

    fun by(dy: Float) {
        if (!active) return
        fingerTop += dy
        step()
    }

    /** Let go: settle into the slot it was dropped in, then come down. */
    fun end() {
        if (!active) return
        active = false
        val key = draggingKey
        settling = scope.launch {
            launch { lift.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            val home = info(key)?.offset?.toFloat()
            if (home != null) {
                animate(fingerTop, home, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> fingerTop = v }
            }
            lift.animateTo(0f)
            draggingKey = null
            block = emptySet()
        }
    }

    /**
     * Draw a moving row: shifted to the finger, a little larger, and raised.
     * A group's rows scale about the middle of the whole group, so it grows as
     * one card rather than each row growing about its own middle.
     */
    fun draw(layer: GraphicsLayerScope, key: String, shape: Shape) {
        if (key !in block) return
        val anchor = info(draggingKey) ?: return
        val me = info(key) ?: return
        val l = lift.value
        with(layer) {
            translationY = fingerTop - anchor.offset
            val s = 1f + LIFT_SCALE * l
            scaleX = s
            scaleY = s
            shadowElevation = LIFT_ELEVATION.toPx() * l
            this.shape = shape
            clip = false
            if (block.size > 1) {
                val height = block.sumOf { info(it)?.size ?: 0 }
                val middle = anchor.offset + height / 2f
                transformOrigin = TransformOrigin(0.5f, (middle - me.offset) / me.size.coerceAtLeast(1))
            }
        }
    }

    /** One step of the drag: cross at most one neighbour, then wait for the new layout. */
    fun step() {
        val layout = listState.layoutInfo
        if (layout === acted) return
        val key = draggingKey ?: return
        val infos = layout.visibleItemsInfo.associateBy { it.key }
        val i = slots.indexOfFirst { it.key == key }
        val me = infos[key] ?: return
        if (i < 0) return
        val units = BuilderBlocks.units(slots)

        fun extent(r: IntRange): ClosedFloatingPointRange<Float>? {
            val a = infos[slots[r.first].key] ?: return null
            val b = infos[slots[r.last].key] ?: return null
            return a.offset.toFloat()..(b.offset + b.size).toFloat()
        }
        fun middle(r: ClosedFloatingPointRange<Float>) = (r.start + r.endInclusive) / 2f
        /** A full superset's whole extent, if [s] is one of its markers. */
        fun fullGroup(s: Slot<T>?): ClosedFloatingPointRange<Float>? {
            val group = when (s) {
                is Slot.Head -> s.group
                is Slot.End -> (slots.firstOrNull { it is Slot.Head && it.group.uid == s.uid } as? Slot.Head)?.group
                else -> null
            } ?: return null
            if (!BuilderBlocks.isFull(slots, group)) return null
            return extent(units.first { (slots[it.first] as? Slot.Head)?.group?.uid == group.uid })
        }

        val moved = if (slots[i] is Slot.Item) {
            val mid = fingerTop + me.size / 2f
            // Past the middle of the neighbour — or, for a full superset the
            // item will hop over, past the middle of the whole superset, or it
            // would hop straight back on the next step.
            val prev = slots.getOrNull(i - 1)
            val next = slots.getOrNull(i + 1)
            val prevEdge = (fullGroup(prev) ?: prev?.let { infos[it.key] }?.let { it.offset.toFloat()..(it.offset + it.size).toFloat() })
            val nextEdge = (fullGroup(next) ?: next?.let { infos[it.key] }?.let { it.offset.toFloat()..(it.offset + it.size).toFloat() })
            when {
                prevEdge != null && mid < middle(prevEdge) -> BuilderBlocks.moveItem(slots, i, i - 1)
                nextEdge != null && mid > middle(nextEdge) -> BuilderBlocks.moveItem(slots, i, i + 1)
                else -> return
            }
        } else {
            val u = units.indexOfFirst { it.first == i }
            if (u < 0) return
            val height = units[u].sumOf { infos[slots[it].key]?.size ?: 0 }
            val prev = units.getOrNull(u - 1)?.let(::extent)
            val next = units.getOrNull(u + 1)?.let(::extent)
            when {
                prev != null && fingerTop < middle(prev) -> BuilderBlocks.moveUnit(slots, u, u - 1)
                next != null && fingerTop + height > middle(next) -> BuilderBlocks.moveUnit(slots, u, u + 1)
                else -> return
            }
        }
        if (moved === slots) return
        acted = layout
        slots = moved
        onSlots(moved)
    }

    /** How fast to scroll while the dragged rows are held against an edge. */
    fun edgeSpeed(): Float {
        if (!active) return 0f
        val layout = listState.layoutInfo
        val height = block.sumOf { info(it)?.size ?: 0 }.coerceAtMost(layout.viewportEndOffset / 2)
        val edge = 80f
        return when {
            fingerTop < layout.viewportStartOffset + edge -> -12f
            fingerTop + height > layout.viewportEndOffset - edge -> 12f
            else -> 0f
        }
    }

    private companion object {
        /** "Slightly larger": enough to read as picked up, not enough to cover the neighbours. */
        const val LIFT_SCALE = 0.04f
        val LIFT_ELEVATION = 12.dp
    }
}

@Composable
internal fun <T> rememberReorder(
    listState: LazyListState,
    slots: List<Slot<T>>,
    onSlots: (List<Slot<T>>) -> Unit,
): Reorder<T> {
    val scope = rememberCoroutineScope()
    val state = remember(listState) { Reorder<T>(listState, scope) }
    state.slots = slots
    state.onSlots = onSlots
    // Auto-scroll while the dragged rows are held against the top or bottom
    // edge, so a long structure can be reordered end to end in one drag.
    LaunchedEffect(state.active) {
        while (state.active) {
            val speed = state.edgeSpeed()
            if (speed != 0f) {
                listState.scrollBy(speed)
                state.step()
            }
            delay(16)
        }
    }
    return state
}

/**
 * The handle: lifts on touch, with no long press and no slop to wait out,
 * and consumes everything so the list does not scroll under the drag.
 */
internal fun Modifier.dragHandle(reorder: Reorder<*>, key: String): Modifier = pointerInput(reorder, key) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        reorder.start(key)
        try {
            drag(down.id) { change ->
                reorder.by(change.positionChange().y)
                change.consume()
            }
        } finally {
            reorder.end()
        }
    }
}
