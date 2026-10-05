// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * UTC offsets from the JVM's zone database, for the zone-agnostic date logic
 * in commonMain (see `Matching.localDate`).
 *
 * A zone the JVM cannot name falls back to UTC, as the server's
 * `activity_local_date` does with one Python cannot: an unreadable setting
 * must not stop a run from matching, and both sides must agree on what it
 * means.
 */
object ZoneOffsets {
    fun of(zone: String?): (epochSeconds: Long) -> Int {
        val rules = zone?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?.rules
            ?: ZoneOffset.UTC.rules
        return { rules.getOffset(Instant.ofEpochSecond(it)).totalSeconds }
    }
}
