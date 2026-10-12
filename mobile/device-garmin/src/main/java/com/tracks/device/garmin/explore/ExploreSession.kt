// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.explore

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * The phone's side of a Garmin Explore sync, played the way Garmin Explore plays it.
 *
 * Every message shape here is copied from decoded captures of Explore and a
 * factory-reset fēnix 6X Pro Solar — see docs/garmin-explore-sync.md, which
 * also explains the model. In one paragraph: Tracks declares a collection it
 * owns; each course and saved place Tracks wants on the watch is an item in it
 * under a UUID Tracks generated; the watch asks "what changed since transaction
 * N?", reads the collection, then pulls each new course's line and each new
 * place's record; removing something is serving its item tombstoned. Nothing is
 * confirmed until the session finishes OK.
 *
 * No Gadgetbridge here: messages arrive and leave as the raw bytes of `Smart`
 * field 22, and the adapter that touches Gadgetbridge only moves bytes.
 */
class ExploreSession(private val host: Host) {

    interface Host {
        fun load(): String?
        fun save(state: String)
        /** Send a phone-initiated message (the body of `Smart` field 22). */
        fun send(message: ByteArray)
        fun log(message: String)
        /**
         * What Tracks wants on the watch, or null while the app has not said.
         * Null skips planning entirely: an empty list would mean "take
         * everything off".
         */
        fun wanted(): Pair<List<DesiredCourse>, List<DesiredWaypoint>>?
        fun onReport(report: ReconcileReport)
        fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
    }

    /**
     * Re-read from storage at every entry rather than cached: more than one
     * session object can exist for a watch, and storage is the only copy they
     * share. Measured 2026-10-12 — a cached copy overwrote a push record.
     */
    private var library = WatchLibrary()

