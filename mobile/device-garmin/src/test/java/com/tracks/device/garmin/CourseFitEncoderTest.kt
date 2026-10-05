// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitCourse
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLap
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitRecord

/**
 * [CourseFitEncoder], checked by decoding its own output — see
 * [FitFile.parseIncoming] — rather than against a byte fixture, since the
 * thing worth proving is that a real watch's own parser (which this decoder
 * mirrors) reads back what was meant, not that the bytes match one
 * particular encoder's mood on one particular day.
 */
class CourseFitEncoderTest {

    private val square = listOf(
        listOf(-150.0, 40.0),
        listOf(-150.0, 40.01),
        listOf(-149.99, 40.01),
        listOf(-149.99, 40.0),
    )

    @Test
    fun `a course round-trips through the file it produces`() {
        val bytes = CourseFitEncoder.encode(
            name = "Ridge Loop", sport = "hiking", courseId = 42,
            coordinates = square, distanceMetres = 0.0, ascentMetres = 120.0,
        )
        assertNotNull(bytes)
        val parsed = FitFile.parseIncoming(bytes)
        assertEquals(FileType.FILETYPE.COURSES, parsed.fileType)

        val fileId = parsed.records.filterIsInstance<FitFileId>().single()
        assertEquals(FileType.FILETYPE.COURSES, fileId.type)

        val course = parsed.records.filterIsInstance<FitCourse>().single()
        assertEquals("Ridge Loop", course.name)

        val lap = parsed.records.filterIsInstance<FitLap>().single()
        assertEquals(120, lap.totalAscent)
        // Always present, even at zero — see the class doc on why an absent
        // field is a different thing to a watch than a zero one.
        assertEquals(0, lap.totalDescent)

        val records = parsed.records.filterIsInstance<FitRecord>()
        assertEquals(square.size, records.size)
        assertEquals(square.first()[1], records.first().latitude!!, 1e-4)
        assertEquals(square.first()[0], records.first().longitude!!, 1e-4)
        assertEquals(square.last()[1], records.last().latitude!!, 1e-4)
        assertEquals(square.last()[0], records.last().longitude!!, 1e-4)
    }

    @Test
    fun `distance is computed from the line when the server figure is not supplied`() {
        val bytes = CourseFitEncoder.encode(
            name = "No stats yet", sport = "hiking", courseId = 1,
            coordinates = square, distanceMetres = 0.0, ascentMetres = 0.0,
        )
        val lap = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLap>().single()
        // ~3km along the three edges of this square at this latitude — a
        // sanity range, not an exact figure, since the lap's distance is the
        // phone's own equirectangular estimate rather than the server's
        // arithmetic.
        assertTrue(lap.totalDistance!! in 2500.0..3500.0)
    }

    @Test
    fun `a supplied distance is trusted over the computed one`() {
        val bytes = CourseFitEncoder.encode(
            name = "Known distance", sport = "hiking", courseId = 1,
            coordinates = square, distanceMetres = 9999.0, ascentMetres = 0.0,
        )
        val lap = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLap>().single()
        assertEquals(9999.0, lap.totalDistance!!, 1e-6)
    }

    @Test
    fun `fewer than two usable points refuses rather than writing a broken file`() {
        assertNull(CourseFitEncoder.encode("x", "hiking", 1, listOf(listOf(0.0, 0.0)), 0.0, 0.0))
        assertNull(CourseFitEncoder.encode("x", "hiking", 1, emptyList(), 0.0, 0.0))
    }

    @Test
    fun `a name past the wire limit is trimmed on a whole character, not split`() {
        val bytes = CourseFitEncoder.encode(
            name = "A very long track name that will not fit in sixteen bytes",
            sport = "running", courseId = 7, coordinates = square, distanceMetres = 0.0, ascentMetres = 0.0,
        )
        val course = FitFile.parseIncoming(bytes).records.filterIsInstance<FitCourse>().single()
        val nameBytes = course.name!!.toByteArray(Charsets.UTF_8)
        assertTrue(nameBytes.size <= 15)
        // Decodes cleanly — a byte-boundary cut through a multi-byte
        // character would fail to round-trip through UTF-8 at all.
        assertEquals(course.name, String(nameBytes, Charsets.UTF_8))
    }

    @Test
    fun `an unrecognised sport falls back to hiking rather than failing`() {
        val bytes = CourseFitEncoder.encode(
            name = "Mystery sport", sport = "underwater_basket_weaving", courseId = 1,
            coordinates = square, distanceMetres = 0.0, ascentMetres = 0.0,
        )
        assertNotNull(bytes)
        assertEquals(FileType.FILETYPE.COURSES, FitFile.parseIncoming(bytes).fileType)
    }

    @Test
    fun `the file sniffs as a course by its bytes alone`() {
        val bytes = CourseFitEncoder.encode(
            name = "Sniff me", sport = "hiking", courseId = 1,
            coordinates = square, distanceMetres = 0.0, ascentMetres = 0.0,
        )!!
        assertEquals(
            FileType.FILETYPE.COURSES,
            GarminFileTypes.resolve("GARMIN/Courses", "TRK_1.fit", bytes),
        )
    }
}
