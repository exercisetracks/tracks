// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.explore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sessions played through with the message shapes the watch exchanged with
 * Garmin Explore in capture c (docs/garmin-explore-sync.md). A wrong field here
 * fails silently on the watch — the course just never appears, or never leaves
 * — so the shapes are pinned.
 */
class ExploreSessionTest {

    private var now = 1791843474L

    private inner class FakeHost : ExploreSession.Host {
        var state: String? = null
        val reports = mutableListOf<ReconcileReport>()
        val sent = mutableListOf<ByteArray>()
        var wanted: Pair<List<DesiredCourse>, List<DesiredWaypoint>>? = Pair(emptyList(), emptyList())
        override fun load() = state
        override fun save(state: String) { this.state = state }
        override fun send(message: ByteArray) { sent += message }
        override fun log(message: String) = println(message)
        override fun wanted() = wanted
        override fun onReport(report: ReconcileReport) { reports += report }
        override fun nowSeconds() = now
        val activeTracks = mutableListOf<List<ActivePoint>>()
        override fun onActiveTrack(points: List<ActivePoint>) { activeTracks += points }
    }

    private val course = DesiredCourse(
        7, "testcourse1",
        listOf(Triple(10.0, 20.0, 575.0), Triple(10.001, 20.0, 576.0), Triple(10.002, 19.999, null)), 0,
    )
    private val place = DesiredWaypoint(3, "test way point 1", 10.0, 20.0, 577.0, 0)

    private fun msg(field: Int, build: ProtoWriter.() -> Unit = {}) = ProtoWriter().message(field, build).toByteArray()
    private fun ExploreSession.ask(field: Int, build: ProtoWriter.() -> Unit = {}) =
        ProtoReader(handle(msg(field, build))!!).message(field + 1)!!

    /**
     * A session Tracks starts — always FULL — up to the change summary, in
     * capture c's order. The watch lists [places] and [lines] as everything it
     * holds, and lists Tracks' collection unless it [forgot] it (a reset).
     */
    private fun ExploreSession.openTo(
        places: List<ByteArray> = emptyList(),
        lines: List<ByteArray> = emptyList(),
        forgot: Boolean = false,
    ): ProtoReader {
        val mine = collectionId().unhex()
        startRequest()
        handle(msg(2) { varint(1, 1) })
        ask(4) { if (!forgot) message(1) { message(1) { bytes(1, mine) }; message(2) { bytes(1, mine) } } }
        ask(8)
        ask(14) { message(1) { for (p in places) message(1) { bytes(1, p); message(3) { varint(1, now); varint(2, 1) } } }; varint(2, 1) }
        ask(24) { message(1) { for (l in lines) message(1) { bytes(1, l); varint(2, 9) } }; varint(2, 115) }
        // A FULL session asks from transaction 0.
        return ask(18) { varint(2, 0); varint(3, 115) }
    }

    /** A session the watch starts, which is always incremental, up to the change summary. */
    private fun ExploreSession.watchOpens(since: Int, places: ProtoWriter.() -> Unit = {}): ProtoReader {
        val mine = collectionId().unhex()
        handle(msg(1) { varint(1, 2) })
        ask(4) { message(1) { message(1) { bytes(1, mine) } } }
        ask(8)
        ask(14) { places(); varint(2, 1) }
        ask(24) { varint(2, 115) }
        return ask(18) { varint(1, since); varint(3, 115) }
    }

    private fun ExploreSession.collection(): List<ProtoReader> {
        val digest = ask(10) { varint(1, 0); varint(2, 1); varint(4, 2) }
        val uuid = digest.message(2)!!.message(1)!!.bytes(1)!!
        val items = mutableListOf<ProtoReader>()
        var offset = 0
        while (true) {
            val page = ask(6) { message(1) { message(1) { bytes(1, uuid); varint(2, 2) }; varint(2, offset); varint(3, 2) } }
            items += page.message(2)!!.message(3)!!.messages(1)
            if (page.varint(1) == 0L) return items
            offset = page.varint(3).toInt()
        }
    }

    private fun ExploreSession.ok() = handle(msg(3) { varint(1, 1) })

