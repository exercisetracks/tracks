// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import com.tracks.device.FindPhone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventFindPhone

/**
 * What the watch's find-my-phone button turns into.
 *
 * The mapping is small and every case is a decision somebody can hear. Ring
 * when the device asked for silence and the app has embarrassed its user in a
 * meeting; stay silent when it asked for noise and the feature simply does not
 * work; ring on an event the parser could not read and a protocol bug becomes
 * an alarm going off in a cinema.
 *
 * Testable without a watch because that is the point of the boundary: the
 * vendored stack produces its own event type and Tracks' own enum is what the
 * rest of the app sees.
 */
class FindPhoneEventTest {

    private fun mapped(event: GBDeviceEventFindPhone.Event): FindPhone? =
        findPhoneFor(event)

    @Test
    fun `the button on the watch rings the phone`() {
        // The only thing Garmin actually sends: FindMyPhoneRequestMessage
        // hard-codes START.
        assertEquals(FindPhone.RING, mapped(GBDeviceEventFindPhone.Event.START))
    }

    @Test
    fun `stopping it on the watch stops the phone`() {
        assertEquals(FindPhone.STOP, mapped(GBDeviceEventFindPhone.Event.STOP))
    }

    @Test
    fun `a device that asked for silence gets silence`() {
        // Upstream's other vendors send these. Answering them with a ringtone
        // would override a choice the user made on the device itself.
        assertEquals(FindPhone.VIBRATE, mapped(GBDeviceEventFindPhone.Event.START_VIBRATE))
        assertEquals(FindPhone.VIBRATE, mapped(GBDeviceEventFindPhone.Event.VIBRATE))
    }

    @Test
    fun `an explicit ring is a ring`() {
        assertEquals(FindPhone.RING, mapped(GBDeviceEventFindPhone.Event.RING))
    }

    @Test
    fun `a message that could not be read makes no noise`() {
        // UNKNOWN means the stack failed to understand something. Guessing
        // "ring" would make every future parser bug audible.
        assertNull(mapped(GBDeviceEventFindPhone.Event.UNKNOWN))
    }
}
