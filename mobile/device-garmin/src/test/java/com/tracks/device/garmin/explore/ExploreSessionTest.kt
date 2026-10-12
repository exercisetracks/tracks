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

    private val now = 1791843474L

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
    }

    private val course = DesiredCourse(
        7, "testcourse1",
        listOf(Triple(10.0, 20.0, 575.0), Triple(10.001, 20.0, 576.0), Triple(10.002, 19.999, null)), 0,
    )
    private val place = DesiredWaypoint(3, "test way point 1", 10.0, 20.0, 577.0, 0)

    private fun msg(field: Int, build: ProtoWriter.() -> Unit = {}) = ProtoWriter().message(field, build).toByteArray()
    private fun ExploreSession.ask(field: Int, build: ProtoWriter.() -> Unit = {}) =
        ProtoReader(handle(msg(field, build))!!).message(field + 1)!!

    /** The watch's side of a session up to the change summary, as capture c orders it. */
    private fun ExploreSession.openTo(since: Int): ProtoReader {
        handle(msg(1) { varint(1, 2); varint(2, 2); varint(4, 10) })
        ask(4)
        ask(8)
        ask(14)
        ask(24) { varint(2, 115) }
        return ask(18) { varint(1, since); varint(2, 0); varint(3, 115) }
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

    @Test
    fun `the phone declares its own collection as its default`() {
        val s = ExploreSession(FakeHost())
        s.handle(msg(1) { varint(1, 1) })
        val declared = s.ask(4).message(2)!!
        val mine = declared.message(1)!!.bytes(1)!!.hex()
        assertEquals(mine, declared.message(2)!!.bytes(1)!!.hex(), "declared and default are the same collection")
    }

    @Test
    fun `a course is put on the watch as a line the watch pulls`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        val summary = s.openTo(since = 2)
        assertEquals(3L, summary.varint(1), "one past the watch's transaction")
        assertEquals(listOf(1L, 1L, 1L), listOf(summary.varint(3), summary.varint(4), summary.varint(5)))

        val item = s.collection().single()
        assertEquals(3L, item.varint(4), "a course item")
        assertEquals(0L, item.varint(3))
        assertEquals(now, item.message(2)!!.varint(1), "stamped in Unix seconds")
        val uuid = item.bytes(1)!!

        val line = s.ask(26) { varint(1, 2); varint(2, 3); varint(4, 1); varint(5, 115) }.message(2)!!.message(1)!!
        assertEquals(uuid.hex(), line.bytes(1)!!.hex())
        assertEquals(9L, line.varint(2), "a course line")

        val name = s.ask(30) { message(1) { bytes(1, uuid); varint(2, 1); varint(5, 115) } }
        assertEquals("testcourse1", name.message(2)!!.message(4)!!.string(2))

        val points = s.ask(30) { message(1) { bytes(1, uuid); varint(2, 2); varint(3, 0); varint(4, 10); varint(5, 115) } }
        val part = points.message(2)!!.message(8)!!
        assertEquals(3, part.fields.count { it.number == 2 }, "one position per vertex")
        assertEquals(0L, points.varint(1), "three points fit in one page")

        s.handle(msg(3) { varint(1, 1) })
        assertEquals(setOf(7), host.reports.single().coursesOn)
    }

    @Test
    fun `a course is taken off by serving its item tombstoned, and the line digest empties`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        s.openTo(since = 2); s.collection(); s.handle(msg(3) { varint(1, 1) })

        host.wanted = emptyList<DesiredCourse>() to emptyList()
        assertEquals(4L, s.openTo(since = 3).varint(1))
        val item = s.collection().single()
        assertEquals(1L, item.varint(3), "tombstoned")
        assertEquals(3L, item.varint(4))
        val lines = s.ask(26) { varint(1, 3); varint(2, 4); varint(4, 1) }
        assertEquals(0, lines.bytes(2)!!.size, "no line is served for a removed course")
        s.handle(msg(3) { varint(1, 1) })
        assertEquals(setOf(7), host.reports.last().coursesOff)
    }

    @Test
    fun `a failed session confirms nothing and the change is announced again`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        assertEquals(3L, s.openTo(since = 2).varint(1))
        s.handle(msg(3) { varint(1, 2) })
        assertTrue(host.reports.single().failed)
        assertEquals(setOf(7), s.unconfirmed().first)
        assertEquals(3L, s.openTo(since = 2).varint(1), "the same transaction again")
    }

    @Test
    fun `a course deleted on the watch is reported and not sent again`() {
        val host = FakeHost().apply { wanted = listOf(course) to emptyList() }
        val s = ExploreSession(host)
        s.openTo(since = 2); val uuid = s.collection().single().bytes(1)!!; s.handle(msg(3) { varint(1, 1) })

        s.handle(msg(1) { varint(1, 2) })
        s.ask(4); s.ask(8); s.ask(14)
        s.ask(24) { message(1) { message(1) { bytes(1, uuid); varint(2, 9); varint(3, 1) } } }
        s.ask(18) { varint(1, 3) }
        s.handle(msg(3) { varint(1, 1) })
        assertEquals(setOf(7), host.reports.last().coursesDeletedOnWatch)
        assertTrue(s.unconfirmed().first.isEmpty())
    }

    @Test
    fun `a saved place is served as a waypoint record`() {
        val host = FakeHost().apply { wanted = emptyList<DesiredCourse>() to listOf(place) }
        val s = ExploreSession(host)
        s.openTo(since = 0)
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
        assertEquals(2L, s.openTo(since = 2).varint(1), "nothing announced")
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
        first.openTo(since = 0); first.handle(msg(3) { varint(1, 1) })
        val second = ExploreSession(host)
        second.handle(msg(1) { varint(1, 2) })
        assertEquals(a, second.ask(4).message(2)!!.message(1)!!.bytes(1)!!.hex())
        assertEquals(a, WatchLibrary.restore(WatchLibrary().also { it.collection = a }.serialise()).collection)
    }
}
