// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.garmin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which protocol a watch is spoken to decides whether a sync takes ten minutes
 * or most of a day, and on a single-channel watch it decides whether the sync
 * happens at all — so the three inputs are worth pinning down away from a real
 * device, since only one watch has ever exercised any of this.
 */
class SyncProtocolDecisionTest {

    private fun decide(multiLink: Boolean, chosen: Boolean? = null, demoted: Boolean = false) =
        GarminSupport.decideSyncProtocol(multiLink, chosen, demoted)

    @Test
    fun `a multi-link watch gets the newer protocol without being asked`() {
        assertTrue(decide(multiLink = true))
    }

    @Test
    fun `a single-channel watch never does, because it has nowhere to put it`() {
        // Not a preference: the newer protocol streams each file down a service
        // channel of its own, and a V1 communicator has exactly one channel.
        assertFalse(decide(multiLink = false))
        assertFalse(decide(multiLink = false, chosen = true))
        assertFalse(decide(multiLink = false, chosen = true, demoted = false))
    }

    @Test
    fun `a watch that failed to answer stays on the older protocol`() {
        assertFalse(decide(multiLink = true, demoted = true))
    }

    @Test
    fun `an explicit choice outranks what we worked out`() {
        // Both directions. Re-enabling a demoted watch is the one that matters:
        // firmware updates happen, and without this the demotion is a one-way
        // door with no visible handle.
        assertTrue(decide(multiLink = true, chosen = true, demoted = true))
        assertFalse(decide(multiLink = true, chosen = false, demoted = false))
    }
}
