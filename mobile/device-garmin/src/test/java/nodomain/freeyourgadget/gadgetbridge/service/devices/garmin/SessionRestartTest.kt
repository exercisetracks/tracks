// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A watch that starts a new GFDI session on a link that stayed up is reported,
 * so the link can be rebuilt.
 *
 * The failure this prevents: CommunicatorV2 re-registers a GFDI channel the
 * watch closed, the watch re-runs its handshake, and the phone, already
 * initialised, ignored it. The watch's side of the old session — battery
 * updates, sync-ready, the Explore session — was gone, so the battery froze and
 * every Explore session failed until the app was force-stopped. Robolectric
 * because the vendored message logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SessionRestartTest {

    @BeforeTest
    fun init() {
        GBApplication.init(ApplicationProvider.getApplicationContext())
    }

    private fun supportIn(state: GBDevice.State): Pair<GarminSupport, () -> Int> {
        val device = GBDevice("AA:BB:CC:DD:EE:FF", "fenix 6X Pro", null)
        device.setState(state)
        val support = GarminSupport()
        support.setContext(device, null, ApplicationProvider.getApplicationContext())
        var restarts = 0
        support.setSyncListener(object : GarminSupport.SyncListener {
            override fun onWatchReady() {}
            override fun onSyncFinished() {}
            override fun onSessionRestarted() { restarts++ }
        })
        return support to { restarts }
    }

    @Test
    fun `device information on an initialised link is a restarted session`() {
        val (support, restarts) = supportIn(GBDevice.State.INITIALIZED)
        support.onMessage(deviceInformationFrame(product = 3291, unit = 1))
        assertEquals(1, restarts())
    }

    @Test
    fun `device information during the first handshake is not`() {
        // Every connection opens this way; reporting it would rebuild every
        // link the moment it came up.
        val (support, restarts) = supportIn(GBDevice.State.INITIALIZING)
        support.onMessage(deviceInformationFrame(product = 3291, unit = 1))
        assertEquals(0, restarts())
    }
}
