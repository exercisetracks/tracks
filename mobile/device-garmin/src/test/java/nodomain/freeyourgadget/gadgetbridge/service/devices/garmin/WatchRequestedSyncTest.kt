// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.proto.garmin.GdiFileSyncService
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sync pressed on the watch reaches Tracks' sync, and not Gadgetbridge's.
 *
 * The watch says so with file sync's start_sync_notification (handled from
 * Gadgetbridge 0.94). Upstream answers it by starting its own fetch, which in
 * Tracks would pull files outside the sync runner and its lock; it has to
 * come out at the sync listener instead, where WatchManager runs the
 * ordinary sync. Robolectric because the vendored handler logs through
 * android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WatchRequestedSyncTest {

    @BeforeTest
    fun init() {
        GBApplication.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `sync pressed on the watch asks the phone for its sync`() {
        val device = GBDevice("AA:BB:CC:DD:EE:FF", "fenix 6X Pro", null)
        device.setState(GBDevice.State.INITIALIZED)
        val support = GarminSupport()
        support.setContext(device, null, ApplicationProvider.getApplicationContext())
        var requested = 0
        support.setSyncListener(object : GarminSupport.SyncListener {
            override fun onWatchReady() {}
            override fun onSyncFinished() {}
            override fun onWatchRequestedSync() { requested++ }
        })

        val reply = FileSyncServiceHandler(support).handle(
            GdiFileSyncService.FileSyncService.newBuilder()
                .setStartSyncNotification(GdiFileSyncService.StartSyncNotification.getDefaultInstance())
                .build(),
        )

        assertEquals(1, requested)
        assertNull(reply, "a notification, answered with the transport's ack alone")
    }
}
