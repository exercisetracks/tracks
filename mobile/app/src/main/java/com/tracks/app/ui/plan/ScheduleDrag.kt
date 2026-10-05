// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.plan

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.PlannedWorkout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Long-press a workout on the schedule and drag it to another day.
 *
 * ## The lift
 *
 * On pickup the chip grows a little and rises — the same lift as the
 * builder's reorder (ui/builder/BuilderReorder.kt): about 5% larger, with a
 * shadow, sprung rather than eased. It follows the finger, and on release it
 * settles back to its own size: in place when dropped on another day (the
 * chip then reappears in that day's row), or flying home when let go where it
 * was or somewhere that is not a day.
 *
 * ## Why an overlay, not the chip itself
 *
 * The chip sits inside its day's row inside the week card. Moved with a
 * graphics layer it would draw under every later row, because a z-index only
 * orders siblings. So while a drag is live the chip in its row is invisible
 * and a copy is drawn at the finger in a layer over the whole page
 * ([DragOverlay]). Days report where they are ([dropTarget]); a drop is
 * whichever day the finger is over.
 *
 * ## The others make room
 *
 * The day under the finger opens a gap the size of the dragged chip, and the
 * rows below it slide down to make it ([DropGap]); the day it was lifted from
 * closes up behind it ([collapseWhileAway]). Both are sprung heights, so the
 * week rearranges itself live under the drag rather than only tinting the day
 * that would take it — the drop then lands in a space that is already there,
 * and nothing jumps on release. Dragged back over its own day, its old slot
 * reopens: that is where it will go.
 *
 * ## Long press, then drag — and long press alone
 *
 * The press has to be long so a plain swipe still scrolls the page, and the
 * gesture is consumed once it has lifted so the page does not scroll under
 * the drag. A long press let go without moving opens the move sheet instead
 * ([onHold]): the sheet reaches weeks that are not on screen, which a drag
 * cannot.
 */
internal class ScheduleDrag(private val scope: CoroutineScope) {

    /** The workout being dragged, or settling after a drop. */
    var item by mutableStateOf<PlannedWorkout?>(null)
        private set
    /** Whether it is the agenda's full chip or the month grid's small one. */
    var full by mutableStateOf(true)
        private set
    var size by mutableStateOf(IntSize.Zero)
        private set
    /** The day under the finger, for the row to show it will take the drop. */
    var hover by mutableStateOf<LocalDate?>(null)
        private set

    /** Where the copy's top-left is drawn, in root coordinates. */
    private val topLeft = Animatable(Offset.Zero, Offset.VectorConverter)
    /** 0 at rest, 1 lifted: the growth and the shadow. */
    private val lift = Animatable(0f)
    private var home = Offset.Zero
    private var grab = Offset.Zero
    private var travelled = 0f
    private var pointer = Offset.Zero
    private val targets = HashMap<LocalDate, Rect>()
    /** Counts pickups, so a settle only clears the drag it belongs to. */
    private var pickups = 0

    val position: Offset get() = topLeft.value
    val scale: Float get() = 1f + LIFT_SCALE * lift.value
    val liftFraction: Float get() = lift.value

    fun isDragging(w: PlannedWorkout) = item?.id == w.id

    /** The day the dragged workout was lifted from. */
    val from: LocalDate? get() = item?.scheduledDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** Whether [day] should open a gap for the drag: under the finger, and not where it came from. */
    fun opensGap(day: LocalDate): Boolean = item != null && hover == day && hover != from

    /**
     * Whether the dragged chip's own slot is closed up: it is over another
     * day that will take it. Over nothing, or back over its own day, the slot
     * stays — that is where it would return.
     */
    fun awayFromHome(w: PlannedWorkout): Boolean = isDragging(w) && hover != null && hover != from

    fun register(day: LocalDate, bounds: Rect) { targets[day] = bounds }
    fun unregister(day: LocalDate) { targets.remove(day) }

    /**
     * Whether [PlannedWorkout] may land on a day. A day it may not take is
     * never highlighted, and a drop there flies home — the race cannot be
     * moved into the past, and it should look refused, not moved and back.
     */
    var accepts: (PlannedWorkout, LocalDate) -> Boolean = { _, _ -> true }

    fun start(w: PlannedWorkout, full: Boolean, chip: LayoutCoordinates, at: Offset) {
        item = w
        pickups++
        this.full = full
        size = chip.size
        home = chip.positionInRoot()
        grab = at
        pointer = home + at
        travelled = 0f
        scope.launch { topLeft.snapTo(home) }
        scope.launch { lift.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow)) }
        hover = dayAt(pointer)?.takeIf { accepts(w, it) }
    }

    fun by(delta: Offset) {
        if (item == null) return
        pointer += delta
        travelled += delta.getDistance()
        scope.launch { topLeft.snapTo(pointer - grab) }
        hover = dayAt(pointer)?.takeIf { d -> item?.let { accepts(it, d) } == true }
    }

    /**
     * Let go. Returns the day it was dropped on, or null when it was not
     * dropped on a day. [held] is true when the finger never really moved.
     */
    fun end(slop: Float): Drop? {
        val w = item ?: return null
        val day = dayAt(pointer)
        val held = travelled < slop
        val from = runCatching { LocalDate.parse(w.scheduledDate.take(10)) }.getOrNull()
        val elsewhere = !held && day != null && day != from
        val landed = elsewhere && accepts(w, day!!)
        hover = null
        val pickup = pickups
        scope.launch {
            // Settling: back to size, and home unless it landed somewhere —
            // each Animatable driven by exactly one coroutine. This used to
            // shrink `lift` in a child and then again in the parent. Starting
            // an animation interrupts the running one by cancelling its
            // caller's Job, and on the main dispatcher the child starts second,
            // so it cancelled the parent — the coroutine that clears [item] —
            // and a workout dropped on another day left its lifted copy on
            // screen for good (ScheduleDropSettleTest).
            try {
                coroutineScope {
                    launch { lift.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    if (!landed) launch { topLeft.animateTo(home, spring(stiffness = Spring.StiffnessMediumLow)) }
                }
            } finally {
                // Cleared however the settle ends — finished, or cut short by
                // a new pickup's animations — unless that new pickup is now
                // the drag on screen.
                if (pickups == pickup) item = null
            }
        }
        return Drop(w, if (landed) day else null, held, refused = if (elsewhere && !landed) day else null)
    }

    fun cancel() {
        end(Float.MAX_VALUE)
    }

    private fun dayAt(p: Offset): LocalDate? = targets.entries.firstOrNull { it.value.contains(p) }?.key

    /** [day] where it landed; [refused] a day it was dropped on and may not take. */
    data class Drop(val workout: PlannedWorkout, val day: LocalDate?, val held: Boolean, val refused: LocalDate? = null)

    companion object {
        /** "Slightly larger": picked up, without covering the neighbours. */
        const val LIFT_SCALE = 0.05f
        val LIFT_ELEVATION = 12.dp
    }
}

