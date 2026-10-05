// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one place a course removal is ever confirmed.
 *
 * The watch never acknowledges a removal — it simply stops listing the course —
 * so a tombstone has to keep asserting itself, and keep forcing a full sync to
 * be heard, until the course leaves the watch's digest. This is where that gets
 * noticed and the tombstone dropped.
 *
 * It is worth pinning because it was unreachable for a long time: the prune ran
 * against the phone's own cumulative, persisted name map rather than against
 * what the watch had just said, so it retained everything. Nothing failed
 * loudly. Every sync after the first course deletion was simply forced to FULL,
 * for ever.
 */
class ExploreSyncPruneTest {

    private fun prune(
        names: MutableMap<String, String>,
        tombstones: MutableSet<String>,
        present: Set<String>,
        fullSync: Boolean = true,
    ) = ExploreSyncHandler.pruneToWatchState(names, tombstones, present, fullSync)

    @Test
    fun `a removal the watch has finished is dropped`() {
        val names = mutableMapOf("aa" to "Ridge Loop", "bb" to "River Path")
        val tombstones = mutableSetOf("aa")

        // The watch no longer lists "aa": it acted on the tombstone.
        val changed = prune(names, tombstones, setOf("bb"))

        assertTrue(changed)
        assertTrue(tombstones.isEmpty(), "the removal is done; re-asserting it forces FULL for ever")
        assertEquals(setOf("bb"), names.keys)
    }

    @Test
    fun `a removal the watch has not acted on yet is kept`() {
        val names = mutableMapOf("aa" to "Ridge Loop", "bb" to "River Path")
        val tombstones = mutableSetOf("aa")

        val changed = prune(names, tombstones, setOf("aa", "bb"))

        assertFalse(changed)
        assertEquals(setOf("aa"), tombstones, "the course is still there; keep saying so")
    }

    @Test
    fun `an incremental digest prunes nothing`() {
        // The trap this gate exists for. An incremental digest carries only
        // what changed, so an empty one means "nothing changed" — and pruning
        // against it would read that as "the watch has no courses" and throw
        // away every name and every pending removal at once.
        val names = mutableMapOf("aa" to "Ridge Loop", "bb" to "River Path")
        val tombstones = mutableSetOf("aa")

        val changed = prune(names, tombstones, emptySet(), fullSync = false)

        assertFalse(changed)
        assertEquals(setOf("aa", "bb"), names.keys)
        assertEquals(setOf("aa"), tombstones)
    }

    @Test
    fun `a course deleted on the watch is forgotten even without a tombstone`() {
        // Somebody deleted it on the wrist. The phone's map is a belief about
        // the watch, so it has to follow.
        val names = mutableMapOf("aa" to "Ridge Loop", "bb" to "River Path")
        val tombstones = mutableSetOf<String>()

        assertTrue(prune(names, tombstones, setOf("bb")))
        assertEquals(setOf("bb"), names.keys)
    }

    @Test
    fun `names of surviving courses are kept, not just their ids`() {
        val names = mutableMapOf("aa" to "Ridge Loop", "bb" to "River Path")
        prune(names, mutableSetOf(), setOf("bb"))
        assertEquals("River Path", names["bb"])
    }

    @Test
    fun `nothing to do reports no change, so state is not rewritten every sync`() {
        val names = mutableMapOf("aa" to "Ridge Loop")
        assertFalse(prune(names, mutableSetOf(), setOf("aa")))
    }

    @Test
    fun `a watch that has been emptied is followed all the way down`() {
        val names = mutableMapOf("aa" to "Ridge Loop")
        val tombstones = mutableSetOf("aa")
        assertTrue(prune(names, tombstones, emptySet()))
        assertTrue(names.isEmpty())
        assertTrue(tombstones.isEmpty())
    }
}