    /** Every collection the phone serves, by UUID, with the items in each. */
    private fun ExploreSession.collections(): Map<String, List<ProtoReader>> {
        val out = LinkedHashMap<String, List<ProtoReader>>()
        var at = 0
        while (true) {
            val digest = ask(10) { varint(1, 0); varint(2, 1); varint(3, at); varint(4, 2) }
            val entry = digest.message(2)?.messages(1)?.firstOrNull() ?: return out
            val uuid = entry.bytes(1)!!
            val items = mutableListOf<ProtoReader>()
            var offset = 0
            while (true) {
                val page = ask(6) { message(1) { message(1) { bytes(1, uuid); varint(2, 2) }; varint(2, offset); varint(3, 2) } }
                items += page.message(2)!!.message(3)?.messages(1).orEmpty()
                if (page.varint(1) == 0L) break
                offset = page.varint(3).toInt()
            }
            out[uuid.hex()] = items
            at++
        }
    }

    @Test
    fun `each Tracks collection is a collection of its own on the watch`() {
        val host = FakeHost().apply { wanted = listOf(course) to listOf(place.copy(group = "alps")) }
        val s = ExploreSession(host)
        s.openTo()
        val served = s.collections()
        assertEquals(2, served.size, "the default and Alps")
        assertEquals(listOf(3L), served.getValue(s.collectionId()).map { it.varint(4) }, "the course in the default")
        assertEquals(listOf(0L), served.values.last().map { it.varint(4) }, "the place in its own")
        val declared = s.ask(4).message(2)!!.messages(1).map { it.bytes(1)!!.hex() }
        assertEquals(served.keys.toList(), declared)
    }

