// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationAction
import com.tracks.device.NotificationAction.Kind.REPLY
import com.tracks.device.NotificationAction.Kind.SIMPLE
import com.tracks.device.NotificationCategory
import com.tracks.device.NotificationResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType

class GarminNotificationsTest {

    private fun notification(
        category: NotificationCategory = NotificationCategory.MESSAGE,
    ) = DeviceNotification(
        id = 42,
        appName = "Signal",
        packageName = "org.thoughtcrime.securesms",
        title = "Ada",
        body = "on my way",
        category = category,
        timestamp = 1_760_000_000_000L,
        sender = "Ada",
    )

    @Test
    fun `identity and content survive the translation`() {
        val spec = GarminNotifications.toSpec(notification())

        // The id is what a later dismissal addresses; losing it means a
        // notification the user cleared on the phone stays on the wrist.
        assertEquals(42, spec.getId())
        assertEquals("Ada", spec.title)
        assertEquals("on my way", spec.body)
        assertEquals("Ada", spec.sender)
        assertEquals("Signal", spec.sourceName)
        assertEquals("org.thoughtcrime.securesms", spec.sourceAppId)
    }

    @Test
    fun `a chat message is not mapped to the phone type`() {
        // The types the watch distinguishes drive its vibration pattern.
        // Mapping a text onto the call type makes the watch buzz like an
        // incoming call for every message, which is the worst kind of wrong:
        // plausible in code review, immediately obvious on a wrist.
        assertEquals(NotificationType.GENERIC_SMS, typeFor(NotificationCategory.MESSAGE))
        assertEquals(NotificationType.GENERIC_PHONE, typeFor(NotificationCategory.CALL))
    }

    @Test
    fun `each category the watch draws gets its own type`() {
        assertEquals(NotificationType.GENERIC_EMAIL, typeFor(NotificationCategory.EMAIL))
        assertEquals(NotificationType.GENERIC_CALENDAR, typeFor(NotificationCategory.CALENDAR))
        assertEquals(NotificationType.GENERIC_ALARM_CLOCK, typeFor(NotificationCategory.ALARM))
        assertEquals(NotificationType.GENERIC_NAVIGATION, typeFor(NotificationCategory.NAVIGATION))
    }

    @Test
    fun `categories the watch has no icon for stay unknown rather than guessing`() {
        assertEquals(NotificationType.UNKNOWN, typeFor(NotificationCategory.SOCIAL))
        assertEquals(NotificationType.UNKNOWN, typeFor(NotificationCategory.TRANSPORT))
        assertEquals(NotificationType.UNKNOWN, typeFor(NotificationCategory.GENERIC))
    }

    /**
     * The watch draws the *sender* as a message's heading, and the vendored
     * handler writes a literal "-" when the spec has none. An app that posts a
     * plainly titled message notification rather than a MessagingStyle one has
     * no sender to capture, and arriving on the wrist headed by a dash is not
     * an improvement on its title.
     */
    @Test
    fun `a message with no known sender is headed by its title`() {
        val spec = GarminNotifications.toSpec(notification().copy(sender = null))

        assertEquals("Ada", spec.sender)
    }

    @Test
    fun `a notification with no text still converts`() {
        // Silent, bodyless notifications are common (media controls, ongoing
        // downloads). Dropping them on a null would lose the dismissal too.
        val bare = notification().copy(title = null, body = null, sender = null)
        val spec = GarminNotifications.toSpec(bare)
        assertEquals(42, spec.getId())
        assertEquals(null, spec.title)
    }

    // ── Acting from the watch ────────────────────────────────────────────────

    private fun withActions(vararg actions: NotificationAction) =
        notification().copy(actions = actions.toList())

    private fun control(event: GBDeviceEventNotificationControl.Event, handle: Long, reply: String? = null) =
        GBDeviceEventNotificationControl().also {
            it.event = event
            it.handle = handle
            it.reply = reply
        }

