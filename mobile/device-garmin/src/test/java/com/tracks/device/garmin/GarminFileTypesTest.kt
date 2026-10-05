// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType

/**
 * Resolving a push target is the one place a wrong answer is expensive: the
 * watch accepts the file, files it under the wrong type, and the workout the
 * user planned simply never appears — with no error anywhere to explain it.
 */
class GarminFileTypesTest {

    @Test
    fun `an unparseable file falls back to the courses folder`() {
        assertEquals(
            FileType.FILETYPE.COURSES,
            GarminFileTypes.resolve("GARMIN/Courses", "ride.fit", byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun `an unparseable file falls back to the workouts folder`() {
        assertEquals(
            FileType.FILETYPE.WORKOUTS,
            GarminFileTypes.resolve("GARMIN/Workouts", "intervals.fit", byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun `NewFiles alone is not enough to name a type`() {
        // The whole reason the FIT header is consulted first: this folder
        // carries workouts, schedules and race plans indiscriminately, so
        // guessing from it would be picking one of three at random.
        assertNull(GarminFileTypes.resolve("GARMIN/NewFiles", "plan.fit", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `ephemeris is recognised and refused`() {
        assertTrue(GarminFileTypes.isEphemeris("GARMIN/REMOTESW", "CPE.bin"))
        assertTrue(GarminFileTypes.isEphemeris("somewhere/else", "CPE_GPS.BIN"))
        assertNull(GarminFileTypes.resolve("GARMIN/REMOTESW", "CPE.bin", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a fit file in the ephemeris folder is still refused`() {
        // Ordering check: the ephemeris test runs before parsing, so a .bin
        // that happens to parse cannot sneak through as a FIT type.
        assertNull(GarminFileTypes.resolve("GARMIN/REMOTESW", "whatever.bin", fitFile()))
    }

    @Test
    fun `folder matching ignores case and separators`() {
        assertEquals(
            FileType.FILETYPE.COURSES,
            GarminFileTypes.resolve("garmin/courses", "x.fit", byteArrayOf(0)),
        )
    }

    /**
     * A minimal but structurally valid FIT header, used only to prove the
     * ordering above. It is deliberately not a complete file — this test does
     * not care whether parsing succeeds, only that it is never reached.
     */
    private fun fitFile(): ByteArray = byteArrayOf(
        12, 0x20, 0, 0, 0, 0, 0, 0,
        '.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(),
    )

    @Test
    fun `a Connect IQ app resolves to PRG`() {
        // The one non-FIT thing BLE will carry, and the reason a watch app can
        // be installed without the Connect IQ Store or a cable.
        assertEquals(
            FileType.FILETYPE.PRG,
            GarminFileTypes.resolve("GARMIN/APPS", "TracksMusic.prg", byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun `the app extension is matched regardless of case`() {
        assertTrue(GarminFileTypes.isWatchApp("TRACKSMUSIC.PRG"))
        assertTrue(GarminFileTypes.isWatchApp("tracksmusic.prg"))
        assertTrue(!GarminFileTypes.isWatchApp("program.txt"))
    }

    @Test
    fun `a prg is resolved from its name, not the folder it was named with`() {
        // BLE has no filesystem, so the folder is only ever a hint. A .prg
        // must resolve the same way wherever the server says it would go.
        assertEquals(
            FileType.FILETYPE.PRG,
            GarminFileTypes.resolve("", "TracksMusic.prg", byteArrayOf(1, 2, 3)),
        )
    }
}
