// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.garmin.GarminCapability
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.CapabilitiesDeviceEvent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Courses and places are routed by what the watch's handshake declares, not by
 * which vendor it is.
 *
 * The route used to be "is this a Garmin": every Garmin went to Explore sync,
 * so one without it would have received no courses at all, and the FIT-file
 * route meant for it was unreachable. The fenix this was all measured on must
 * stay on Explore, so its own capability bytes are the first case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ExploreCapabilityTest {

    @BeforeTest
    fun init() {
        GBApplication.init(ApplicationProvider.getApplicationContext())
    }

    private fun supportDeclaring(capabilities: Set<GarminCapability>): GarminSupport {
        val device = GBDevice("AA:BB:CC:DD:EE:FF", "fenix 6X Pro", null)
        device.setState(GBDevice.State.INITIALIZING)
        val support = GarminSupport()
        support.setContext(device, null, ApplicationProvider.getApplicationContext())
        support.evaluateGBDeviceEvent(CapabilitiesDeviceEvent(capabilities))
        return support
    }

    @Test
    fun `a fenix 6X Pro declares Explore sync`() {
        // The watch's CONFIGURATION in the 2026-10-12 Explore capture, after
        // its length byte: watch->phone, handle 2, at 3.414 s.
        val fenix = GarminCapability.setFromBinary(hex("fbff3fa6fade5e4efb7536cd03"))
        assertTrue(GarminCapability.EXPLORE_SYNC in fenix)
        assertTrue(supportDeclaring(fenix).speaksExploreSync())
    }

    @Test
    fun `a watch that does not declare it is not sent Explore sessions`() {
        val older = setOf(GarminCapability.COURSE_DOWNLOAD, GarminCapability.SYNC)
        assertFalse(supportDeclaring(older).speaksExploreSync())
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
