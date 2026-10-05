// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.builder

import com.tracks.core.api.StepGroup
import com.tracks.core.fit.WorkoutBlocks

/**
 * The editing rules for a workout or flow's structure, apart from any screen.
 *
 * ## Stored: a flat list with the group on each member
 *
 * A repeat group or superset is not a row in storage but settings repeated on
 * consecutive members (spec/sync.yaml, workout_exercise). That is what the
 * encoder, the guided session and the watch read, and it does not change here.
 *
 * ## Edited: a flat list of slots with the group as two markers
 *
 * The builder shows Superset and Repeat as *containers* the user adds like a
 * Rest block and drags exercises into and out of. So while editing, the list is
 * [Slot]s: an item, or a group's [Head] and [End] markers. An item's group is
 * simply the markers it sits between — membership is decided by where a drag
 * drops it, with no separate "join" or "take out" action to keep in step. The
 * markers exist only in the editor; [items] folds them back into the stored
 * form, which is also why an empty group can exist here (as a drop target the
 * user has just added) and vanishes on save — storage has nothing to hang it on.
 *
 * Groups never nest: an item moves one slot at a time and may cross a marker,
 * but a group moves as a whole unit and hops over other units whole.
 *
 * Generic over the row type so Strength and Flexibility share one set of
 * rules — the user asked for the two builders to behave identically. The web
 * builder has the same rules in frontend/src/lib/blocks.js.
 */
class BlockRows<T>(
    val key: (T) -> String,
    val group: (T) -> StepGroup?,
    val withGroup: (T, StepGroup?) -> T,
)

/** One line of the editable structure: an item, or a group's opening or closing marker. */
sealed interface Slot<out T> {
    val key: String

    data class Item<T>(val value: T, override val key: String) : Slot<T>
    data class Head(val group: StepGroup) : Slot<Nothing> {
        override val key: String get() = "head:${group.uid}"
    }
    data class End(val uid: String) : Slot<Nothing> {
        override val key: String get() = "end:$uid"
    }
}

object BuilderBlocks {

    /** Supersets are two or three exercises back to back; more is a repeat. */
    const val SUPERSET_MAX = 3

    /** Stored rows as slots: each run of a group wrapped in its markers. */
    fun <T> slots(items: List<T>, rows: BlockRows<T>): List<Slot<T>> = buildList {
        for ((group, members) in WorkoutBlocks.runsBy(items, rows.group)) {
            if (group != null) add(Slot.Head(group))
            members.forEach { add(Slot.Item(it, rows.key(it))) }
            if (group != null) add(Slot.End(group.uid))
        }
    }

    /**
     * Slots back to stored rows: every item takes the group of the markers
     * around it (the head's settings), or none. Empty groups leave nothing.
     */
    fun <T> items(slots: List<Slot<T>>, rows: BlockRows<T>): List<T> {
        var open: StepGroup? = null
        val out = ArrayList<T>()
        for (s in slots) when (s) {
            is Slot.Head -> open = s.group
            is Slot.End -> open = null
            is Slot.Item -> out += rows.withGroup(s.value, open)
        }
        return out
    }

    /** For each slot, the group it is inside — null for loose items and for the markers. */
    fun <T> enclosing(slots: List<Slot<T>>): List<StepGroup?> {
        var open: StepGroup? = null
        return slots.map { s ->
            when (s) {
                is Slot.Head -> { open = s.group; null }
                is Slot.End -> { open = null; null }
                is Slot.Item -> open
            }
        }
    }

    /**
     * The units a group drag moves past: a loose item, or a whole group from
     * head to end, as index ranges into [slots].
     */
    fun <T> units(slots: List<Slot<T>>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < slots.size) {
            val s = slots[i]
            if (s is Slot.Head) {
                val end = slots.indexOfFirst { it is Slot.End && it.uid == s.group.uid }.takeIf { it >= i } ?: i
                out += i..end
                i = end + 1
            } else {
                out += i..i
                i++
            }
        }
        return out
    }

    /**
     * Move the item at [from] to [to] (list positions, as a drag steps it).
     *
     * Crossing a marker is what moves it into or out of a group. A superset
     * that is already full is hopped over whole instead of entered, so a drag
     * down the list never snags on one.
     */
    fun <T> moveItem(slots: List<Slot<T>>, from: Int, to: Int): List<Slot<T>> {
        if (from !in slots.indices || to !in slots.indices || from == to) return slots
        if (slots[from] !is Slot.Item) return slots
        val out = slots.toMutableList()
        val item = out.removeAt(from)
        out.add(to, item)
        val group = enclosing(out)[to] ?: return out
        if (group.kind != "superset" || memberCount(out, group.uid) <= SUPERSET_MAX) return out
        // Full: land beyond the group in the direction of travel.
        out.removeAt(to)
        val range = units(out).first { (out[it.first] as? Slot.Head)?.group?.uid == group.uid }
        out.add(if (to > from) range.last + 1 else range.first, item)
        return out
    }

    /** Move whole unit [from] to position [to] (unit indices, see [units]). */
    fun <T> moveUnit(slots: List<Slot<T>>, from: Int, to: Int): List<Slot<T>> {
        val units = units(slots).map { slots.slice(it) }.toMutableList()
        if (from !in units.indices || to !in units.indices || from == to) return slots
        units.add(to, units.removeAt(from))
        return units.flatten()
    }

    /** A new, empty group at the end, ready to have items dragged into it. */
    fun <T> addGroup(slots: List<Slot<T>>, group: StepGroup): List<Slot<T>> =
        slots + Slot.Head(group) + Slot.End(group.uid)

    /** Change a group's kind, rounds or rest. A superset refuses to hold more than [SUPERSET_MAX]. */
    fun <T> setGroup(slots: List<Slot<T>>, group: StepGroup): List<Slot<T>> {
        if (group.kind == "superset" && memberCount(slots, group.uid) > SUPERSET_MAX) return slots
        return slots.map { if (it is Slot.Head && it.group.uid == group.uid) Slot.Head(group) else it }
    }

    /**
     * Remove a group's container. Its members stay where they are, loose —
     * the trash on a header drops the grouping, not the exercises inside,
     * because losing a whole superset to one tap is a harder mistake to undo.
     */
    fun <T> removeGroup(slots: List<Slot<T>>, uid: String): List<Slot<T>> =
        slots.filterNot { (it is Slot.Head && it.group.uid == uid) || (it is Slot.End && it.uid == uid) }

    /** Remove one item. Its group, even left empty, stays as a drop target. */
    fun <T> remove(slots: List<Slot<T>>, key: String): List<Slot<T>> =
        slots.filterNot { it is Slot.Item && it.key == key }

    /** Replace one item's value in place. */
    fun <T> update(slots: List<Slot<T>>, key: String, change: (T) -> T): List<Slot<T>> =
        slots.map { if (it is Slot.Item && it.key == key) it.copy(value = change(it.value)) else it }

    /** A superset with no room left — an item dragged at it hops over it whole. */
    fun <T> isFull(slots: List<Slot<T>>, group: StepGroup): Boolean =
        group.kind == "superset" && memberCount(slots, group.uid) >= SUPERSET_MAX

    private fun <T> memberCount(slots: List<Slot<T>>, uid: String): Int =
        enclosing(slots).count { it?.uid == uid }
}
