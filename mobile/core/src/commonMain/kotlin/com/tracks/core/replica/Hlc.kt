// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

/**
 * A hybrid logical clock stamp: when an edit was made, in an order every
 * replica agrees on.
 *
 * ## Why not the wall clock
 *
 * Because phones are wrong about the time. A phone set a day ahead would win
 * every conflict for a day, and one set behind would lose edits to anything
 * older. An HLC stays close to wall time when clocks are sane, and when they
 * are not it still never goes backwards: receiving a stamp moves the local
 * clock past it, so no device can mint an edit that sorts below one it has
 * already seen.
 *
 * ## Why a string
 *
 * `spec/sync.yaml` fixes the encoding as fixed-width text precisely so that
 * plain string comparison is clock comparison — in SQLite, in Postgres, in
 * Python and here. [compareTo] therefore compares [encoded], not the parts: if
 * the two ever disagreed the database and the merge would order edits
 * differently, which is the kind of bug that only shows up months later.
 */
data class Hlc(val wallMs: Long, val counter: Int, val node: String) : Comparable<Hlc> {

    init {
        require(wallMs in 0..MAX_WALL) { "wall time out of range: $wallMs" }
        require(counter in 0..MAX_COUNTER) { "counter out of range: $counter" }
        require(node.length == 16 && node.all { it in HEX }) { "node must be 16 lowercase hex: $node" }
    }

    val encoded: String =
        wallMs.toString().padStart(16, '0') + "-" +
            counter.toString(16).padStart(4, '0') + "-" + node

    override fun compareTo(other: Hlc): Int = encoded.compareTo(other.encoded)

    override fun toString(): String = encoded

    companion object {
        private const val MAX_WALL = 9_999_999_999_999_999L
        const val MAX_COUNTER = 0xffff
        private const val HEX = "0123456789abcdef"

        fun parse(text: String): Hlc {
            val parts = text.split('-')
            require(parts.size == 3 && parts[0].length == 16 && parts[1].length == 4) {
                "not an HLC stamp: $text"
            }
            return Hlc(
                wallMs = parts[0].toLongOrNull() ?: throw IllegalArgumentException("bad wall: $text"),
                counter = parts[1].toIntOrNull(16) ?: throw IllegalArgumentException("bad counter: $text"),
                node = parts[2],
            )
        }

        fun parseOrNull(text: String): Hlc? = runCatching { parse(text) }.getOrNull()
    }
}

/** A stamp so far ahead of this device's clock that accepting it would poison the field. */
class ClockSkewException(val stamp: String, val nowMs: Long) :
    Exception("stamp $stamp is more than ${HLC_MAX_SKEW_MS}ms ahead of $nowMs")

/**
 * The clock itself: the last stamp issued or seen, and the rules for the next.
 *
 * Pure — it neither reads the time nor persists anything. The caller passes
 * `now` and stores [last], which keeps the rules testable against the shared
 * fixtures and lets the store save the clock in the same transaction as the
 * rows it stamped.
 */
class HlcClock(val node: String, last: Hlc? = null) {

    var last: Hlc = last ?: Hlc(0, 0, node)
        private set

    /** The stamp for a local edit. */
    fun tick(nowMs: Long): Hlc {
        val wall = maxOf(nowMs, last.wallMs)
        val counter = if (wall == last.wallMs) last.counter + 1 else 0
        return advance(wall, counter)
    }

    /**
     * Would [stamp] be refused? Checked before anything is applied, so a change
     * carrying one bad stamp is rejected whole rather than half-merged.
     */
    fun wouldRefuse(stamp: Hlc, nowMs: Long): Boolean = stamp.wallMs > nowMs + HLC_MAX_SKEW_MS

    /** Move past a stamp from elsewhere. Throws [ClockSkewException] rather than accept a poisoned one. */
    fun receive(stamp: Hlc, nowMs: Long): Hlc {
        if (wouldRefuse(stamp, nowMs)) throw ClockSkewException(stamp.encoded, nowMs)
        return advancePast(stamp, nowMs)
    }

    /**
     * Move past a stamp the server sent in a pull, however far ahead.
     *
     * The skew check guards the server receiving a push, not a client
     * receiving a pull: the server already vetted this stamp against its own
     * clock. If the phone's clock is the wrong one, refusing would stall its
     * pull cursor forever — the failure the spec's `receive_pulled` case pins.
     */
    fun receivePulled(stamp: Hlc, nowMs: Long): Hlc = advancePast(stamp, nowMs)

    private fun advancePast(stamp: Hlc, nowMs: Long): Hlc {
        val wall = maxOf(nowMs, last.wallMs, stamp.wallMs)
        val counter = when {
            wall == last.wallMs && wall == stamp.wallMs -> maxOf(last.counter, stamp.counter) + 1
            wall == last.wallMs -> last.counter + 1
            wall == stamp.wallMs -> stamp.counter + 1
            else -> 0
        }
        return advance(wall, counter)
    }

    private fun advance(wall: Long, counter: Int): Hlc {
        // An error, not a wrap: wrapping would issue a stamp that sorts before
        // the one just issued. 65 536 edits in one millisecond is a loop, not a user.
        check(counter <= Hlc.MAX_COUNTER) { "HLC counter overflow at $wall" }
        return Hlc(wall, counter, node).also { last = it }
    }
}
