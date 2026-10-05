// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.builder

import com.tracks.core.api.StepGroup
import kotlin.test.Test
import kotlin.test.assertEquals

class BuilderBlocksTest {

    private data class R(val key: String, val group: StepGroup? = null)

    private val rows = BlockRows<R>(key = { it.key }, group = { it.group }, withGroup = { r, g -> r.copy(group = g) })
    private val g = StepGroup("g", "repeat", 3, 60)

    /** Slots as short strings: items by key, markers as `[g` and `g]`. */
    private fun shape(slots: List<Slot<R>>) = slots.map {
        when (it) {
            is Slot.Item -> it.key
            is Slot.Head -> "[${it.group.uid}"
            is Slot.End -> "${it.uid}]"
        }
    }
    private fun groups(items: List<R>) = items.map { it.group?.uid }

    @Test
    fun `stored rows open as a group wrapped in its markers and fold back unchanged`() {
        val items = listOf(R("a"), R("b", g), R("c", g), R("d"))
        val slots = BuilderBlocks.slots(items, rows)
        assertEquals(listOf("a", "[g", "b", "c", "g]", "d"), shape(slots))
        assertEquals(items, BuilderBlocks.items(slots, rows))
    }

    /** Membership is where the drop lands — there is no separate "join" action to fall out of step with it. */
    @Test
    fun `an item dragged down past a group's head joins that group`() {
        val slots = BuilderBlocks.slots(listOf(R("a"), R("b", g), R("c")), rows)
        val moved = BuilderBlocks.moveItem(slots, from = 0, to = 1)
        assertEquals(listOf("[g", "a", "b", "g]", "c"), shape(moved))
        assertEquals(listOf("g", "g", null), groups(BuilderBlocks.items(moved, rows)))
    }

    @Test
    fun `an item dragged past a group's end leaves it`() {
        val slots = BuilderBlocks.slots(listOf(R("a", g), R("b", g), R("c")), rows)
        val moved = BuilderBlocks.moveItem(slots, from = 2, to = 3)
        assertEquals(listOf("[g", "a", "g]", "b", "c"), shape(moved))
        assertEquals(listOf("g", null, null), groups(BuilderBlocks.items(moved, rows)))
    }

    @Test
    fun `an item dragged up past a group's head leaves it`() {
        val slots = BuilderBlocks.slots(listOf(R("a", g), R("b", g)), rows)
        val moved = BuilderBlocks.moveItem(slots, from = 1, to = 0)
        assertEquals(listOf("a", "[g", "b", "g]"), shape(moved))
    }

    /** A freshly added group has no members, and has to take the first one somehow. */
    @Test
    fun `an empty group is a drop target`() {
        val slots = BuilderBlocks.addGroup(BuilderBlocks.slots(listOf(R("a")), rows), g)
        assertEquals(listOf("a", "[g", "g]"), shape(slots))
        val moved = BuilderBlocks.moveItem(slots, from = 0, to = 1)
        assertEquals(listOf("[g", "a", "g]"), shape(moved))
        assertEquals(listOf("g"), groups(BuilderBlocks.items(moved, rows)))
    }

    @Test
    fun `an empty group is dropped on save, since storage has nothing to hang it on`() {
        val slots = BuilderBlocks.addGroup(BuilderBlocks.slots(listOf(R("a")), rows), g)
        assertEquals(listOf(R("a")), BuilderBlocks.items(slots, rows))
    }

    @Test
    fun `removing the last member leaves the group standing`() {
        val slots = BuilderBlocks.slots(listOf(R("a", g)), rows)
        assertEquals(listOf("[g", "g]"), shape(BuilderBlocks.remove(slots, "a")))
    }

    /** Dragging a group must carry every member, and must not drop it inside another group. */
    @Test
    fun `a group moves as a whole unit, past other groups whole`() {
        val h = StepGroup("h", "superset", 3, 90)
        val slots = BuilderBlocks.slots(listOf(R("a", g), R("b", g), R("c", h), R("d")), rows)
        assertEquals(listOf(0..3, 4..6, 7..7), BuilderBlocks.units(slots))
        val moved = BuilderBlocks.moveUnit(slots, from = 0, to = 1)
        assertEquals(listOf("[h", "c", "h]", "[g", "a", "b", "g]", "d"), shape(moved))
    }

    @Test
    fun `a full superset is hopped over rather than entered`() {
        val s = StepGroup("s", "superset", 3, 90)
        val slots = BuilderBlocks.slots(listOf(R("x"), R("a", s), R("b", s), R("c", s), R("y")), rows)
        val down = BuilderBlocks.moveItem(slots, from = 0, to = 1)
        assertEquals(listOf("[s", "a", "b", "c", "s]", "x", "y"), shape(down))
        val up = BuilderBlocks.moveItem(slots, from = 6, to = 5)
        assertEquals(listOf("x", "y", "[s", "a", "b", "c", "s]"), shape(up))
    }

    @Test
    fun `removing a group's container keeps its members, loose, in place`() {
        val slots = BuilderBlocks.slots(listOf(R("a", g), R("b", g), R("c")), rows)
        val gone = BuilderBlocks.removeGroup(slots, "g")
        assertEquals(listOf("a", "b", "c"), shape(gone))
        assertEquals(listOf(null, null, null), groups(BuilderBlocks.items(gone, rows)))
    }

    @Test
    fun `changing a group's rounds writes every member on save`() {
        val slots = BuilderBlocks.slots(listOf(R("a", g), R("b", g)), rows)
        val changed = BuilderBlocks.setGroup(slots, g.copy(rounds = 5))
        assertEquals(listOf(5, 5), BuilderBlocks.items(changed, rows).map { it.group?.rounds })
    }

    @Test
    fun `a group of four cannot be made a superset`() {
        val slots = BuilderBlocks.slots(listOf(R("a", g), R("b", g), R("c", g), R("d", g)), rows)
        assertEquals(slots, BuilderBlocks.setGroup(slots, g.copy(kind = "superset")))
    }
}