    private inline fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        val stored = host.load()
        library = WatchLibrary.restore(stored)
        // A library made fresh here has just generated its collection's UUID.
        // Saved at once: declaring it to the watch changes nothing else, so the
        // save-on-change below would let the next entry generate another, and
        // everything the watch was given under the first would be stranded.
        if (!WatchLibrary.hasCollection(stored)) host.save(library.serialise())
        val before = library.serialise()
        val result = block()
        if (library.serialise() != before) host.save(library.serialise())
        result
    }

    // Session state; none of it outlives a session.
    private var open = false
    private var full = false
    private var followUp = false
    private var announcedFrom = -1
    private var announcedTo = -1
    private val deletedOnWatch = mutableSetOf<Int>()
    private var wantedNow: Pair<List<DesiredCourse>, List<DesiredWaypoint>>? = null

    // ── Asked by the app ────────────────────────────────────────────────────

    /** Courses and places waiting on the watch's confirmation. */
    fun unconfirmed(): Pair<Set<Int>, Set<Int>> = locked {
        val items = library.items.values.filter { !it.confirmed && !it.tombstone }
        items.filter { it.kind == WatchLibrary.KIND_COURSE }.mapTo(HashSet()) { it.ref } to
            items.filter { it.kind == WatchLibrary.KIND_WAYPOINT }.mapTo(HashSet()) { it.ref }
    }

    /**
     * Run a session now, or straight after the one in progress — two at once
     * interleave their replies.
     */
    fun requestSync() = locked {
        if (open) followUp = true else host.send(startLocked())
    }

    fun startRequest(): ByteArray = locked { startLocked() }

    private fun startLocked(): ByteArray {
        full = !library.baseline
        open = true
        resetSession()
        host.log("requesting a ${if (full) "FULL" else "INCREMENTAL"} sync")
        return ProtoWriter().message(F_START_REQUEST) {
            varint(1, if (full) SYNC_FULL else SYNC_INCREMENTAL)
            varint(2, PROTOCOL_VERSION)
            bytes(3, APP_UUID)
            varint(4, SYNC_CAPABILITIES)
            for (c in intArrayOf(4, 8, 2)) message(5) { varint(1, c); varint(2, 0) }
        }.toByteArray()
    }

    // ── From the watch ──────────────────────────────────────────────────────

    /** One Explore message in; the reply, or null when none is owed. */
    fun handle(message: ByteArray): ByteArray? = locked {
        try {
            dispatch(ProtoReader(message))
        } catch (e: Exception) {
            host.log("unreadable message ${message.hex()}: $e")
            null
        }
    }

    private fun dispatch(msg: ProtoReader): ByteArray? {
        val field = msg.fields.firstOrNull() ?: return null
        val body = msg.message(field.number) ?: ProtoReader(ByteArray(0))
        return when (field.number) {
            F_START_REQUEST -> watchStarted(body)
            F_START_RESPONSE -> {
                val ok = body.varint(1) == 1L
                host.log(if (ok) "watch accepted our sync" else "watch refused our sync: ${body.varint(1)}")
                if (!ok) open = false
                null
            }
            F_COLLECTION_LIST_WRITE -> declare()
            F_LINE_DIGEST_WRITE -> watchLines(body)
            F_CHANGE_SUMMARY_REQUEST -> changeSummary(body)
            F_COLLECTION_DIGEST_READ -> collectionDigest()
            F_COLLECTION_READ -> collectionPage(body)
            F_WAYPOINT_DIGEST_READ -> waypointDigest(body)
            F_WAYPOINT_READ -> waypoint(body)
            F_LINE_DIGEST_READ -> lineDigest(body)
            F_LINE_READ -> linePart(body)
            F_SYNC_FINISHED -> finished(body)
            F_COLLECTION_DIGEST_WRITE, F_WAYPOINT_DIGEST_WRITE, F_ACTIVE_LINE_DIGEST_WRITE ->
                reply(field.number + 1) { varint(1, 0) }
            F_COLLECTION_PAGE -> reply(F_COLLECTION_PAGE + 1) {}
            F_LINE_DATA_READY -> reply(F_LINE_DATA_READY + 1) {}
            else -> {
                host.log("no answer for message ${field.number}")
                null
            }
        }
    }

    private fun watchStarted(body: ProtoReader): ByteArray {
        open = true
        full = body.varint(1) == SYNC_FULL.toLong()
        resetSession()
        host.log("watch started a ${if (full) "FULL" else "INCREMENTAL"} sync")
        return reply(F_START_RESPONSE) { varint(1, 1); varint(3, PROTOCOL_VERSION); bytes(4, APP_UUID) }
    }

    /** Tracks' collection, declared as its own and as the default, exactly as Explore declares its. */
    private fun declare(): ByteArray {
        val c = library.collection.unhex()
        host.log("declaring collection ${library.collection}")
        return reply(F_COLLECTION_LIST_WRITE + 1) {
            varint(1, 0)
            message(2) {
                message(1) { bytes(1, c); message(2) { varint(1, library.collectionStamp); varint(2, 0) }; varint(3, 0) }
                message(2) { bytes(1, c); message(2) { varint(1, 0); varint(2, 0) } }
            }
        }
    }

    /**
     * Lines that changed on the watch. One of Tracks' own courses listed with
     * field 3 = 1 was deleted on the watch: it is tombstoned here to agree, and
     * reported, and not sent again.
     */
    private fun watchLines(body: ProtoReader): ByteArray {
        val lines = body.message(1)?.messages(1).orEmpty()
        val ours = library.items.values.filter { it.kind == WatchLibrary.KIND_COURSE && !it.tombstone }
            .associateBy { it.uuid }
        for (l in lines) {
            val uuid = l.bytes(1)?.hex() ?: continue
            val item = ours[uuid] ?: continue
            if (l.varint(3) == 1L) {
                host.log("course ${item.ref} ($uuid) was deleted on the watch")
                tombstone(library, item, host.nowSeconds())
                // The watch already has it gone: nothing to announce for it.
                library.items[uuid] = library.items.getValue(uuid).copy(confirmed = true, txn = library.transaction)
                deletedOnWatch += item.ref
            }
        }
        host.log("watch line digest: ${lines.size} line(s)")
        return reply(F_LINE_DIGEST_WRITE + 1) { varint(1, 0) }
    }

    /** "What changed since transaction N?" — where Tracks' wishes become the library. */
    private fun changeSummary(body: ProtoReader): ByteArray {
        val since = body.varint(1).toInt()
        val lib = library
        val now = host.nowSeconds()
        wantedNow = host.wanted()
        wantedNow?.let { (courses, places) ->
            // Courses deleted on the watch stay out until Tracks asks again.
            plan(lib, courses.filterNot { it.trackId in deletedOnWatch }, places, now)
        } ?: host.log("the app has not said what it wants yet; serving the library as it stands")

        // Anything the watch has already moved past is something it has.
        for (i in lib.items.values.toList()) {
            if (!i.confirmed && i.txn != WatchLibrary.PENDING && i.txn <= since) {
                lib.items[i.uuid] = i.copy(confirmed = true)
            }
        }
        val pending = lib.items.values.filter { it.txn == WatchLibrary.PENDING }
        val unannounced = lib.items.values.filter { !it.confirmed && it.txn > since }
        if (pending.isEmpty() && unannounced.isEmpty()) {
            announcedFrom = -1; announcedTo = -1
            host.log("nothing to change (watch at transaction $since)")
            return reply(F_CHANGE_SUMMARY_REQUEST + 1) { varint(1, since) }
        }
        val ending = if (pending.isNotEmpty()) maxOf(since, lib.transaction) + 1
        else unannounced.maxOf { it.txn }
        for (i in pending) lib.items[i.uuid] = i.copy(txn = ending)
        lib.transaction = maxOf(lib.transaction, ending)
        lib.collectionStamp = maxOf(lib.collectionStamp, lib.items.values.maxOf { it.stamp })
        announcedFrom = since
        announcedTo = ending
        host.log("announcing transaction $since -> $ending: " +
            lib.items.values.filter { it.txn in (since + 1)..ending }
                .joinToString { "${kindName(it.kind)} ${it.ref}${if (it.tombstone) " off" else " on"}" })
        return reply(F_CHANGE_SUMMARY_REQUEST + 1) { varint(1, ending); varint(3, 1); varint(4, 1); varint(5, 1) }
    }

    private fun collectionDigest(): ByteArray = reply(F_COLLECTION_DIGEST_READ + 1) {
        varint(1, 0)
        message(2) {
            message(1) {
                bytes(1, library.collection.unhex()); varint(2, 0); varint(3, 1)
                message(4) { varint(1, library.collectionStamp); varint(2, 0) }
            }
        }
    }

    /** The whole collection, tombstones included, a page at a time. */
    private fun collectionPage(body: ProtoReader): ByteArray {
        val op = body.message(1)
        val asked = op?.message(1)?.bytes(1)?.hex()
        if (asked != library.collection) {
            host.log("watch asked for collection $asked, which is not Tracks'")
            return reply(F_COLLECTION_READ + 1) { varint(1, READ_ERROR) }
        }
        val items = library.items.values.toList()
        val offset = op.varint(2).toInt().coerceIn(0, items.size)
        val end = minOf(items.size, offset + op.varint(3, 2).toInt().coerceAtLeast(1))
        val more = end < items.size
        return reply(F_COLLECTION_READ + 1) {
            varint(1, if (more) READ_MORE else 0)
            message(2) {
                bytes(1, library.collection.unhex())
                message(3) {
                    for (i in items.subList(offset, end)) message(1) {
                        bytes(1, i.uuid.unhex())
                        message(2) { varint(1, i.stamp); varint(2, 0) }
                        varint(3, i.tombstone)
                        varint(4, i.kind)
                    }
                }
            }
            if (more) varint(3, end)
        }
    }

    /** Live items of [kind] changed within the watch's requested transaction range. */
    private fun changed(kind: Int, body: ProtoReader): List<WatchLibrary.Item> {
        val from = body.varint(1).toInt()
        val to = body.varint(2, Long.MAX_VALUE).toInt()
        return library.live(kind).filter { it.txn in (from + 1)..to }
    }

    /** One entry per page, as Explore serves them. */
    private fun digestPage(field: Int, entries: List<WatchLibrary.Item>, body: ProtoReader,
                           entry: ProtoWriter.(WatchLibrary.Item) -> Unit): ByteArray {
        val offset = body.varint(3).toInt()
        if (offset >= entries.size) return reply(field) { varint(1, 0); bytes(2, ByteArray(0)) }
        val more = offset + 1 < entries.size
        return reply(field) {
            varint(1, if (more) READ_MORE else 0)
            message(2) { message(1) { entry(entries[offset]) } }
            if (more) varint(3, offset + 1)
        }
    }

    private fun waypointDigest(body: ProtoReader): ByteArray =
        digestPage(F_WAYPOINT_DIGEST_READ + 1, changed(WatchLibrary.KIND_WAYPOINT, body), body) { i ->
            bytes(1, i.uuid.unhex()); varint(2, 0); message(3) { varint(1, i.stamp); varint(2, 0) }
        }

    private fun lineDigest(body: ProtoReader): ByteArray =
        digestPage(F_LINE_DIGEST_READ + 1, changed(WatchLibrary.KIND_COURSE, body), body) { i ->
            bytes(1, i.uuid.unhex()); varint(2, LINE_COURSE); varint(3, 0)
            for (f in 4..8) message(f) { varint(1, i.stamp); varint(2, 0) }
        }

    private fun waypoint(body: ProtoReader): ByteArray {
        val uuid = body.message(1)?.message(1)?.bytes(1)?.hex()
        val item = library.items[uuid]
        val place = item?.let { i -> wanted()?.second?.firstOrNull { it.waypointId == i.ref } }
        if (item == null || place == null) {
            host.log("watch asked for waypoint $uuid, which Tracks cannot describe")
            return reply(F_WAYPOINT_READ + 1) { varint(1, READ_ERROR) }
        }
        return reply(F_WAYPOINT_READ + 1) {
            varint(1, 0)
            message(2) {
                bytes(1, item.uuid.unhex())
                message(2) {
                    message(1) { varint(1, item.stamp); varint(2, 0) }
                    fixed64(2, position(place.lat, place.lng))
                    string(3, place.name.take(MAX_NAME))
                    varint(4, place.createdAt.takeIf { it > 0 } ?: item.stamp)
                    float(5, (place.elevation ?: 0.0).toFloat())
                    // Symbol and colour, as Explore sent them for a plain place.
                    varint(6, 8284); varint(7, 0); varint(8, 10); varint(9, 1)
                }
            }
        }
    }

    /** One part of a course's line: 1 summary, 2 points (paged), 4 stats, 8 and 16 metadata. */
    private fun linePart(body: ProtoReader): ByteArray {
        val req = body.message(1) ?: return reply(F_LINE_READ + 1) { varint(1, READ_ERROR) }
        val uuid = req.bytes(1)?.hex()
        val item = library.items[uuid]
        val course = item?.let { i -> wanted()?.first?.firstOrNull { it.trackId == i.ref } }
        if (item == null || course == null || course.points.size < 2) {
            host.log("watch asked for line $uuid, which Tracks cannot describe")
            return reply(F_LINE_READ + 1) { varint(1, READ_ERROR) }
        }
        val part = req.varint(2).toInt()
        val stamp = ProtoWriter().varint(1, item.stamp).varint(2, 0)
        val geo = Geometry(course.points)
        var more = false
        var next = -1
        val line = ProtoWriter().bytes(1, item.uuid.unhex()).varint(2, LINE_COURSE).varint(3, 0)
        when (part) {
            1 -> line.message(4) { message(1, stamp); string(2, course.name.take(MAX_NAME)); varint(3, course.createdAt.takeIf { it > 0 } ?: item.stamp) }
            2 -> {
                val offset = req.varint(3).toInt().coerceIn(0, geo.size)
                val end = minOf(geo.size, offset + req.varint(4, 10).toInt().coerceAtLeast(1))
                more = end < geo.size
                next = offset + req.varint(4, 10).toInt()
                line.message(8) {
                    message(1, stamp)
                    for (k in offset until end) fixed64(2, position(geo.lat[k], geo.lng[k]))
                    for (k in offset until end) float(3, geo.ele[k])
                    for (k in offset until end) varint(4, geo.dist[k].roundToLong())
                    bytes(6, ByteArray(0))
                    varint(9, offset)
                    varint(21, 0)
                }
            }
            4 -> line.message(5) {
                message(1, stamp)
                float(2, geo.dist.last().toFloat())
                // Unexplained; copied from Explore's stats part for a walking course.
                float(3, 3.107f); float(4, 3.0f)
                float(11, geo.ele.max()); float(12, geo.ele.min())
            }
            8 -> line.message(6) { message(1, stamp); varint(2, LINE_COURSE) }
            16 -> line.message(7) { message(1, stamp); varint(2, 0); varint(3, 0) }
            else -> {
                host.log("watch asked for unknown part $part of line $uuid")
                return reply(F_LINE_READ + 1) { varint(1, READ_ERROR) }
            }
        }
        return reply(F_LINE_READ + 1) {
            varint(1, if (more) READ_MORE else 0)
            message(2, line)
            if (next >= 0) varint(3, next)
        }
    }

    private fun finished(body: ProtoReader): ByteArray? {
        val ok = body.varint(1) == FINISHED_OK
        host.log("watch finished the sync: ${if (ok) "ok" else "FAILED (status ${body.varint(1)})"}")
        open = false
        val lib = library
        if (ok) {
            if (full) lib.baseline = true
            val on = mutableSetOf<Pair<Int, Int>>()
            val off = mutableSetOf<Pair<Int, Int>>()
            for (i in lib.items.values.toList()) {
                val carried = announcedTo >= 0 && i.txn != WatchLibrary.PENDING && i.txn <= announcedTo
                if (!i.confirmed && carried) lib.items[i.uuid] = i.copy(confirmed = true)
                if (i.confirmed || carried) (if (i.tombstone) off else on) += i.kind to i.ref
            }
            fun ids(set: Set<Pair<Int, Int>>, kind: Int) = set.filter { it.first == kind }.mapTo(HashSet()) { it.second }
            val liveRefs = lib.live(WatchLibrary.KIND_COURSE).mapTo(HashSet()) { it.ref }
            host.onReport(ReconcileReport(
                coursesOn = ids(on, WatchLibrary.KIND_COURSE),
                coursesOff = ids(off, WatchLibrary.KIND_COURSE) - liveRefs - deletedOnWatch,
                coursesDeletedOnWatch = deletedOnWatch.toSet(),
                waypointsOn = ids(on, WatchLibrary.KIND_WAYPOINT),
                waypointsOff = ids(off, WatchLibrary.KIND_WAYPOINT) -
                    lib.live(WatchLibrary.KIND_WAYPOINT).mapTo(HashSet()) { it.ref },
            ))
        } else {
            host.onReport(ReconcileReport(failed = true))
        }
        // Anything still waiting gets a session of its own straight away.
        val waiting = lib.items.values.any { it.txn == WatchLibrary.PENDING }
        if (followUp || (ok && waiting)) {
            followUp = false
            host.send(startLocked())
        }
        return null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun wanted() = wantedNow ?: host.wanted().also { wantedNow = it }

    private fun resetSession() {
        announcedFrom = -1
        announcedTo = -1
        deletedOnWatch.clear()
        wantedNow = null
    }

    private fun kindName(kind: Int) = if (kind == WatchLibrary.KIND_COURSE) "course" else "place"

    private fun reply(field: Int, build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().message(field, build).toByteArray()

    /** A course's vertices with the cumulative distance and elevation the points part needs. */
    private class Geometry(points: List<Triple<Double, Double, Double?>>) {
        val size = points.size
        val lat = DoubleArray(size) { points[it].first }
        val lng = DoubleArray(size) { points[it].second }
        val ele = FloatArray(size)
        val dist = DoubleArray(size)

        init {
            var last = points.firstNotNullOfOrNull { it.third } ?: 0.0
            for (k in 0 until size) {
                last = points[k].third ?: last
                ele[k] = last.toFloat()
                if (k > 0) {
                    val mean = Math.toRadians((lat[k] + lat[k - 1]) / 2)
                    dist[k] = dist[k - 1] + hypot((lng[k] - lng[k - 1]) * cos(mean) * 111_320.0, (lat[k] - lat[k - 1]) * 110_540.0)
                }
            }
        }
    }

    companion object {
        // Explore sync message fields, from the captures.
        const val F_START_REQUEST = 1
        const val F_START_RESPONSE = 2
        const val F_SYNC_FINISHED = 3
        const val F_COLLECTION_LIST_WRITE = 4
        const val F_COLLECTION_READ = 6
        const val F_COLLECTION_DIGEST_WRITE = 8
        const val F_COLLECTION_DIGEST_READ = 10
        const val F_WAYPOINT_READ = 12
        const val F_WAYPOINT_DIGEST_WRITE = 14
        const val F_WAYPOINT_DIGEST_READ = 16
        const val F_CHANGE_SUMMARY_REQUEST = 18
        const val F_COLLECTION_PAGE = 22
        const val F_LINE_DIGEST_WRITE = 24
        const val F_LINE_DIGEST_READ = 26
        const val F_LINE_DATA_READY = 28
        const val F_LINE_READ = 30
        const val F_ACTIVE_LINE_DIGEST_WRITE = 32

        const val SYNC_FULL = 1
        const val SYNC_INCREMENTAL = 2
        const val PROTOCOL_VERSION = 2
        const val FINISHED_OK = 1L
        const val READ_MORE = 1
        const val READ_ERROR = 2
        const val LINE_COURSE = 9L
        const val MAX_NAME = 63

        /** Explore's capability set after its first sync. */
        const val SYNC_CAPABILITIES = 31

        /** Stable per-app identity; the watch keys its per-client cursor on it. */
        val APP_UUID: ByteArray = "Tracks Explore!!".toByteArray(Charsets.US_ASCII)

        private val LOCK = Any()

        /** Longitude in the high word, latitude in the low, both semicircles. */
        fun position(lat: Double, lng: Double): Long {
            fun semi(deg: Double) = (deg * 2147483648.0 / 180.0).roundToLong().toInt()
            return (semi(lng).toLong() shl 32) or (semi(lat).toLong() and 0xffffffffL)
        }
    }
}

/** Where the session asks the app what it wants, and tells it what the watch confirmed. */
object ExploreBridge {
    interface Library {
        fun wanted(): Pair<List<DesiredCourse>, List<DesiredWaypoint>>?
        fun onReport(report: ReconcileReport)
    }

    @Volatile
    var library: Library? = null
}