@Composable
internal fun rememberScheduleDrag(): ScheduleDrag {
    val scope = rememberCoroutineScope()
    return remember { ScheduleDrag(scope) }
}

/**
 * A chip that can be picked up. Taps are left to the modifiers outside this
 * one; after a lift the release is consumed, so it never also counts as a tap.
 */
@Composable
internal fun Modifier.draggableWorkout(
    schedule: ScheduleDrag,
    workout: PlannedWorkout,
    full: Boolean,
    onDrop: (ScheduleDrag.Drop) -> Unit,
): Modifier {
    // One holder for the chip's lifetime: the gesture block outlives any one
    // composition, and must read where the chip is now.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val current by rememberUpdatedState(workout to onDrop)
    return this
        .onGloballyPositioned { coords[0] = it }
        .graphicsLayer { alpha = if (schedule.isDragging(workout)) 0f else 1f }
        .pointerInput(workout.id, full) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val c = coords[0] ?: return@awaitEachGesture
                val (w, drop) = current
                schedule.start(w, full, c, press.position)
                press.consume()
                val finished = drag(press.id) { change ->
                    schedule.by(change.positionChange())
                    change.consume()
                }
                currentEvent.changes.forEach { it.consume() }
                if (finished) schedule.end(slop)?.let(drop) else schedule.cancel()
            }
        }
}

/**
 * The dragged chip's slot closes while it is over another day, so the chips
 * below it in its own day move up. Height only, and the chip stays composed
 * at zero height: its pointer input is running the drag, and removing it
 * from composition would end the gesture under the finger.
 */
@Composable
internal fun Modifier.collapseWhileAway(drag: ScheduleDrag, workout: PlannedWorkout): Modifier {
    val open by animateFloatAsState(
        if (drag.awayFromHome(workout)) 0f else 1f,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "slot",
    )
    return layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        layout(p.width, (p.height * open).roundToInt()) { p.placeRelative(0, 0) }
    }
}

/**
 * Room in [day] for the chip being dragged over it: a sprung gap as tall as
 * the chip, outlined where it will land. Emitted only while it has height, so
 * an idle week carries no zero-height child (and no spacing for one).
 */
@Composable
internal fun DropGap(drag: ScheduleDrag, day: LocalDate) {
    val density = LocalDensity.current
    val target = if (drag.opensGap(day)) with(density) { drag.size.height.toDp() } else 0.dp
    val height by animateDpAsState(target, spring(stiffness = Spring.StiffnessMediumLow), label = "gap")
    if (height <= 0.dp) return
    val shape = RoundedCornerShape(if (drag.full) Tokens.Radius.lg else Tokens.Chip.radius)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), shape),
    )
}

/** A day that takes drops: reports where it is while it is on screen. */
@Composable
internal fun Modifier.dropTarget(drag: ScheduleDrag, day: LocalDate): Modifier {
    DisposableEffect(drag, day) { onDispose { drag.unregister(day) } }
    return onGloballyPositioned { drag.register(day, it.boundsInRoot()) }
}

/** The lifted copy, drawn over the page. Its scale is exposed for tests. */
@Composable
internal fun DragOverlay(drag: ScheduleDrag, origin: Offset) {
    val w = drag.item ?: return
    // Read here, not only in the layer, so the semantics below follow it.
    val scale = drag.scale
    val density = LocalDensity.current
    val shape = RoundedCornerShape(if (drag.full) Tokens.Radius.lg else Tokens.Chip.radius)
    Box(
        Modifier
            .offset { (drag.position - origin).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .size(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
            .graphicsLayer {
                scaleX = drag.scale
                scaleY = drag.scale
                shadowElevation = ScheduleDrag.LIFT_ELEVATION.toPx() * drag.liftFraction
                this.shape = shape
                clip = false
            }
            .semantics {
                testTag = "schedule-drag"
                liftScale = scale
            },
    ) { WorkoutChip(w, full = drag.full) }
}

/** The lifted chip's current scale — what a test reads to see the lift. */
internal val LiftScale = SemanticsPropertyKey<Float>("LiftScale")
internal var SemanticsPropertyReceiver.liftScale by LiftScale
