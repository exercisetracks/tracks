// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiSmartProto.Smart

/**
 * The once-a-second position a recording watch sends (core service, message 7),
 * built field for field as the fenix 6X sent it to Garmin Explore in the
 * 2026-10-12 capture: position, altitude, timestamp and type, and nothing
 * else. The coordinates are made up; the capture's are personal.
 *
 * With the proto's other fields required, every such report failed to parse
 * and was dropped whole, and the phone's live track moved only when an Explore
 * sync read the line so far -- about every twenty minutes.
 */
class LivePositionReportTest {

    private fun encode(block: (CodedOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(out)
        block(coded)
        coded.flush()
        return out.toByteArray()
    }

    /** One report, wrapped the way it arrives: Smart → core service → notification. */
    private fun report(latSemicircles: Int, lonSemicircles: Int): ByteArray {
        val latLon = encode { it.writeSInt32(1, latSemicircles); it.writeSInt32(2, lonSemicircles) }
        val data = encode {
            it.writeByteArray(1, latLon)
            it.writeFloat(2, 565.2f)
            it.writeUInt32(3, 1_160_778_251) // Garmin epoch, as captured
            it.writeEnum(6, 2) // REALTIME_TRACKING
        }
        val notification = encode { it.writeByteArray(1, data) }
        val core = encode { it.writeByteArray(7, notification) }
        return encode { it.writeByteArray(13, core) }
    }

    private fun semicircles(degrees: Double) = (degrees * 2147483648.0 / 180.0).toInt()

    private fun parse(bytes: ByteArray) =
        Smart.parseFrom(bytes).coreService.locationUpdatedNotification.locationDataList

    @Test
    fun `a report with only what the watch sends parses`() {
        val points = GarminSupport.livePoints(parse(report(semicircles(46.55), semicircles(-7.98))))
        assertEquals(1, points.size)
        val p = points.single()
        assertTrue(abs(p.lat - 46.55) < 1e-6, "lat ${p.lat}")
        assertTrue(abs(p.lng - -7.98) < 1e-6, "a western longitude stays negative: ${p.lng}")
        assertEquals(565.2, p.altitude!!, 0.01)
        assertEquals(1_160_778_251L + 631_065_600L, p.epochSeconds)
    }

    @Test
    fun `a report before the watch has a fix is not a point`() {
        // Both coordinates 0x7FFFFFFF: what the watch sends for its first
        // seconds of recording. Taken as a position it is 180 degrees north.
        val points = GarminSupport.livePoints(parse(report(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertTrue(points.isEmpty())
    }

    @Test
    fun `a report without altitude keeps the point`() {
        val latLon = encode { it.writeSInt32(1, semicircles(46.0)); it.writeSInt32(2, semicircles(7.0)) }
        val data = encode { it.writeByteArray(1, latLon) }
        val smart = encode { s ->
            s.writeByteArray(13, encode { c -> c.writeByteArray(7, encode { it.writeByteArray(1, data) }) })
        }
        val p = GarminSupport.livePoints(parse(smart)).single()
        assertNull(p.altitude)
        assertNull(p.epochSeconds)
    }

    /**
     * The watch sends these reports only to a client that subscribed, and
     * Tracks never did: in capture c all 33 went to Garmin Explore, which had
     * asked, and none to Connect on the same watch. Explore's two requests,
     * byte for byte: nothing enabled, then REALTIME_TRACKING with 5 as a
     * varint and 1.0 as a float.
     */
    @Test
    fun `the subscription is Explore's, byte for byte`() {
        val off = encode { s -> s.writeByteArray(13, encode { c -> c.writeByteArray(5, encode { it.writeBool(1, false) }) }) }
        val request = encode { it.writeEnum(1, 2); it.writeUInt32(2, 5); it.writeFloat(3, 1.0f) }
        val on = encode { s ->
            s.writeByteArray(13, encode { c -> c.writeByteArray(5, encode { it.writeBool(1, true); it.writeByteArray(2, request) }) })
        }
        val sent = GarminSupport.liveTrackingRequests().map { it.toByteArray().toList() }
        assertEquals(listOf(off.toList(), on.toList()), sent)
    }
}
