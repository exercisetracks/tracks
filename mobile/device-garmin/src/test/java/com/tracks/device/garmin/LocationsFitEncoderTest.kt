// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.fieldDefinitions.FieldDefinitionLocationSymbol.LocationSymbol
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLocation

/** [LocationsFitEncoder], checked the same way as [CourseFitEncoderTest]: by decoding its own output. */
class LocationsFitEncoderTest {

    @Test
    fun `an empty list produces a valid file naming no places`() {
        val bytes = LocationsFitEncoder.encode(emptyList())
        val parsed = FitFile.parseIncoming(bytes)
        assertEquals(FileType.FILETYPE.LOCATION, parsed.fileType)
        assertTrue(parsed.records.filterIsInstance<FitLocation>().isEmpty())
        assertEquals(
            FileType.FILETYPE.LOCATION,
            GarminFileTypes.resolve("GARMIN/Locations", "Locations.fit", bytes),
        )
    }

    @Test
    fun `a saved place round-trips its position, name and symbol`() {
        val point = WaypointLocation(
            name = "Cache Camp", lat = 39.755, lng = -150.221,
            elevationMetres = 2743.0, icon = "camp",
        )
        val bytes = LocationsFitEncoder.encode(listOf(point))
        val places = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLocation>()
        assertEquals(1, places.size)
        assertEquals("Cache Camp", places[0].name)
        assertEquals(39.755, places[0].positionLat!!, 1e-4)
        assertEquals(-150.221, places[0].positionLong!!, 1e-4)
        assertEquals(LocationSymbol.Campground, places[0].symbol)
        assertEquals(2743.0f, places[0].altitude!!, 0.5f)
    }

    @Test
    fun `a place with no elevation encodes the invalid marker, not sea level`() {
        val point = WaypointLocation(name = "No Height", lat = 1.0, lng = 1.0, elevationMetres = null, icon = "marker")
        val bytes = LocationsFitEncoder.encode(listOf(point))
        val place = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLocation>().single()
        assertNull(place.altitude)
    }

    @Test
    fun `an unrecognised icon falls back to the plain pin rather than failing`() {
        val point = WaypointLocation(name = "Mystery", lat = 1.0, lng = 1.0, elevationMetres = null, icon = "something_unknown")
        val bytes = LocationsFitEncoder.encode(listOf(point))
        val place = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLocation>().single()
        assertEquals(LocationSymbol.Pin_Blue, place.symbol)
    }

    @Test
    fun `every saved place survives in one file, in order`() {
        val points = listOf(
            WaypointLocation("First", 1.0, 1.0, null, "marker"),
            WaypointLocation("Second", 2.0, 2.0, null, "water"),
            WaypointLocation("Third", 3.0, 3.0, null, "summit"),
        )
        val bytes = LocationsFitEncoder.encode(points)
        val places = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLocation>()
        assertEquals(listOf("First", "Second", "Third"), places.map { it.name })
    }

    @Test
    fun `a name past the wire limit is trimmed on a whole character, not split`() {
        val point = WaypointLocation(
            name = "A saved place with a name much longer than thirty-one bytes of UTF-8",
            lat = 1.0, lng = 1.0, elevationMetres = null, icon = "marker",
        )
        val bytes = LocationsFitEncoder.encode(listOf(point))
        val place = FitFile.parseIncoming(bytes).records.filterIsInstance<FitLocation>().single()
        val nameBytes = place.name!!.toByteArray(Charsets.UTF_8)
        assertTrue(nameBytes.size <= 31)
        assertEquals(place.name, String(nameBytes, Charsets.UTF_8))
    }

    @Test
    fun `a saved-places file reads back what was written`() {
        // The phone reads the watch's own file to find places made on the
        // watch; it has to read Tracks' own encoding back exactly first.
        val places = listOf(
            WaypointLocation("Trailhead", 45.12345, -122.54321, 1234.0, "trailhead"),
            WaypointLocation("No elevation", -33.9, 151.2, null, "marker"),
        )
        val back = LocationsFitEncoder.decode(LocationsFitEncoder.encode(places))
        assertEquals(places.map { it.name }, back.map { it.name })
        assertEquals(45.12345, back[0].lat, 1e-6)
        assertEquals(-122.54321, back[0].lng, 1e-6)
        assertEquals(1234.0, back[0].elevationMetres!!, 0.2)
        assertEquals("trailhead", back[0].icon)
        assertNull(back[1].elevationMetres)
    }

    @Test
    fun `bytes that are not a FIT file read as no places`() {
        assertTrue(LocationsFitEncoder.decode(ByteArray(20)).isEmpty())
    }
}