    @Test
    fun `moving an item to another collection takes it out of the old one`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place.copy(group = "alps")) }
        val s = ExploreSession(host)
        s.openTo(); val before = s.collections(); s.ok()
        val alps = before.keys.last()
        val placeId = before.getValue(alps).single().bytes(1)!!

        host.wanted = emptyList<DesiredCourse>() to listOf(place.copy(group = "dolomites"))
        s.openTo(places = listOf(placeId))
        val after = s.collections()
        assertEquals(1L, after.getValue(alps).single().varint(3), "tombstoned in Alps")
        val moved = after.entries.single { it.key != alps && it.key != s.collectionId() }.value.single()
        assertEquals(0L, moved.varint(3), "live in Dolomites")
        assertTrue(moved.bytes(1)!!.hex() != placeId.hex(), "under a new UUID")
    }

    @Test
    fun `an item in a collection the watch has not taken up yet is not read as deleted`() {
        // The watch lists a new collection a session after it is declared;
        // until then its items are absent from every FULL listing.
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place.copy(group = "alps")) }
        val s = ExploreSession(host)
        s.openTo(); s.collections(); s.ok()
        s.openTo(); s.ok()   // lists only the default collection, and no places
        assertTrue(host.reports.last().waypointsDeletedOnWatch.isEmpty())
        assertTrue(s.unconfirmed().second.isEmpty(), "still wanted, still confirmed")
    }

    @Test
    fun `a FULL session asking with no range is served what is new`() {
        // Measured 2026-10-13: in a FULL session the watch asks for places and
        // lines as `{4: 1}`, no bounds. Nothing was served, so nothing new
        // ever arrived, while the session finished OK.
        val host = FakeHost().apply { wanted = listOf(course) to listOf(place) }
        val s = ExploreSession(host)
        s.openTo()
        val places = s.ask(16) { varint(4, 1) }.message(2)?.messages(1).orEmpty()
        val lines = s.ask(26) { varint(4, 1); varint(5, 115) }.message(2)?.messages(1).orEmpty()
        assertEquals(1, places.size, "the new place")
        assertEquals(1, lines.size, "the new course")
        s.ok()
        // Confirmed now: a later FULL session does not serve it again.
        val placeId = s.collections().values.flatten().first { it.varint(4) == 0L }.bytes(1)!!
        val lineId = s.collections().values.flatten().first { it.varint(4) == 3L }.bytes(1)!!
        s.openTo(places = listOf(placeId), lines = listOf(lineId))
        assertTrue(s.ask(16) { varint(4, 1) }.message(2)?.messages(1).isNullOrEmpty())
    }

    @Test
    fun `a new collection is declared in the session that first serves it`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place.copy(group = "alps")) }
        val s = ExploreSession(host)
        s.startRequest(); s.handle(msg(2) { varint(1, 1) })
        val declared = s.ask(4).message(2)!!.messages(1).map { it.bytes(1)!!.hex() }
        assertEquals(2, declared.size, "the default and Alps, before anything is planned")
    }

    @Test
    fun `a collection emptied and settled is declared retired`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place.copy(group = "alps")) }
        val s = ExploreSession(host)
        s.openTo(); val alps = s.collections().keys.last(); s.ok()
        host.wanted = emptyList<DesiredCourse>() to emptyList()
        s.openTo(places = listOf(s.collections().getValue(alps).single().bytes(1)!!)); s.ok()
        // Its last item's removal is confirmed; the next session retires it.
        s.openTo(); s.ok()
        val declared = s.ask(4).message(2)!!.messages(1)
        assertEquals(1L, declared.single { it.bytes(1)!!.hex() == alps }.varint(3), "declared tombstoned")
    }

    @Test
    fun `the phone declares its own collection as its default`() {
        val s = ExploreSession(FakeHost())
        s.handle(msg(1) { varint(1, 1) })
        val declared = s.ask(4).message(2)!!
        val mine = declared.message(1)!!.bytes(1)!!.hex()
        assertEquals(mine, declared.message(2)!!.bytes(1)!!.hex(), "declared and default are the same collection")
    }

    @Test
    fun `every session Tracks starts is FULL`() {
        // Only a FULL session has the watch report a course deleted on it.
        val s = ExploreSession(FakeHost())
        assertEquals(1L, ProtoReader(s.startRequest()).message(1)!!.varint(1))
    }

    @Test
    fun `a course is put on the watch as a line the watch pulls`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        val summary = s.openTo()
        assertEquals(1L, summary.varint(1), "one past the last transaction")
        assertEquals(listOf(1L, 1L, 1L), listOf(summary.varint(3), summary.varint(4), summary.varint(5)))

        val item = s.collection().single()
        assertEquals(3L, item.varint(4), "a course item")
        assertEquals(0L, item.varint(3))
        assertEquals(now, item.message(2)!!.varint(1), "stamped in Unix seconds")
        val uuid = item.bytes(1)!!

        val line = s.ask(26) { varint(1, 0); varint(2, 1); varint(4, 1); varint(5, 115) }.message(2)!!.message(1)!!
        assertEquals(uuid.hex(), line.bytes(1)!!.hex())
        assertEquals(9L, line.varint(2), "a course line")

        val name = s.ask(30) { message(1) { bytes(1, uuid); varint(2, 1); varint(5, 115) } }
        assertEquals("testcourse1", name.message(2)!!.message(4)!!.string(2))

        val points = s.ask(30) { message(1) { bytes(1, uuid); varint(2, 2); varint(3, 0); varint(4, 10); varint(5, 115) } }
        val part = points.message(2)!!.message(8)!!
        assertEquals(3, part.fields.count { it.number == 2 }, "one position per vertex")
        assertEquals(0L, points.varint(1), "three points fit in one page")

        s.ok()
        assertEquals(setOf(7), host.reports.single().coursesOn)
    }

    @Test
    fun `a course is taken off by serving its item tombstoned, and the line digest empties`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        s.openTo(); val uuid = s.collection().single().bytes(1)!!; s.ok()

        host.wanted = emptyList<DesiredCourse>() to emptyList()
        assertEquals(2L, s.openTo(lines = listOf(uuid)).varint(1))
        val item = s.collection().single()
        assertEquals(1L, item.varint(3), "tombstoned")
        assertEquals(3L, item.varint(4))
        val lines = s.ask(26) { varint(1, 1); varint(2, 2); varint(4, 1) }
        assertEquals(0, lines.bytes(2)!!.size, "no line is served for a removed course")
        s.ok()
        assertEquals(setOf(7), host.reports.last().coursesOff)
    }

    @Test
    fun `a failed session confirms nothing and the change is announced again`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        assertEquals(1L, s.openTo().varint(1))
        s.handle(msg(3) { varint(1, 2) })
        assertTrue(host.reports.single().failed)
        assertEquals(setOf(7), s.unconfirmed().first)
        assertEquals(1L, s.openTo().varint(1), "the same transaction again")
    }

    @Test
    fun `a course deleted on the watch is reported and not sent again`() {
        // Measured 2026-10-13: in a FULL session the watch lists the deleted
        // course with `3: 1`.
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        s.openTo(); val uuid = s.collection().single().bytes(1)!!; s.ok()

        startFullWith(s) { ask24 -> ask24.message(1) { message(1) { bytes(1, uuid); varint(2, 9); varint(3, 1) } } }
        s.ok()
        assertEquals(setOf(7), host.reports.last().coursesDeletedOnWatch)
        assertTrue(s.unconfirmed().first.isEmpty())
    }

    private fun startFullWith(s: ExploreSession, lines: (ProtoWriter) -> Unit) {
        val mine = s.collectionId().unhex()
        s.startRequest(); s.handle(msg(2) { varint(1, 1) })
        s.ask(4) { message(1) { message(1) { bytes(1, mine) } } }
        s.ask(8); s.ask(14) { varint(2, 1) }
        s.ask(24) { lines(this); varint(2, 115) }
        s.ask(18) { varint(2, 0) }
    }

    @Test
    fun `a session the watch starts announces nothing and is followed by a FULL one`() {
        // Announcing makes the watch re-read the collection; an incremental
        // session has not heard of a course deleted on the watch, and serving
        // it live there put it straight back (2026-10-13, test1).
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        assertEquals(5L, s.watchOpens(since = 5).varint(1), "nothing announced")
        s.ok()
        val follow = ProtoReader(host.sent.last()).message(1)!!
        assertEquals(1L, follow.varint(1), "a FULL session straight after")
    }

    @Test
    fun `the in-progress line is read as Explore reads it`() {
        // Capture c, every session: points (part 2), then part 16, then done.
        val s = ExploreSession(FakeHost())
        s.handle(msg(1) { varint(1, 2) })
        val line = "7b8b21c600000080001000002b030000".unhex()
        val ask = s.ask(32) {
            message(1) {
                message(1) { varint(1, 0); varint(2, 1); bytes(3, line); varint(4, 3); varint(5, 0) }
                message(1) { varint(1, 1); varint(2, 0) }
            }
            varint(2, 115)
        }.message(2)!!
        assertEquals(line.hex(), ask.bytes(1)!!.hex())
        assertEquals(2L, ask.varint(2))
        val second = s.ask(28) { varint(1, 0); message(2) { bytes(1, line); message(8) { } }; varint(3, 0) }.message(1)!!
        assertEquals(16L, second.varint(2))
        assertTrue(!s.ask(28) { varint(1, 0); message(2) { bytes(1, line); message(7) { } }; varint(3, 10) }.has(1), "then nothing")
    }

    @Test
    fun `a recorded line is read through every part and page`() {
        // Capture c at t = 564: part 2 paged by the watch's offsets, then 1, 4, 16.
        val s = ExploreSession(FakeHost())
        s.handle(msg(1) { varint(1, 2) })
        val line = "7b8b21c600000080001000002c030000".unhex()
        val first = s.ask(24) {
            message(1) { message(1) { bytes(1, line); varint(2, 2) } }
            varint(2, 115)
        }.message(2)!!
        assertEquals(2L, first.varint(2))
        fun page(more: Boolean, part: Int, next: Int) = s.ask(28) {
            varint(1, if (more) 1 else 0)
            message(2) { bytes(1, line); message(ExploreSession.PART_FIELDS.getValue(part)) { } }
            varint(3, next)
        }.message(1)
        assertEquals(10L, page(true, 2, 10)!!.varint(3), "the next page from the watch's offset")
        assertEquals(1L, page(false, 2, 0)!!.varint(2))
        assertEquals(4L, page(false, 1, 10)!!.varint(2))
        assertEquals(16L, page(false, 4, 10)!!.varint(2))
        assertEquals(null, page(false, 16, 10), "then nothing")
    }

    @Test
    fun `a course is not read back when the watch lists it`() {
        // Explore answers course lines in the digest with a bare {1: 0}.
        val s = ExploreSession(FakeHost())
        s.handle(msg(1) { varint(1, 2) })
        val reply = s.ask(24) { message(1) { message(1) { bytes(1, ByteArray(16) { 7 }); varint(2, 9) } }; varint(2, 115) }
        assertTrue(!reply.has(2))
    }

    @Test
    fun `a place deleted on the watch is read from the watch's side of the collection`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); val item = s.collection().single(); s.ok()
        val coll = s.collectionId().unhex()

        s.startRequest(); s.handle(msg(2) { varint(1, 1) })
        s.ask(4) { message(1) { message(1) { bytes(1, coll) } } }
        val op = s.ask(8) {
            message(1) { message(1) { bytes(1, coll); varint(3, 1); message(4) { varint(1, 65) } } }
        }.message(2)
        assertEquals(coll.hex(), op?.message(1)?.bytes(1)?.hex(), "asks for the collection")
        val ack = s.ask(22) {
            varint(1, 0)
            message(2) {
                bytes(1, coll)
                message(3) { message(1) { bytes(1, item.bytes(1)!!); message(2) { varint(1, now + 5) }; varint(3, 1); varint(4, 0) } }
            }
        }
        assertTrue(!ack.has(1), "nothing more to read")
        s.ask(14) { varint(2, 1) }; s.ask(24) { varint(2, 115) }
        s.ask(18) { varint(2, 0) }
        s.ok()
        assertEquals(setOf(3), host.reports.last().waypointsDeletedOnWatch)
        assertTrue(s.unconfirmed().second.isEmpty(), "not sent again")
    }

    @Test
    fun `a place deleted by hand on the watch is agreed to, not served live again`() {
        // 2026-10-12: two places deleted on the watch came back in its waypoint
        // digest flagged `2: 1`, in the incremental session the watch started.
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); val item = s.collection().single(); s.ok()

        s.watchOpens(since = 1) {
            message(1) { message(1) { bytes(1, item.bytes(1)!!); varint(2, 1); message(3) { varint(1, now + 9); varint(2, 1) } } }
        }
        assertEquals(1L, s.collection().single().varint(3), "served tombstoned from now on")
        s.ok()
        assertEquals(setOf(3), host.reports.last().waypointsDeletedOnWatch)
    }

    @Test
    fun `a delivered place missing from a FULL listing was deleted on the watch`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place, place.copy(waypointId = 4)) }
        val s = ExploreSession(host)
        s.openTo(); val items = s.collection(); s.ok()
        s.openTo(places = listOf(items.first().bytes(1)!!)); s.ok()
        assertEquals(1, host.reports.last().waypointsDeletedOnWatch.size)
    }

    @Test
    fun `a delivered course missing from a FULL line listing was deleted on the watch`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        s.openTo(); s.collection(); s.ok()
        s.openTo(); s.ok()
        assertEquals(setOf(7), host.reports.last().coursesDeletedOnWatch)
    }

    @Test
    fun `what the watch holds is left alone in a FULL session`() {
        val host = FakeHost().apply { wanted = listOf(course) to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); val items = s.collection(); s.ok()
        val placeId = items.first { it.varint(4) == 0L }.bytes(1)!!
        val lineId = items.first { it.varint(4) == 3L }.bytes(1)!!
        s.openTo(places = listOf(placeId), lines = listOf(lineId)); s.ok()
        assertTrue(host.reports.last().coursesDeletedOnWatch.isEmpty())
        assertTrue(host.reports.last().waypointsDeletedOnWatch.isEmpty())
    }

    @Test
    fun `a watch that keeps failing gets a fresh collection, the old one declared tombstoned`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); val before = s.collection().single(); s.ok()
        val old = s.collectionId()
        repeat(ExploreSession.RETIRE_AFTER_FAILURES) {
            s.handle(msg(1) { varint(1, 2) }); s.handle(msg(3) { varint(1, 2) })
        }
        val declared = s.ask(4).message(2)!!
        assertTrue(declared.message(2)!!.bytes(1)!!.hex() != old, "a new default collection")
        assertEquals(1L, declared.messages(1).single { it.bytes(1)!!.hex() == old }.varint(3), "the old one tombstoned")
        assertTrue(s.openTo().varint(1) > 0, "the place is announced again")
        assertTrue(s.collection().single().bytes(1)!!.hex() != before.bytes(1)!!.hex(), "under a new UUID")
    }

    @Test
    fun `a factory-reset watch is given everything again, not told it deleted it`() {
        val host = FakeHost().apply { wanted = listOf(course) to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); s.collection(); s.ok()
        val mine = s.collectionId()

        assertTrue(s.openTo(forgot = true).varint(1) > 0, "everything announced again")
        assertEquals(mine, s.collectionId(), "into the same collection, nothing stranded")
        assertEquals(2, s.collection().count { it.varint(3) == 0L })
        s.ok()
        assertTrue(host.reports.last().coursesDeletedOnWatch.isEmpty())
        assertTrue(host.reports.last().waypointsDeletedOnWatch.isEmpty())
    }

    @Test
    fun `nothing is read as deleted from a watch that does not list Tracks' collection`() {
        val host = FakeHost().apply { wanted = listOf(course) to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); s.collection(); s.ok()
        // Not yet known (as straight after a new collection): no reset, no omission rule.
        val lib = WatchLibrary.restore(host.state).apply { collectionKnown = false }
        host.state = lib.serialise()
        s.openTo(forgot = true); s.ok()
        assertTrue(host.reports.last().coursesDeletedOnWatch.isEmpty())
        assertTrue(host.reports.last().waypointsDeletedOnWatch.isEmpty())
    }

    @Test
    fun `a FULL session from transaction 0 is not mistaken for a reset`() {
        // Every FULL session asks from 0; read as a reset, it replaced the
        // collection each time and stranded the old one on the watch.
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        s.openTo(); val line = s.collection().single().bytes(1)!!; s.ok()
        val mine = s.collectionId()
        assertEquals(0L, s.openTo(lines = listOf(line)).varint(1), "nothing to announce")
        assertEquals(mine, s.collectionId())
    }

    @Test
    fun `where Tracks put a place is remembered after the place is gone`() {
        // The watch's saved-places file has no ids; position is all that tells
        // Tracks' places from ones made on the watch, deleted ones included.
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); s.ok()
        host.wanted = emptyList<DesiredCourse>() to emptyList()
        s.openTo()
        assertTrue(WatchLibrary.placeKey(place.lat, place.lng) in s.sentPlaces())
    }

    @Test
    fun `an incremental listing is not taken as everything the watch holds`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); s.collection(); s.ok()
        s.watchOpens(since = 1); s.ok()
        assertTrue(host.reports.last().waypointsDeletedOnWatch.isEmpty())
    }

    @Test
    fun `a place the watch merely re-saved is left alone`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(); val item = s.collection().single(); s.ok()
        s.watchOpens(since = 1) {
            message(1) { message(1) { bytes(1, item.bytes(1)!!); message(3) { varint(1, now); varint(2, 1) } } }
        }
        assertEquals(0L, s.collection().single().varint(3))
    }

    @Test
    fun `the collection digest is paged by the watch's offset`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo()
        assertEquals(1, s.ask(10) { varint(1, 0); varint(2, 1); varint(4, 2) }.message(2)!!.messages(1).size)
        val past = s.ask(10) { varint(1, 0); varint(2, 1); varint(3, 1); varint(4, 2) }
        assertTrue(past.message(2)?.messages(1).isNullOrEmpty(), "nothing past the end")
    }

    @Test
    fun `a saved place is served as a waypoint record`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo()
        val item = s.collection().single()
        assertEquals(0L, item.varint(4), "a waypoint item")
        val digest = s.ask(16) { varint(1, 0); varint(2, 1); varint(4, 1) }.message(2)!!.message(1)!!
        val record = s.ask(12) { message(1) { message(1) { bytes(1, digest.bytes(1)!!); varint(2, 1) } } }
        assertEquals("test way point 1", record.message(2)!!.message(2)!!.string(3))
    }

    @Test
    fun `with no word from the app nothing is planned`() {
        val host = FakeHost().apply { wanted = null }
        val s = ExploreSession(host)
        assertEquals(0L, s.openTo().varint(1), "nothing announced")
    }

    @Test
    fun `positions encode as Explore's do`() {
        // Capture c: longitude in the high word, latitude in the low, both
        // signed semicircles. A synthetic point, not one from the capture.
        val v = ExploreSession.position(-10.5, -170.25)
        assertEquals(-10.5, (v and 0xffffffffL).toInt() * 180.0 / 2147483648.0, 1e-6)
        assertEquals(-170.25, (v ushr 32).toInt() * 180.0 / 2147483648.0, 1e-6)
    }

    @Test
    fun `the explore package imports nothing from Gadgetbridge`() {
        val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "mobile/device-garmin/src/main/java/com/tracks/device/garmin/explore") }
            .plus(sequenceOf(File("src/main/java/com/tracks/device/garmin/explore")))
            .first { it.isDirectory }
        val offenders = dir.listFiles()!!.filter { it.readText().contains("nodomain.freeyourgadget") }
        assertTrue(offenders.isEmpty(), "Gadgetbridge imports in $offenders — this package must stay independent")
    }

    /**
     * The collection's UUID is the phone's identity on the watch. Measured
     * 2026-10-12: it changed between two sessions, which strands everything
     * the first one put on the watch in a collection Tracks no longer claims.
     */
    @Test
    fun `the collection keeps its identity across sessions`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val first = ExploreSession(host)
        first.handle(msg(1) { varint(1, 1) })
        val a = first.ask(4).message(2)!!.message(1)!!.bytes(1)!!.hex()
        first.openTo(); first.ok()
        val second = ExploreSession(host)
        second.handle(msg(1) { varint(1, 2) })
        assertEquals(a, second.ask(4).message(2)!!.message(1)!!.bytes(1)!!.hex())
        assertEquals(a, WatchLibrary.restore(WatchLibrary().also { it.collection = a }.serialise()).collection)
    }

    /**
     * A session the watch abandoned without its finish must not hold every
     * later sync hostage. Before, only the finish message closed a session, so
     * after a dropped link each request just queued as a follow-up and courses
     * stopped moving until the app was force-stopped.
     */
    @Test
    fun `a session the watch went silent in is replaced by a fresh one`() {
        val host = FakeHost()
        val s = ExploreSession(host)
        s.requestSync()
        assertEquals(1, host.sent.size, "the first request starts a session")
        s.requestSync()
        assertEquals(1, host.sent.size, "a second, while it is live, waits its turn")
        now += ExploreSession.STALE_SESSION_SECONDS + 1
        s.requestSync()
        assertEquals(2, host.sent.size, "once the session has gone silent, a new one starts")
    }

    /**
     * The watch's in-progress line is the track so far, and Explore draws its
     * live map from it. Tracks used to read it and throw it away; now the
     * points reach the map. Positions come packed, as the watch writes them.
     */
    @Test
    fun `the in-progress line's points reach the host`() {
        val host = FakeHost()
        val s = ExploreSession(host)
        val uuid = "0123456789abcdef".toByteArray()
        s.handle(msg(1) { varint(1, 2) })
        // active_line_digest_write: the in-progress line's uuid.
        s.handle(msg(32) { message(1) { message(1) { bytes(3, uuid) } } })
        val packed = java.io.ByteArrayOutputStream().apply {
            for ((lat, lng) in listOf(46.5 to 7.25, 46.501 to 7.251)) {
                val pos = ExploreSession.position(lat, lng)
                for (i in 0 until 8) write(((pos ushr (8 * i)) and 0xff).toInt())
            }
        }.toByteArray()
        // line_data_ready: the points part (field 8), complete in one page.
        s.handle(msg(ExploreSession.F_LINE_DATA_READY) {
            varint(1, 0)
            message(2) { bytes(1, uuid); varint(2, 3); message(8) { bytes(2, packed); varint(9, 0) } }
        })
        val track = host.activeTracks.single()
        assertEquals(2, track.size)
        assertEquals(46.5, track[0].lat, 1e-6)
        assertEquals(7.251, track[1].lng, 1e-6)
    }

    /**
     * The watch writes a point every second from the start button, with or
     * without a fix; the ones without are 0x7FFFFFFF in both halves (capture
     * c's first page held nine of them). Read as positions, they put the
     * line's start at 180 degrees north.
     */
    @Test
    fun `points recorded before the watch had a fix are left out`() {
        val host = FakeHost()
        val s = ExploreSession(host)
        val uuid = "0123456789abcdef".toByteArray()
        s.handle(msg(1) { varint(1, 2) })
        s.handle(msg(32) { message(1) { message(1) { bytes(3, uuid) } } })
        val noFix = (0x7fffffffL shl 32) or 0x7fffffffL
        val packed = java.io.ByteArrayOutputStream().apply {
            for (pos in listOf(noFix, noFix, ExploreSession.position(46.5, 7.25))) {
                for (i in 0 until 8) write(((pos ushr (8 * i)) and 0xff).toInt())
            }
        }.toByteArray()
        s.handle(msg(ExploreSession.F_LINE_DATA_READY) {
            varint(1, 0)
            message(2) { bytes(1, uuid); varint(2, 3); message(8) { bytes(2, packed) } }
        })
        val track = host.activeTracks.single()
        assertEquals(1, track.size)
        assertEquals(46.5, track.single().lat, 1e-6)
    }
}
