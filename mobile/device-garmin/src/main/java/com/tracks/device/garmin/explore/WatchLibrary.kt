// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.explore

import java.security.SecureRandom

/**
 * Tracks' library on one watch: the collection the phone owns, and the items in it.
 *
 * Garmin's model, read off captures of Garmin Explore (docs/garmin-explore-sync.md):
 * the **phone** owns a collection and every item in it, under UUIDs the phone
 * generates, and the watch mirrors it. Putting a course on the watch is adding
 * an item; taking it off is marking the item tombstoned with a fresh stamp —
 * tombstones stay in the collection. The watch reads the phone's side during a
 * session, once the phone says it has changes.
 *
 * Persisted per watch as tab-separated records ([serialise]), because this
 * module has no JSON library and the format only has to round-trip itself.
 */
class WatchLibrary {

    data class Item(
        val uuid: String,
        val kind: Int,
        /** The Tracks course or waypoint this item is. */
        val ref: Int,
        /** Version stamp, Unix seconds. Every part of a line shares it. */
        val stamp: Long,
        val tombstone: Boolean,
        /**
         * The transaction this version was announced in; [PENDING] until a
         * change summary carries it. Digests serve what changed after the
         * watch's last transaction, which is what keeps a session short.
         */
        val txn: Int,
        /** Hash of what was sent, so an edit is noticed and re-sent. */
        val content: Int,
        /** A session that carried this version finished OK. */
        val confirmed: Boolean,
    )

    /** This phone's collection on this watch; generated once and kept. */
    var collection: String = newUuid()
    var collectionStamp: Long = 0

    /** Item UUID -> item, in the order added. */
    val items = LinkedHashMap<String, Item>()

    /** The last transaction this phone announced; only grows. */
    var transaction: Int = 0

    /** A FULL session has finished OK at least once. */
    var baseline: Boolean = false

    /** Sessions in a row the watch ended FAILED; reset by one that finishes OK. */
    var failures: Int = 0

    /**
     * A session has finished OK with [collection] declared, so the watch knows
     * it. A watch that then no longer lists it has been reset.
     */
    var collectionKnown: Boolean = false

    /**
     * Collections given up, with the stamp they were retired at. Declared to
     * the watch tombstoned, as an item is, so it drops them: measured
     * 2026-10-12, the watch took a collection declared with `3: 1` out of its
     * own collection list at the next session.
     */
    val retired = LinkedHashMap<String, Long>()

    /**
     * Every position Tracks has put a saved place at on this watch, kept after
     * the place is deleted. The watch's own saved-places file lists Tracks'
     * places alongside the ones made on the watch, with nothing to tell them
     * apart but where they are; this is what does.
     */
    val sentPlaces = LinkedHashSet<String>()

    fun recordPlace(lat: Double, lng: Double) { sentPlaces += placeKey(lat, lng) }

    /**
     * Start again in a fresh collection: the current one is retired, and every
     * live item comes back under a new UUID, unconfirmed, to be sent again.
     * Tombstones are left behind with the old collection. The way out of a
     * watch that fails every session over this collection's state, short of a
     * factory reset.
     */
    fun retire(now: Long): Int {
        retired[collection] = now
        collection = newUuid()
        collectionStamp = 0
        val live = items.values.filter { !it.tombstone }
        items.clear()
        for (i in live) {
            val uuid = newUuid()
            items[uuid] = i.copy(uuid = uuid, stamp = now, txn = PENDING, confirmed = false)
        }
        failures = 0
        collectionKnown = false
        return live.size
    }

    /**
     * Send everything again, into the same collection: for a watch that has
     * forgotten Tracks (factory reset). Tombstones go — there is nothing on
     * the watch for them to remove — and every live item is announced anew.
     */
    fun resendAll(now: Long): Int {
        val live = items.values.filter { !it.tombstone }
        items.clear()
        for (i in live) items[i.uuid] = i.copy(stamp = maxOf(now, i.stamp + 1), txn = PENDING, confirmed = false)
        collectionKnown = false
        return live.size
    }

    /** Something the watch has not yet confirmed. */
    val dirty: Boolean get() = items.values.any { !it.confirmed }

    fun live(kind: Int): List<Item> = items.values.filter { it.kind == kind && !it.tombstone }

    fun byRef(kind: Int, ref: Int): Item? =
        items.values.firstOrNull { it.kind == kind && it.ref == ref && !it.tombstone }

    fun serialise(): String = buildString {
        append("C\t$collection\t$collectionStamp\t$transaction\t${baseline.bit}\t$failures\t${collectionKnown.bit}\n")
        for ((c, at) in retired) append("R\t$c\t$at\n")
        for (p in sentPlaces) append("P\t$p\n")
        for (i in items.values) {
            append("I\t${i.uuid}\t${i.kind}\t${i.ref}\t${i.stamp}\t${i.tombstone.bit}\t${i.txn}\t${i.content}\t${i.confirmed.bit}\n")
        }
    }

