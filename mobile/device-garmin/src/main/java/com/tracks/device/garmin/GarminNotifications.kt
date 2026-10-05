// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationAction
import com.tracks.device.NotificationCategory
import com.tracks.device.NotificationResponse
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventNotificationControl
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType

/**
 * Translates a Tracks notification into the shape the vendored Garmin code
 * expects.
 *
 * Small, but it is the whole reason [DeviceNotification] exists as its own type
 * rather than passing Android's `StatusBarNotification` down: the app captures
 * notifications once, in terms it controls, and each vendor translates. A Coros
 * implementation writes its own version of this file and the capture side never
 * learns that a second watch exists.
 */
internal object GarminNotifications {

    fun toSpec(notification: DeviceNotification): NotificationSpec {
        val spec = NotificationSpec(notification.id, notification.timestamp)
        spec.title = notification.title
        spec.body = notification.body
        // Falls back to the title, and has to: for a message the watch draws
        // the *sender* as the notification's heading, and when the spec has
        // none the vendored handler writes a literal "-" there. A chat app
        // that posts a plain titled notification rather than a MessagingStyle
        // one — which is most of them outside the big messengers — would
        // otherwise arrive on the wrist headed by a dash.
        spec.sender = notification.sender ?: notification.title
        spec.sourceName = notification.appName
        spec.sourceAppId = notification.packageName
        spec.type = typeOf(notification.category)
        spec.attachedActions = actionsOf(notification)
        return spec
    }

    /**
     * The watch's action list for one notification: the app's reply, then its
     * buttons, then Clear and Mute app.
     *
     * Order is what the watch draws, top to bottom, so the thing someone
     * raised their wrist to do comes first and the one that stops an app
     * reaching the watch at all comes last, where it is hardest to hit by
     * accident.
     *
     * At most one reply, because the vendored handler remembers one reply
     * handle per notification and would answer every reply through whichever
     * came last. At most [MAX_CUSTOM] buttons, because that is how many custom
     * action codes Garmin's protocol has (CUSTOM_ACTION_1…5); the rest are
     * dropped rather than mislabelled.
     */
    internal fun actionsOf(notification: DeviceNotification): ArrayList<NotificationSpec.Action> {
        val out = ArrayList<NotificationSpec.Action>()
        var replied = false
        var custom = 0
        notification.actions.forEachIndexed { index, action ->
            val type = when (action.kind) {
                NotificationAction.Kind.REPLY ->
                    if (replied) return@forEachIndexed else NotificationSpec.Action.TYPE_WEARABLE_REPLY
                NotificationAction.Kind.SIMPLE ->
                    if (custom >= MAX_CUSTOM) return@forEachIndexed else NotificationSpec.Action.TYPE_WEARABLE_SIMPLE
            }
            if (type == NotificationSpec.Action.TYPE_WEARABLE_REPLY) replied = true else custom++
            out += NotificationSpec.Action().apply {
                this.type = type
                title = label(action.label)
                handle = actionHandle(notification.id, index)
            }
        }
        out += NotificationSpec.Action().apply {
            type = NotificationSpec.Action.TYPE_SYNTECTIC_DISMISS
            title = "Clear"
        }
        out += NotificationSpec.Action().apply {
            type = NotificationSpec.Action.TYPE_SYNTECTIC_MUTE
            title = "Mute app"
        }
        return out
    }

    /**
     * Translate the watch acting on a notification into the vendor-neutral
     * response, or null when it names nothing Tracks offered.
     *
     * The vendored handler reports every kind as one event type with a
     * `handle` that means different things — see [actionHandle] for how the two
     * are told apart. A custom button arrives as REPLY with no text, which is
     * upstream reusing the event rather than the user replying.
     */
    fun responseOf(event: GBDeviceEventNotificationControl): NotificationResponse? {
        val action = decodeActionHandle(event.handle)
        val id = action?.first ?: event.handle.toInt()
        return when (event.event) {
            GBDeviceEventNotificationControl.Event.DISMISS -> NotificationResponse.Dismiss(id)
            GBDeviceEventNotificationControl.Event.MUTE -> NotificationResponse.MuteApp(id)
            GBDeviceEventNotificationControl.Event.REPLY -> {
                val text = event.reply
                when {
                    !text.isNullOrBlank() -> NotificationResponse.Reply(id, text)
                    action != null -> NotificationResponse.Action(id, action.second)
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * An action's handle: the notification id and the action's index, packed
     * above every value a notification id can take.
     *
     * The packing is not tidiness. The watch's answer comes back with either
     * the *notification* id (a dismissal, a mute, and a reply to a message,
     * where the vendored handler puts the id rather than the action's handle)
     * or the *action* handle (a reply to anything else, and every custom
     * button) — in the same field, with nothing saying which. Notification ids
     * are Ints, so as Longs they lie in [-2^31, 2^31); a handle at 2^40 and up
     * cannot be mistaken for one, and carries the id with it, so a custom
     * button needs no table to find its notification.
     */
    internal fun actionHandle(notificationId: Int, index: Int): Long {
        require(index in 0 until 16)
        return ACTION_HANDLE_BASE or ((notificationId.toLong() and 0xFFFF_FFFFL) shl 4) or index.toLong()
    }

    /** (notification id, action index), or null when [handle] is a plain notification id. */
    internal fun decodeActionHandle(handle: Long): Pair<Int, Int>? {
        if (handle < ACTION_HANDLE_BASE) return null
        return ((handle shr 4) and 0xFFFF_FFFFL).toInt() to (handle and 0xF).toInt()
    }

    /**
     * Short enough for the wire and the screen. The vendored encoder writes a
     * label's length as one byte and then round-trips the whole action list
     * through a String, which mangles any byte from 0x80 up — so a label of
     * 128 bytes or more corrupts every action after it. Twenty characters is
     * well inside that even in a script of three-byte characters, and is about
     * what fits across a watch face anyway.
     */
    private fun label(text: String): String {
        val trimmed = text.trim()
        if (trimmed.codePointCount(0, trimmed.length) <= MAX_LABEL) return trimmed
        // By code point, so an emoji is never cut in half into a lone surrogate.
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_LABEL - 1)) + "…"
    }

    private const val ACTION_HANDLE_BASE = 1L shl 40
    private const val MAX_CUSTOM = 5
    private const val MAX_LABEL = 20

    /**
     * Map onto the notification types Garmin watches actually distinguish.
     *
     * The watch uses the type to pick an icon and a vibration pattern, nothing
     * more, so a near miss is cosmetic — but a *wrong* one is not: mapping a
     * chat message to the call type would make a watch buzz like an incoming
     * call for a text. [NotificationType.GENERIC] is the honest answer whenever
     * the category does not correspond to something the watch draws, which is
     * why several of these land there rather than being forced into a
     * lookalike.
     */
    private fun typeOf(category: NotificationCategory): NotificationType = when (category) {
        NotificationCategory.MESSAGE -> NotificationType.GENERIC_SMS
        NotificationCategory.EMAIL -> NotificationType.GENERIC_EMAIL
        NotificationCategory.CALL -> NotificationType.GENERIC_PHONE
        NotificationCategory.CALENDAR -> NotificationType.GENERIC_CALENDAR
        NotificationCategory.ALARM -> NotificationType.GENERIC_ALARM_CLOCK
        NotificationCategory.NAVIGATION -> NotificationType.GENERIC_NAVIGATION
        NotificationCategory.SOCIAL,
        NotificationCategory.TRANSPORT,
        NotificationCategory.GENERIC -> NotificationType.UNKNOWN
    }
}
