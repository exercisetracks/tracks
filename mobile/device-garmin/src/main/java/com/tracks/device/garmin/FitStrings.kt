// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

/**
 * Truncate to at most [maxBytes] of UTF-8 without splitting the last
 * character into a malformed tail.
 *
 * FIT string fields have a fixed wire size taken straight from the field's
 * own declared length in the profile — 16 bytes for a course name, 32 for a
 * saved place's — and the generated encoder ([FitRecordDataBuilder]) already
 * truncates to that size at the byte level so an overlong value cannot
 * overflow the record. What it does not do is know the bytes are UTF-8: cut
 * on an arbitrary byte boundary, a name with a multi-byte character near the
 * limit reaches the watch missing the back half of that character rather
 * than missing the whole thing.
 */
internal fun truncateUtf8(value: String, maxBytes: Int): String {
    val bytes = value.encodeToByteArray()
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.decodeToString(0, end)
}
