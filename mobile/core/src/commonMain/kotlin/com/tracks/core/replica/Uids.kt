// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import kotlin.random.Random

/**
 * Where a synced row's uid comes from.
 *
 * Two answers, and choosing the wrong one produces duplicates that no merge can
 * fix afterwards:
 *
 * - **A random, time-ordered UUIDv7** for things that are new each time — a
 *   waypoint, a flow, a logged dose. Time-ordered because both SQLite and
 *   Postgres index sequential keys far better than random ones.
 * - **A UUIDv5 of a natural key** for things two devices can create
 *   independently and mean the same thing — the same watch paired to two
 *   phones, the same day's weight typed on both, the same activity pulled once
 *   over Bluetooth and once over USB. Both sides derive the same uid from the
 *   same name, so the second create is an edit of the first rather than a twin.
 *
 * Which applies is the spec's decision ([EntitySpec.uidKey]), not the caller's.
 */
object Uids {

    private val namespace: ByteArray = parse(UID_NAMESPACE)

    /**
     * The uid for a new row of [entity].
     *
     * [keyValues] fills the entity's uid template; entities without one ignore
     * it. A template whose values are missing falls back to
     * [EntitySpec.uidKeyFallback] — an activity from a file with no device
     * serial is keyed by the file's hash instead — and failing that, it is a
     * programming error: silently minting a random uid for a natural-key row is
     * exactly how the duplicates this exists to prevent would come back.
     */
    fun forEntity(
        entity: String,
        keyValues: Map<String, String?> = emptyMap(),
        nowMs: Long,
        random: Random = Random.Default,
    ): String {
        val spec = SyncRegistry.require(entity)
        val template = spec.uidKey ?: return v7(nowMs, random)
        fill(template, keyValues)?.let { return v5(it) }
        spec.uidKeyFallback?.let { fallback -> fill(fallback, keyValues)?.let { return v5(it) } }
        throw IllegalArgumentException("'$entity' needs ${template} to derive its uid; got $keyValues")
    }

    /** Substitute `{name}` placeholders, or null if any value is missing or blank. */
    fun fill(template: String, values: Map<String, String?>): String? {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            if (open < 0) { out.append(template, i, template.length); break }
            val close = template.indexOf('}', open)
            require(close > open) { "unclosed placeholder in $template" }
            out.append(template, i, open)
            val value = values[template.substring(open + 1, close)]
            if (value.isNullOrEmpty()) return null
            out.append(value)
            i = close + 1
        }
        return out.toString()
    }

    /** RFC 9562 UUIDv5 of [name] (UTF-8) under the spec's namespace. */
    fun v5(name: String): String {
        val hash = Digest.sha1(namespace + name.encodeToByteArray())
        val bytes = hash.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return format(bytes)
    }

    /** RFC 9562 UUIDv7: 48 bits of Unix milliseconds, then random. */
    fun v7(nowMs: Long, random: Random = Random.Default): String {
        val bytes = random.nextBytes(16)
        for (i in 0 until 6) bytes[i] = (nowMs ushr (40 - 8 * i)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return format(bytes)
    }

    private fun format(b: ByteArray): String {
        val hex = Digest.hex(b)
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun parse(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        require(hex.length == 32) { "not a UUID: $uuid" }
        return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
