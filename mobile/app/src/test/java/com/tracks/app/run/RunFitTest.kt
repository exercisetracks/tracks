// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import com.tracks.core.run.RunFix
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file a phone-recorded run turns into.
 *
 * This is the one part of the running feature that cannot be checked by using
 * it: a FIT file that is subtly malformed uploads happily, fails somewhere
 * inside the importer, and shows up as a run that simply never appeared. So the
 * encoder is checked by parsing its own output back with the same reader the
 * watch files go through.
 */
class RunFitTest {

    private fun fixes(count: Int): List<RunFix> = (0 until count).map { i ->
        RunFix(
            timestampMs = START_MS + i * 1000L,
            elapsedMs = i * 1000L,
            lat = 39.7392 + i * 0.0001,
            lng = -149.9903,
            altitudeM = 1600.0 + i,
            accuracyM = 5.0,
            speedMps = 3.5,
        )
    }

    private fun encoded(count: Int = 30): ByteArray = requireNotNull(
        RunFit.encode(
            fixes = fixes(count),
            distanceM = 1234.5,
            ascentM = 29.0,
            elapsedMs = 30_000,
            movingMs = 28_000,
            startedAtMs = START_MS,
        )
    )

    @Test
    fun `a run with no fixes produces no file`() {
        assertNull(
            RunFit.encode(
                fixes = emptyList(), distanceM = 0.0, ascentM = 0.0,
                elapsedMs = 0, movingMs = 0, startedAtMs = START_MS,
            )
        )
    }

    @Test
    fun `the encoded run parses back`() {
        val parsed = FitFile.parseIncoming(encoded())
        assertNotNull(parsed)
        assertTrue("no records came back", parsed.records.isNotEmpty())
    }

    @Test
    fun `it declares itself an activity`() {
        // The importer routes on this. A file that says COURSES gets treated as
        // a route to follow rather than a run that happened.
        assertEquals(FileType.FILETYPE.ACTIVITY, FitFile.parseIncoming(encoded()).fileType)
    }

    @Test
    fun `every fix survives as a record`() {
        val parsed = FitFile.parseIncoming(encoded(count = 30))
        val records = parsed.records.filter { it.nativeFITMessage.number == RECORD_MESSAGE }
        assertEquals(30, records.size)
    }

    @Test
    fun `the session and activity summaries are both present`() {
        val numbers = FitFile.parseIncoming(encoded()).records.map { it.nativeFITMessage.number }
        assertTrue("no session message", SESSION_MESSAGE in numbers)
        assertTrue("no activity message", ACTIVITY_MESSAGE in numbers)
        assertTrue("no lap message", LAP_MESSAGE in numbers)
    }

    @Test
    fun `the same run encodes identically twice`() {
        // The ingest endpoint de-duplicates on a content hash, so a retried
        // upload of the same run must be byte-identical or it lands twice.
        assertTrue(encoded().contentEquals(encoded()))
    }

    private companion object {
        const val START_MS = 1_700_000_000_000L

        // Global FIT message numbers, from the profile.
        const val RECORD_MESSAGE = 20
        const val LAP_MESSAGE = 19
        const val SESSION_MESSAGE = 18
        const val ACTIVITY_MESSAGE = 34
    }
}
