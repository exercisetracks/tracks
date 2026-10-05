// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

/**
 * Fractional-index keys for the `order` field of ordered children — a flow's
 * stretches, a workout's exercises.
 *
 * ## Why not an integer position
 *
 * Inserting at position 3 renumbers everything after it, which is an edit to
 * every sibling. Two phones doing that offline — one reordering, one adding a
 * stretch — would each rewrite the whole list, and field-level merge would
 * interleave their numbers into an order neither person made. A key *between*
 * its neighbours changes one row and nothing else, so both edits survive.
 *
 * Keys are strings over `0-9A-Za-z`, compared bytewise (which is ASCII order
 * for this alphabet), never ending in `0` so that there is always room to
 * insert between two of them. Two replicas generating a key in the same gap
 * can produce the same key; that is harmless — ties sort by uid wherever order
 * is read — and not worth coordinating to avoid.
 */
object OrderKeys {

    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62

    /**
     * A key strictly between [before] and [after]; null means "the start" and
     * "the end" of the list respectively.
     */
    fun between(before: String?, after: String?): String {
        val a = before ?: ""
        if (after != null) require(a < after) { "keys out of order: '$a' >= '$after'" }
        requireValid(a)
        after?.let(::requireValid)
        return midpoint(a, after)
    }

    /** Keys for [count] items in order, evenly spread — for a list built in one go. */
    fun sequence(count: Int): List<String> {
        val out = ArrayList<String>(count)
        var previous: String? = null
        repeat(count) { previous = between(previous, null).also(out::add) }
        return out
    }

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            // Skip the shared prefix, treating a missing digit of `a` as 0.
            var n = 0
            while ((a.getOrElse(n) { '0' }) == b[n]) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
        }
        val digitA = if (a.isEmpty()) 0 else DIGITS.indexOf(a[0])
        val digitB = if (b == null) BASE else DIGITS.indexOf(b[0])
        if (digitB - digitA > 1) {
            return DIGITS[(digitA + digitB + 1) / 2].toString()
        }
        // Adjacent digits: descend a level.
        return if (b != null && b.length > 1) {
            b.substring(0, 1)
        } else {
            DIGITS[digitA] + midpoint(a.drop(1), null)
        }
    }

    private fun requireValid(key: String) {
        require(key.all { it in DIGITS }) { "not an order key: '$key'" }
        require(!key.endsWith('0')) { "order keys never end in '0': '$key'" }
    }
}