    @Test
    fun `the watch lists the reply first and the mute last`() {
        // What someone raised their wrist to do comes first; the one that stops
        // an app reaching the watch at all is the hardest to hit by accident.
        val spec = GarminNotifications.toSpec(
            withActions(NotificationAction("Mark as read", SIMPLE), NotificationAction("Reply", REPLY)),
        )
        assertEquals(
            listOf("Mark as read", "Reply", "Clear", "Mute app"),
            spec.attachedActions.map { it.title },
        )
        assertEquals(NotificationSpec.Action.TYPE_WEARABLE_REPLY, spec.attachedActions[1].type)
        assertEquals(NotificationSpec.Action.TYPE_SYNTECTIC_MUTE, spec.attachedActions.last().type)
    }

    @Test
    fun `every notification can be cleared and muted even with no buttons of its own`() {
        val types = GarminNotifications.toSpec(notification()).attachedActions.map { it.type }
        assertEquals(
            listOf(NotificationSpec.Action.TYPE_SYNTECTIC_DISMISS, NotificationSpec.Action.TYPE_SYNTECTIC_MUTE),
            types,
        )
    }

    @Test
    fun `buttons beyond the protocol's five custom slots are dropped, not mislabelled`() {
        val spec = GarminNotifications.toSpec(
            withActions(*Array(7) { NotificationAction("B$it", SIMPLE) }),
        )
        assertEquals(listOf("B0", "B1", "B2", "B3", "B4"), spec.attachedActions.dropLast(2).map { it.title })
    }

    @Test
    fun `only the first reply action is offered`() {
        // The vendored handler keeps one reply handle per notification; a
        // second would silently take over every reply.
        val spec = GarminNotifications.toSpec(
            withActions(NotificationAction("Reply", REPLY), NotificationAction("Reply all", REPLY)),
        )
        assertEquals(1, spec.attachedActions.count { it.isReply })
    }

    @Test
    fun `an action handle can never be mistaken for a notification id`() {
        // Both come back in the same field. Notification ids are hashes, so
        // they are routinely negative — the edge cases are the real cases.
        for (id in listOf(0, 1, -1, 42, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull(GarminNotifications.decodeActionHandle(id.toLong()), "id $id")
            for (index in listOf(0, 4, 15)) {
                val handle = GarminNotifications.actionHandle(id, index)
                assertEquals(id to index, GarminNotifications.decodeActionHandle(handle))
            }
        }
    }

    @Test
    fun `pressing an app's button on the watch presses that button`() {
        val handle = GarminNotifications.actionHandle(-7, 2)
        // A custom action arrives as REPLY with no text; see responseOf.
        assertEquals(
            NotificationResponse.Action(-7, 2),
            GarminNotifications.responseOf(control(GBDeviceEventNotificationControl.Event.REPLY, handle)),
        )
    }

    @Test
    fun `a reply to a message comes back addressed by notification id`() {
        // For the SMS type the vendored handler leaves the notification id in
        // the handle and puts the phone number aside, rather than the action's
        // handle as it does for every other type. Both have to land as a reply.
        assertEquals(
            NotificationResponse.Reply(42, "On my way"),
            GarminNotifications.responseOf(control(GBDeviceEventNotificationControl.Event.REPLY, 42, "On my way")),
        )
        assertEquals(
            NotificationResponse.Reply(42, "On my way"),
            GarminNotifications.responseOf(
                control(GBDeviceEventNotificationControl.Event.REPLY, GarminNotifications.actionHandle(42, 0), "On my way"),
            ),
        )
    }

    @Test
    fun `clear and mute name the notification they were pressed on`() {
        assertEquals(
            NotificationResponse.Dismiss(-99),
            GarminNotifications.responseOf(control(GBDeviceEventNotificationControl.Event.DISMISS, -99)),
        )
        assertEquals(
            NotificationResponse.MuteApp(-99),
            GarminNotifications.responseOf(control(GBDeviceEventNotificationControl.Event.MUTE, -99)),
        )
    }

    @Test
    fun `a reply with no text and no action is ignored`() {
        assertNull(GarminNotifications.responseOf(control(GBDeviceEventNotificationControl.Event.REPLY, 42)))
    }

    private fun typeFor(category: NotificationCategory) =
        GarminNotifications.toSpec(notification(category)).type
}
