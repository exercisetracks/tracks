// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The consequence of getting this wrong is invisible rather than loud: the file
 * uploads fine, and only the "you may release this now" that follows goes
 * astray. The watch then offers the same activity on every sync forever, and
 * nothing in the app ever reports an error.
 */
class GarminSyncFileIdTest {

    @Test
    fun `round trips an ordinary file`() {
        val encoded = GarminSyncFileId.encode(33652, 7, "SPORTS")
        assertEquals("gfs:33652:7:SPORTS", encoded)

        val decoded = GarminSyncFileId.decode(encoded)
        assertEquals(GarminSyncFileId.Decoded(33652, 7, "SPORTS"), decoded)
    }

    @Test
    fun `round trips ids with the top bit set`() {
        // fixed64 on the wire, Long in Java, so half the value space arrives
        // negative. Decimal round-trips it; anything that assumed unsigned
        // would not.
        val id1 = -1L
        val id2 = Long.MIN_VALUE
        val encoded = GarminSyncFileId.encode(id1, id2, "MONITOR")
        assertEquals(GarminSyncFileId.Decoded(id1, id2, "MONITOR"), GarminSyncFileId.decode(encoded))
    }

    @Test
    fun `a file with no type name is still addressable`() {
        // This is the common case, not the edge case: the watch names each type
        // once and then refers to it by number, so most files in a listing
        // arrive unnamed. A mark-as-synced needs only the ids, so requiring a
        // name would leave most of a real sync unreleasable — and every one of
        // those files would be offered again on every sync.
        assertEquals(
            GarminSyncFileId.Decoded(1, 2, null),
            GarminSyncFileId.decode(GarminSyncFileId.encode(1, 2, null)),
        )
        assertEquals(
            GarminSyncFileId.Decoded(1, 2, null),
            GarminSyncFileId.decode(GarminSyncFileId.encode(1, 2, "")),
        )
    }

    @Test
    fun `a directory index from the older protocol is not mistaken for one of ours`() {
        assertFalse(GarminSyncFileId.matches("454"))
        assertNull(GarminSyncFileId.decode("454"))
        assertTrue(GarminSyncFileId.matches("gfs:1:2:SPORTS"))
    }

    @Test
    fun `a type name containing a colon survives`() {
        // Nothing observed uses one, but the split has to be bounded anyway —
        // an unbounded one would truncate the name and produce an id the watch
        // rejects, which is exactly the silent failure this class exists to
        // avoid.
        val encoded = GarminSyncFileId.encode(1, 2, "ODD:NAME")
        assertEquals("ODD:NAME", GarminSyncFileId.decode(encoded)?.typeName)
    }

    @Test
    fun `malformed ids decode to null rather than throwing`() {
        // This runs after the bytes are safely on the server. Throwing here
        // would turn "the watch keeps a file it did not need to" into a failed
        // upload, which is strictly worse.
        assertNull(GarminSyncFileId.decode("gfs:"))
        assertNull(GarminSyncFileId.decode("gfs:1"))
        assertNull(GarminSyncFileId.decode("gfs:1:2"))
        assertNull(GarminSyncFileId.decode("gfs:one:two:SPORTS"))
    }
}
