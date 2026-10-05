// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.GarminSport
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitCourse
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileCreator
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.AbstractFitRecord
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLap

/**
 * A Garmin course FIT file, built on the phone with no server to ask.
 *
 * Ported from the backend's `fit_course.py`, which every offline trip used
 * to depend on regardless — see `com.tracks.app.device.WatchManager` for
 * where this plugs in and why that dependency is gone. The message layout
 * mirrors it message for message: file_id, file_creator, course, lap, a
 * timer start/stop pair of events, and one record per vertex — built with
 * this codebase's own generated FIT message classes
 * ([nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.codegen.FitCodeGen])
 * rather than hand-packed bytes, the same way
 * [nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.GpxRouteFileConverter]
 * already builds a course from a GPX.
 *
 * ## What is missing on purpose
 *
 * Turn-by-turn course points (FIT message 32, `FitCoursePoint`) — the
 * phone's cached library carries a line, not the turn prompts the server
 * derives from it, so every course pushed this way is a breadcrumb rather
 * than a turn-by-turn one. A synced course still gets its turn prompts the
 * moment there is signal for the normal upload path to run again.
 * Per-point altitude is similarly absent: the shared courses GeoJSON this
 * reads its line from strips elevation before it ever reaches the phone
 * (see `courseCoordinatesFromGeoJson`), so only the lap's total ascent is
 * real — total descent is always sent as zero, since the cached library
 * this runs from carries only ascent — real navigation reads position and
 * distance, not a course file's altitude column.
 */
object CourseFitEncoder {

    private const val PRODUCT_CONNECT = 65534
    private const val FILE_CREATOR_SOFTWARE_VERSION = 2609

    /** Course name's wire capacity is 16 bytes; one is held for the NUL terminator. */
    private const val NAME_BYTES = 15

    /** course_capabilities bitfield: processed|valid|time|distance|position. No navigation bit — see the class doc. */
    private const val CAPABILITIES = 1L or 2L or 4L or 8L or 16L

    private val SPEED_MPS = mapOf(
        "running" to 3.0, "trail_running" to 2.6, "walking" to 1.3, "hiking" to 1.1,
        "cycling" to 5.5, "road_cycling" to 7.0, "mountain_biking" to 3.5, "gravel_cycling" to 5.5,
    )
    private const val DEFAULT_SPEED_MPS = 2.0

    private val SPORT = mapOf(
        "running" to GarminSport.RUN, "trail_running" to GarminSport.TRAIL_RUN,
        "road_running" to GarminSport.STREET_RUN, "walking" to GarminSport.WALK,
        "hiking" to GarminSport.HIKE, "cycling" to GarminSport.BIKE,
        "road_cycling" to GarminSport.ROAD_BIKE, "gravel_cycling" to GarminSport.GRAVEL_BIKE,
        "mountain_biking" to GarminSport.MTB,
    )
    private val DEFAULT_SPORT = GarminSport.HIKE

    /**
     * `coordinates` is `[lng, lat]` (or `[lng, lat, ele]`, elevation ignored)
     * pairs in that GeoJSON order — see
     * `com.tracks.core.sync.courseCoordinatesFromGeoJson`. Null when there
     * are fewer than two usable points, the one input that cannot become a
     * valid course at all.
     */
    fun encode(
        name: String,
        sport: String,
        courseId: Int,
        coordinates: List<List<Double>>,
        distanceMetres: Double,
        ascentMetres: Double,
        timeCreatedEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): ByteArray? {
        val points = coordinates.filter { it.size >= 2 && it[0].isFinite() && it[1].isFinite() }
        if (points.size < 2) return null

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + haversineMetres(points[i - 1], points[i])
        }
        val totalDistance = distanceMetres.takeIf { it > 0 } ?: cumulative.last()
        val speed = SPEED_MPS[sport.lowercase()] ?: DEFAULT_SPEED_MPS
        val garminSport = SPORT[sport.lowercase()] ?: DEFAULT_SPORT
        val times = LongArray(points.size) { i ->
            timeCreatedEpochSeconds + (cumulative[i] / speed).roundToLong()
        }

