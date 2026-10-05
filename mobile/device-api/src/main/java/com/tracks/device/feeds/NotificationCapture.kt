// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.os.Build
import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationAction
import com.tracks.device.NotificationCategory

/**
 * Turns Android notifications into something a watch can display.
 *
 * ## What is deliberately dropped
 *
 * Reading notifications means reading everything the user receives — messages,
 * two-factor codes, banking alerts. That access is granted once, in a system
 * dialog most people click through, and then it is total. So this takes the
 * narrowest slice that makes the feature work and discards the rest at the
 * boundary, before it can be logged, stored, or synced:
 *
 * - **Ongoing notifications** (media players, downloads, navigation chrome)
 *   never buzz a wrist usefully and would spam it constantly.
 * - **Group summaries** duplicate the children Android also delivers.
 * - **Local-only** notifications are explicitly flagged by their app as not for
 *   other devices. Honouring that is the whole point of the flag.
 * - **Our own notifications**, which would otherwise loop.
 *
 * On top of that sits [RelayPolicy] — the apps the user has blacklisted and
 * whether silent notifications count — which is preference rather than
 * capability and so arrives as an argument.
 *
 * Nothing here is persisted. A [DeviceNotification] exists to be handed to a
 * watch and then forgotten.
 */
object NotificationCapture {

    /**
     * @param policy what the user has allowed through — see [RelayPolicy].
     * @param importance the notification's effective importance, from its
     *   ranking. Null when the caller could not read one.
     * @return null when the notification should not leave the phone.
     */
    fun from(
        sbn: StatusBarNotification,
        packageManager: PackageManager?,
        ownPackage: String,
        policy: RelayPolicy = RelayPolicy(),
        importance: Int? = null,
    ): DeviceNotification? {
        val notification = sbn.notification ?: return null

        if (sbn.packageName == ownPackage) return null
        // The user's rules first: they are cheaper than reading extras, and a
        // blacklisted app should not have its contents touched at all.
        if (!policy.allows(sbn.packageName, importance)) return null
        if (!shouldRelay(sbn, notification)) return null

        val extras = notification.extras ?: Bundle()
        val title = extras.charSequence(Notification.EXTRA_TITLE)
        val body = extras.charSequence(Notification.EXTRA_TEXT)
            ?: extras.charSequence(Notification.EXTRA_BIG_TEXT)

        // A notification with neither is chrome — a progress bar or an icon-only
        // status entry. There is nothing to show on a watch.
        if (title.isNullOrBlank() && body.isNullOrBlank()) return null

        return DeviceNotification(
            id = idOf(sbn),
            appName = appLabel(sbn.packageName, packageManager),
            packageName = sbn.packageName,
            title = title,
            body = body,
            category = NotificationCategory.fromAndroid(notification.category),
            timestamp = sbn.postTime,
            sender = senderOf(extras),
            actions = actionsOf(notification).map { it.first },
        )
    }

