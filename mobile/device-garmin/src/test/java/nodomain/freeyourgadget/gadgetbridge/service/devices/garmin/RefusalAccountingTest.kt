// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.deviceevents.FileDownloadedDeviceEvent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Restart your watch" is said only when the watch itself refused.
 *
 * Every download the phone gave up on -- silence, no free channel, a payload
 * that would not inflate -- used to be counted as a refusal, so a fault on the
 * phone's side told people to restart a watch that was fine. Robolectric only
 * because the support class logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
class RefusalAccountingTest {

    @Test
    fun `the watch saying no counts as a refusal`() {
        val support = GarminSupport()
        support.evaluateGBDeviceEvent(FileDownloadedDeviceEvent().apply { success = false })
        assertEquals(1, support.takeRefusedDownloads())
        assertEquals(0, support.takePhoneSideDownloadFailures())
    }

    @Test
    fun `the phone giving up is not the watch refusing`() {
        val support = GarminSupport()
        // No V2 communicator: the phone abandons the transfer itself.
        support.downloadFileFromServiceV2(5)
        assertEquals(0, support.takeRefusedDownloads())
        assertEquals(1, support.takePhoneSideDownloadFailures())
    }
}
