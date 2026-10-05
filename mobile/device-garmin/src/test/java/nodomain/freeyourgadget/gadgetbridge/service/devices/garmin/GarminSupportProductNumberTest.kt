// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import java.io.ByteArrayOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import androidx.test.core.app.ApplicationProvider
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.DeviceInformationMessage
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The product number is what chooses which Connect IQ build goes to the watch,
 * and GarminSupport reads it from the raw frame rather than from the vendored
 * parser (which keeps it private). These tests hold the two readings together.
 *
 * Robolectric only because the vendored message logs through android.util.Log
 * and builds its event against GBApplication's context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GarminSupportProductNumberTest {

    @BeforeTest
    fun init() {
        GBApplication.init(ApplicationProvider.getApplicationContext())
    }

    /** A DEVICE_INFORMATION frame as a fēnix 6X Pro sends it, CRC and all. */
    private fun frame(product: Int, unit: Long): ByteArray {
        val body = ByteArrayOutputStream()
        fun u16(v: Int) { body.write(v and 0xFF); body.write((v shr 8) and 0xFF) }
        fun u32(v: Long) { for (i in 0 until 4) body.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun str(s: String) { body.write(s.length); body.write(s.toByteArray()) }
        u16(5024)          // DEVICE_INFORMATION
        u16(150)           // protocol version
        u16(product)
        u32(unit)
        u16(2700)          // software version 27.00
        u16(375)           // max packet size
        str("fenix 6X Pro")
        str("fenix 6X Pro")
        str("006-B3291-00")
        val payload = body.toByteArray()
        val length = 2 + payload.size + 2
        val withLength = byteArrayOf((length and 0xFF).toByte(), (length shr 8).toByte()) + payload
        val crc = ChecksumCalculator.computeCrc(withLength, 0, withLength.size)
        return withLength + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    @Test
    fun `the product number is read from where the vendored parser reads it`() {
        val bytes = frame(product = 3291, unit = 3_412_345_678L)

        // The vendored parser accepts the frame and finds the unit number in
        // the field right after the product number — so the layout this test
        // builds is the one the watch sends, and the offset below is right.
        val parsed = assertIs<DeviceInformationMessage>(GFDIMessage.parseIncoming(bytes))
        val info = parsed.getGBDeviceEvent().filterIsInstance<GBDeviceEventVersionInfo>().single()
        assertEquals("3412345678", info.fwVersion2)

        assertEquals(3291, GarminSupport.productNumberOf(bytes))
    }

    @Test
    fun `a product number above 32767 is not read as negative`() {
        // u16 on the wire. A sign-extended read would make a future product
        // look up as a negative key and miss its build.
        assertEquals(40000, GarminSupport.productNumberOf(frame(product = 40000, unit = 1)))
    }

    @Test
    fun `a frame too short to carry one is unknown rather than garbage`() {
        assertEquals(-1, GarminSupport.productNumberOf(ByteArray(6)))
        assertEquals(-1, GarminSupport.productNumberOf(null))
    }
}