    /**
     * The notification's own buttons a watch can usefully press, paired with
     * the Android action that performs each.
     *
     * The pairs are the contract with the watch: the label goes out, the index
     * comes back, and the Android half — which never leaves the phone — is
     * looked up by that index. So the same notification must always give the
     * same list in the same order, which it does: this reads nothing but the
     * notification.
     *
     * - **Wearable actions win** when the app declared any. They are what the
     *   app designed for a watch — WhatsApp and Google Messages put their
     *   wrist-sized reply and "Mark as read" there — and the phone actions are
     *   then often the ones that would open a screen.
     * - **A reply** is an action with a free-form [android.app.RemoteInput].
     *   One with only fixed choices is skipped: the watch answers with its own
     *   quick-reply text, which such an input would refuse.
     * - **Actions that open an activity are skipped**, where Android can say
     *   (API 31+). Pressing one from the wrist would try to start a screen on a
     *   phone in a pocket, which background-launch rules block anyway — a
     *   button that silently does nothing is worse than no button.
     */
    fun actionsOf(notification: Notification): List<Pair<NotificationAction, Notification.Action>> {
        val wearable = Notification.WearableExtender(notification).actions.orEmpty()
        val source = wearable.ifEmpty { notification.actions?.toList().orEmpty() }
        return source.mapNotNull { action ->
            val label = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val intent = action.actionIntent ?: return@mapNotNull null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.isActivity) return@mapNotNull null
            val inputs = action.remoteInputs.orEmpty()
            val kind = when {
                inputs.isEmpty() -> NotificationAction.Kind.SIMPLE
                inputs.any { it.allowFreeFormInput } -> NotificationAction.Kind.REPLY
                else -> return@mapNotNull null
            }
            NotificationAction(label, kind) to action
        }
    }

    /**
     * Who the watch should say a message is from.
     *
     * ## The bug this replaces
     *
     * This used to read `android.selfDisplayName` — [Notification]'s
     * `EXTRA_SELF_DISPLAY_NAME`, which is the name *the phone's own owner* goes
     * by in the conversation. Every `MessagingStyle` notification carries it,
     * because the style needs it to tell the user's messages from everyone
     * else's, so it is always present and always populated. It is never the
     * person who wrote to you.
     *
     * That went straight to the wrist. [com.tracks.device.DeviceNotification.sender]
     * becomes the Garmin spec's sender, and for a message the Garmin protocol
     * draws the *sender* as the notification's title — so Signal, which builds
     * every chat notification with `MessagingStyle`, showed the user their own
     * name as the heading of every text they received, from everyone. Both
     * Gadgetbridge and Garmin Connect get this right by never reading that
     * field in the first place.
     *
     * ## What is read instead
     *
     * In order of how well each answers "whose message is this":
     *
     * 1. The **conversation title**, when there is one. `MessagingStyle` sets
     *    it for group chats, and the group is the right heading there — which
     *    of its members spoke is already in the body ("Ada: on my way"), and
     *    naming only that person makes a group look like a private message.
     * 2. The **sender of the most recent message**, which is the one-to-one
     *    case. Taken from the message rather than the notification title
     *    because a title can be a summary ("3 new messages") while the message
     *    itself always knows who wrote it.
     * 3. **Nothing.** A notification that is not a conversation has no sender,
     *    and saying so honestly lets the device layer fall back to the title
     *    rather than inventing one.
     */
    internal fun senderOf(extras: Bundle): String? =
        extras.charSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: lastMessageSender(extras)

    /**
     * The author of the newest message in a `MessagingStyle` notification.
     *
     * Read as the plain `sender` string rather than the `sender_person`
     * [android.app.Person]: the platform writes both — the name for the benefit
     * of listeners older than API 28, the Person alongside it — and androidx's
     * `NotificationCompat` keeps writing the name for the same reason. The
     * string is therefore the one key present on every API level this app
     * supports, and it holds exactly what a watch can draw.
     *
     * Walks backwards and skips messages with no sender: that is how the
     * platform marks the ones the *user* wrote (a reply sent from a laptop
     * shows up in the same list), and attributing an incoming notification to
     * the user is the very bug above.
     */
    private fun lastMessageSender(extras: Bundle): String? {
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        for (index in messages.indices.reversed()) {
            val message = messages[index] as? Bundle ?: continue
            val sender = message.getCharSequence(KEY_MESSAGE_SENDER)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            if (sender != null) return sender
        }
        return null
    }

    /**
     * A stable id that is unique across apps.
     *
     * **Not `sbn.id`.** Android's notification id is chosen by the posting app
     * and is only unique within it — a great many apps post their one
     * notification as id 0 or 1. Passing that to the watch made two unrelated
     * notifications collide: the Garmin handler keys its queue by id, so the
     * second arrival was treated as an *update* of the first and sent as MODIFY
     * rather than ADD. In practice a mail and a message an hour apart were one
     * entry on the wrist, and dismissing either dismissed the other.
     *
     * `sbn.key` is the system's own identity for a notification — package, id,
     * tag and user — and is exactly what stays constant when an app updates a
     * notification in place and changes when it posts a genuinely new one. That
     * is the distinction ADD-versus-MODIFY is asking about, so hashing the key
     * gives the watch the right answer in both directions.
     *
     * Used for dismissal too: the id sent to remove a notification has to be
     * the same one that added it, so both sides go through here.
     */
    fun idOf(sbn: StatusBarNotification): Int = sbn.key?.hashCode() ?: sbn.id

    internal fun shouldRelay(sbn: StatusBarNotification, notification: Notification): Boolean {
        val flags = notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.visibility == Notification.VISIBILITY_SECRET) return false
        // The app has said this should not leave the device. Believe it.
        if (flags and Notification.FLAG_LOCAL_ONLY != 0) return false
        return sbn.isClearable
    }

    private fun appLabel(packageName: String, pm: PackageManager?): String {
        if (pm == null) return packageName
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun Bundle.charSequence(key: String): String? =
        getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    /**
     * `Notification.MessagingStyle.Message`'s own key for the sender's name.
     * Not exposed as a constant by the framework, which is why it is spelled
     * out here rather than referenced.
     */
    private const val KEY_MESSAGE_SENDER = "sender"
}