        val records = mutableListOf<RecordData>()

        records += FitFileId.Builder()
            .setType(FileType.FILETYPE.COURSES)
            .setManufacturer(1)
            .setProduct(PRODUCT_CONNECT)
            .setSerialNumber(maxOf(1L, courseId.toLong()))
            .setTimeCreated(timeCreatedEpochSeconds)
            .build(0)

        records += FitFileCreator.Builder()
            .setSoftwareVersion(FILE_CREATOR_SOFTWARE_VERSION)
            .setHardwareVersion(0)
            .build(1)

        records += FitCourse.Builder()
            .setSport(garminSport.type)
            .setName(truncateUtf8(name.ifBlank { "Course" }, NAME_BYTES))
            .setCapabilities(CAPABILITIES)
            .build(2)

        val first = points.first()
        val last = points.last()
        records += FitLap.Builder()
            .setMessageIndex(0)
            .setTimestamp(times.last())
            .setStartTime(times.first())
            .setStartLat(first[1])
            .setStartLong(first[0])
            .setEndLat(last[1])
            .setEndLong(last[0])
            .setTotalElapsedTime((times.last() - times.first()).toDouble())
            .setTotalTimerTime((times.last() - times.first()).toDouble())
            .setTotalDistance(totalDistance)
            .setTotalAscent(ascentMetres.roundToLong().toInt())
            // Always sent, defaulting to 0 exactly like fit_course.py's
            // `int(round(descent_m or 0))` — never omitted. A field this
            // encoder leaves out of a record's *definition* is not "unknown",
            // it is simply absent, and a watch that expects every lap to
            // carry one can reject the whole record rather than treat a
            // missing field as zero. See GarminWeatherEncoder's note on the
            // same gotcha for weather broadcasts — this is the course side
            // of it, found the same way: a file that "sent" over Bluetooth
            // and never appeared in Courses.
            .setTotalDescent(0)
            .build(3)

        records += FitEvent.Builder()
            .setTimestamp(times.first())
            .setEvent(EVENT_TIMER)
            .setEventType(EVENT_TYPE_START)
            .build(4)

        points.indices.forEach { i ->
            records += AbstractFitRecord.Builder()
                .setTimestamp(times[i])
                .setLatitude(points[i][1])
                .setLongitude(points[i][0])
                .setDistance(cumulative[i])
                .build(5)
        }

        records += FitEvent.Builder()
            .setTimestamp(times.last())
            .setEvent(EVENT_TIMER)
            .setEventType(EVENT_TYPE_STOP_ALL)
            .build(4)

        return FitFile(records).outgoingMessage
    }

    /**
     * Equirectangular-approximation distance in metres.
     *
     * Matches `_haversine_m` in `fit_course.py`, not a true haversine despite
     * the name there — accurate to well under a metre over the span of a
     * saved track, and cheap enough to run once per vertex.
     */
    private fun haversineMetres(a: List<Double>, b: List<Double>): Double {
        val meanLatRad = Math.toRadians((a[1] + b[1]) / 2)
        val dx = (b[0] - a[0]) * cos(meanLatRad) * 111_320.0
        val dy = (b[1] - a[1]) * 110_540.0
        return hypot(dx, dy)
    }

    // Raw FIT `event`/`event_type` enum values, not modelled as Kotlin enums —
    // this build's profile leaves both fields as plain ENUM base types with no
    // dedicated field-definition class (see the profile's `EVENT` message).
    // Copied from GpxRouteFileConverter's own use of the same two constants,
    // itself Gadgetbridge's working, hardware-proven course encoder.
    private const val EVENT_TIMER = 0
    private const val EVENT_TYPE_START = 0
    private const val EVENT_TYPE_STOP_ALL = 9
}
