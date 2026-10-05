// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import kotlinx.serialization.json.JsonElement

/**
 * One synced row, as every replica holds it.
 *
 * [fields] holds only fields that have been written: a field that is absent
 * has never been set, which is a different state from being set to null (a
 * cleared description is `JsonNull`, and has a stamp). [clock] carries the
 * stamp of the last write to each field. [deleted], once set, makes the row a
 * tombstone — fields and clock empty, and nothing can bring it back.
 */
data class SyncedRow(
    val entity: String,
    val uid: String,
    val fields: Map<String, JsonElement> = emptyMap(),
    val clock: Map<String, String> = emptyMap(),
    val deleted: String? = null,
) {
    val isTombstone: Boolean get() = deleted != null

    fun tombstone(stamp: String): SyncedRow = SyncedRow(entity, uid, deleted = stamp)
}

/** A stamped value — one field of a [Change]. */
data class Stamped(val value: JsonElement, val stamp: String)

/**
 * A set of writes to one row, as it goes over the wire and as a local edit is
 * expressed internally. Pulled rows arrive in this shape too: a full row is
 * simply a change that writes every field it has.
 */
data class Change(
    val entity: String,
    val uid: String,
    val fields: Map<String, Stamped> = emptyMap(),
    val deleted: String? = null,
) {
    /** Every stamp this change carries — all must be received before any is applied. */
    val stamps: List<String> get() = fields.values.map { it.stamp } + listOfNotNull(deleted)
}

enum class ChangeStatus(val wire: String) {
    /** Everything in the change took effect. */
    APPLIED("applied"),

    /** Some fields took effect and some were refused. */
    PARTIAL("partial"),

    /** Nothing took effect — every part was stale, deleted, or immutable. */
    STALE("stale"),

    /** The change was not considered at all. */
    REJECTED("rejected"),
    ;

    companion object {
        fun ofWire(text: String): ChangeStatus =
            entries.firstOrNull { it.wire == text } ?: throw IllegalArgumentException("unknown status $text")
    }
}

/** Why one field of a change did not take effect. */
enum class Refusal(val wire: String) {
    STALE("stale"),
    DELETED("deleted"),
    IMMUTABLE("immutable"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun ofWire(text: String): Refusal? = entries.firstOrNull { it.wire == text }
    }
}

/** Reasons a change is rejected outright. */
object RejectReason {
    const val READONLY = "readonly"
    const val UNKNOWN_ENTITY = "unknown_entity"

    /**
     * A stamp too far ahead of the receiver's clock. The only *transient*
     * rejection: the sender's clock is wrong, and the change becomes acceptable
     * once the receiver's time catches up, so it must stay queued rather than
     * be dropped.
     */
    const val CLOCK_SKEW = "clock_skew"

    /**
     * The push came from an account epoch older than the server's: the user
     * deleted their data since this phone last looked. Permanent — the phone
     * wipes its own copy instead of retrying.
     */
    const val WIPED = "wiped"

    /**
     * This one change cannot be stored — a malformed value, or a uid that does
     * not match its natural key. Permanent and per change, so one bad row
     * never holds up the rest of the queue.
     */
    const val INVALID = "invalid"
}

data class ChangeResult(
    val entity: String,
    val uid: String,
    val status: ChangeStatus,
    val refused: Map<String, Refusal> = emptyMap(),
    val reason: String? = null,
)
