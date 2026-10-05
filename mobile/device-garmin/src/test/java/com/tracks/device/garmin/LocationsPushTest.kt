// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType

/**
 * The file type a saved-places push is sent as.
 *
 * A watch refusing a file is nearly always read as "the file is bad", and the
 * likelier explanation is that it was announced as the wrong kind. The type is
 * sniffed out of the FIT's own `file_id` message rather than taken from the
 * folder, so this is a decoder question and answerable on a JVM — no watch, no
 * Bluetooth, no waiting for somebody to press a button.
 *
 * The bytes are exactly what the server generates: `generate_locations_fit`
 * with one place and with none. The empty one matters most, because clearing
 * the watch's places over Bluetooth is a *write* of a file containing nothing —
 * BLE has no delete — and a file with no records is the one most likely to
 * confuse a sniffer into guessing.
 */
class LocationsPushTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /** `generate_locations_fit([])` — a locations file holding no locations. */
    private val empty = bytes(
        "0e20d852230000002e464954bea840000000000500010001028402028403048c0404" +
            "8600080100feff01000000daede8441cc2"
    )

    /** `generate_locations_fit([...])` — the same file with one place in it. */
    private val one = bytes(
        "0e20d852560000002e46495479b340000000000500010001028402028403048c0404" +
            "8600080100feff01000000daede8444100001d0007fe0284fd048600050701048502" +
            "0485030284040284010000daede84443616d70001cc7711c565555950b00983a471c"
    )

    @Test
    fun `a file with places in it is announced as locations`() {
        assertEquals(
            FileType.FILETYPE.LOCATION,
            GarminFileTypes.resolve("GARMIN/Locations", "Locations.fit", one),
        )
    }

    @Test
    fun `a file with no places in it is still announced as locations`() {
        // The clear-the-watch case. If this sniffed as something else the watch
        // would refuse a perfectly well-formed file, and the refusal would look
        // like the file being corrupt rather than mislabelled.
        assertEquals(
            FileType.FILETYPE.LOCATION,
            GarminFileTypes.resolve("GARMIN/Locations", "Locations.fit", empty),
        )
    }

    @Test
    fun `the folder is not what decides it`() {
        // Sniffing wins over the folder, so a locations file survives being
        // handed over with the wrong folder name — and, more to the point, the
        // Locations folder has no fallback of its own, so a file that failed to
        // sniff would resolve to nothing at all rather than to LOCATION.
        assertEquals(
            FileType.FILETYPE.LOCATION,
            GarminFileTypes.resolve("GARMIN/NewFiles", "Locations.fit", one),
        )
    }
}
