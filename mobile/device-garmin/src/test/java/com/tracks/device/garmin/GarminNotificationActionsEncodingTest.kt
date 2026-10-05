// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationAction
import com.tracks.device.NotificationCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.NotificationsHandler
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [GarminNotifications] hands the vendored encoder, encoded by it.
 *
 * Robolectric only because the vendored attribute enum builds an Android
 * SparseArray when it loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GarminNotificationActionsEncodingTest {

    private fun withActions(vararg actions: NotificationAction) = DeviceNotification(
        id = 42,
        appName = "Signal",
        packageName = "org.thoughtcrime.securesms",
        title = "Ada",
        body = "on my way",
        category = NotificationCategory.MESSAGE,
        timestamp = 1_760_000_000_000L,
        actions = actions.toList(),
    )

    /**
     * The vendored encoder writes each label behind a one-byte length and then
     * passes the whole list through a String. That survives UTF-8 labels only
     * while no length byte reaches 0x80, so a long label must be cut before it
     * gets there — otherwise every action after it is garbage on the watch.
     */
    @Test
    fun `a long non-ASCII label is cut short enough to survive the vendored encoder`() {
        // 78 characters, 145 bytes of UTF-8: past the 0x80 line, where the
        // untruncated label arrives with a length byte of 239.
        val long = "Отметить как прочитанное, переместить в архив и больше не показывать сообщения"
        assertTrue(long.toByteArray().size >= 0x80)
        val spec = GarminNotifications.toSpec(withActions(NotificationAction(long, NotificationAction.Kind.SIMPLE)))
        val bytes = NotificationsHandler.NotificationAttribute.ACTIONS.getNotificationSpecAttribute(spec, 0)

        // count, then per action: code, icon flags, length, label.
        assertEquals(3, bytes[0].toInt())
        var at = 1
        val labels = mutableListOf<String>()
        repeat(3) {
            val length = bytes[at + 2].toInt() and 0xFF
            assertTrue(length < 0x80, "length byte $length")
            labels += String(bytes, at + 3, length, Charsets.UTF_8)
            at += 3 + length
        }
        assertEquals(bytes.size, at)
        assertTrue(labels[0].endsWith("…") && long.startsWith(labels[0].dropLast(1)), labels[0])
        assertEquals(listOf("Clear", "Mute app"), labels.drop(1))
    }

}
