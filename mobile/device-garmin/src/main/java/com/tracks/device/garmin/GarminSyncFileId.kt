// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

/**
 * How a file pulled over the newer protobuf sync is named in
 * [com.tracks.device.PulledFile.deviceFileId].
 *
 * That field is deliberately an opaque string on the vendor-neutral side, and
 * this is what Garmin puts in it. Two protocols, two ways of addressing a file:
 * the older one uses a 16-bit directory index, which serialises as a bare
 * integer, and the newer one uses a 128-bit id plus a type name, which does
 * not. The prefix is what keeps them apart — an integer can never carry it — so
 * the code that later tells the watch to release a file can tell which protocol
 * it is speaking without a second field on a type shared with every other
 * vendor.
 *
 * A string rather than a lookup table keyed by some token, because the lifetime
 * is wrong for a table. A file can be pulled, the connection can drop, and the
 * upload can finish afterwards — or in a different process entirely, since
 * Android kills and recreates the app as a matter of routine. An id that only
 * meant something to the object graph that created it would quietly stop
 * meaning anything, and the watch would then offer the same file on every sync
 * forever.
 */
internal object GarminSyncFileId {

    private const val PREFIX = "gfs:"

    /** True when [id] names a file on the newer protocol rather than the older one. */
    fun matches(id: String): Boolean = id.startsWith(PREFIX)

    /**
     * The pair of ids is the address; the type name is metadata and may be
     * absent.
     *
     * An earlier version returned null without a type name, on the reasoning
     * that an unnamed file could not be released. The watch disagreed: a
     * mark-as-synced carries the id and the flags and nothing else, so the name
     * is not needed to release anything. Requiring it would have made most of a
     * real sync unreleasable — the watch states each type name once and then
     * refers to it by number, so the majority of files in a listing legitimately
     * arrive unnamed — and every one of those would have been offered again on
     * every sync, forever.
     */
    fun encode(id1: Long, id2: Long, typeName: String?): String =
        "$PREFIX$id1:$id2:${typeName.orEmpty()}"

    /** Null when [id] is not one of ours, or is one of ours and malformed. */
    fun decode(id: String): Decoded? {
        if (!matches(id)) return null
        // limit = 3 so a type name containing a colon survives intact rather
        // than being truncated at it.
        val parts = id.removePrefix(PREFIX).split(':', limit = 3)
        if (parts.size != 3) return null
        val id1 = parts[0].toLongOrNull() ?: return null
        val id2 = parts[1].toLongOrNull() ?: return null
        return Decoded(id1, id2, parts[2].ifEmpty { null })
    }

    /** [typeName] is null when the watch never named this file's type. */
    data class Decoded(val id1: Long, val id2: Long, val typeName: String?)
}