    companion object {
        const val KIND_WAYPOINT = 0
        const val KIND_COURSE = 3
        const val PENDING = -1

        private val random = SecureRandom()

        /** Roughly a metre: tighter than two places anyone marks apart, looser than a FIT round trip. */
        fun placeKey(lat: Double, lng: Double): String =
            "${Math.round(lat * 1e5)},${Math.round(lng * 1e5)}"

        fun newUuid(): String = ByteArray(16).also(random::nextBytes).hex()

        private val Boolean.bit get() = if (this) 1 else 0

        /** Whether stored state already names this phone's collection. */
        fun hasCollection(stored: String?): Boolean =
            stored?.lineSequence()?.any { it.startsWith("C\t") } == true

        /** Rows in an unknown shape are dropped. */
        fun restore(stored: String?): WatchLibrary {
            val lib = WatchLibrary()
            stored?.lineSequence()?.forEach { row ->
                val f = row.split('\t')
                runCatching {
                    when (f[0]) {
                        "C" -> {
                            lib.collection = f[1]; lib.collectionStamp = f[2].toLong()
                            lib.transaction = f[3].toInt(); lib.baseline = f[4] == "1"
                            lib.failures = f.getOrNull(5)?.toIntOrNull() ?: 0
                            lib.collectionKnown = f.getOrNull(6) == "1"
                        }
                        "R" -> lib.retired[f[1]] = f[2].toLong()
                        "P" -> lib.sentPlaces += f[1]
                        "I" -> lib.items[f[1]] = Item(
                            uuid = f[1], kind = f[2].toInt(), ref = f[3].toInt(), stamp = f[4].toLong(),
                            tombstone = f[5] == "1", txn = f[6].toInt(), content = f[7].toInt(),
                            confirmed = f[8] == "1",
                        )
                    }
                }
            }
            return lib
        }
    }
}

/** A course Tracks wants on the watch, with what the watch will be sent. */
data class DesiredCourse(
    val trackId: Int,
    val name: String,
    /** (lat, lng, elevation or null) per vertex, in order. */
    val points: List<Triple<Double, Double, Double?>>,
    val createdAt: Long,
) {
    val content: Int get() = listOf(name, points).hashCode()
}

/** A saved place Tracks wants on the watch. */
data class DesiredWaypoint(
    val waypointId: Int,
    val name: String,
    val lat: Double,
    val lng: Double,
    val elevation: Double?,
    val createdAt: Long,
) {
    val content: Int get() = listOf(name, lat, lng, elevation).hashCode()
}

/** What the watch confirmed, for the app to record. Ids are Tracks ids. */
data class ReconcileReport(
    val coursesOn: Set<Int> = emptySet(),
    val coursesOff: Set<Int> = emptySet(),
    /** Deleted on the watch itself: kept in Tracks, not sent again. */
    val coursesDeletedOnWatch: Set<Int> = emptySet(),
    val waypointsOn: Set<Int> = emptySet(),
    val waypointsOff: Set<Int> = emptySet(),
    /** Places deleted on the watch itself: kept, and not sent again. */
    val waypointsDeletedOnWatch: Set<Int> = emptySet(),
    /** The session failed; nothing in it was applied. */
    val failed: Boolean = false,
    /**
     * What this session itself put on or took off — the news, as against the
     * standing state above, which repeats every tombstone ever confirmed. A
     * receipt read from the standing state said "off the watch" about the
     * same long-deleted track after every sync.
     */
    val changedOn: Set<Int> = emptySet(),
    val changedOff: Set<Int> = emptySet(),
    val changedPlacesOn: Set<Int> = emptySet(),
    val changedPlacesOff: Set<Int> = emptySet(),
)

/**
 * Bring the library in line with what Tracks wants. Pure: mutates [lib] only.
 *
 * Adds an item for anything wanted and not present, re-stamps anything whose
 * content changed (a rename, an edited line), and tombstones anything present
 * and no longer wanted. Returns whether anything changed.
 */
fun plan(lib: WatchLibrary, courses: List<DesiredCourse>, waypoints: List<DesiredWaypoint>, now: Long): Boolean {
    var changed = false
    fun upsert(kind: Int, ref: Int, content: Int) {
        val existing = lib.byRef(kind, ref)
        when {
            existing == null -> {
                val uuid = WatchLibrary.newUuid()
                lib.items[uuid] = WatchLibrary.Item(uuid, kind, ref, now, false, WatchLibrary.PENDING, content, false)
                changed = true
            }
            existing.content != content -> {
                lib.items[existing.uuid] = existing.copy(
                    stamp = maxOf(now, existing.stamp + 1), txn = WatchLibrary.PENDING,
                    content = content, confirmed = false,
                )
                changed = true
            }
        }
    }
    courses.forEach { upsert(WatchLibrary.KIND_COURSE, it.trackId, it.content) }
    waypoints.forEach {
        upsert(WatchLibrary.KIND_WAYPOINT, it.waypointId, it.content)
        lib.recordPlace(it.lat, it.lng)
    }

    val wantedCourses = courses.mapTo(HashSet()) { it.trackId }
    val wantedPlaces = waypoints.mapTo(HashSet()) { it.waypointId }
    for (item in lib.items.values.toList()) {
        if (item.tombstone) continue
        val wanted = if (item.kind == WatchLibrary.KIND_COURSE) item.ref in wantedCourses else item.ref in wantedPlaces
        if (!wanted) {
            tombstone(lib, item, now)
            changed = true
        }
    }
    return changed
}

/** Mark an item removed: kept in the collection, flagged, freshly stamped. */
fun tombstone(lib: WatchLibrary, item: WatchLibrary.Item, now: Long) {
    lib.items[item.uuid] = item.copy(
        tombstone = true, stamp = maxOf(now, item.stamp + 1),
        txn = WatchLibrary.PENDING, confirmed = false,
    )
}
