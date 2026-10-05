// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationResponse
import com.tracks.device.feeds.NotificationCapture

/**
 * Relays phone notifications to the watch.
 *
 * ## The permission
 *
 * `BIND_NOTIFICATION_LISTENER_SERVICE` is the broadest thing this app asks
 * for. Granting it means every notification the user receives — messages,
 * two-factor codes, bank alerts — passes through this process. It cannot be
 * scoped to particular apps, and the system dialog that grants it is one most
 * people click through.
 *
 * Three things follow from taking that seriously:
 *
 * 1. It is **optional**. The app works without it; the user turns it on if
 *    they want notifications on their wrist, and nothing else degrades.
 * 2. Filtering happens **immediately**, in [NotificationCapture], and keeps
 *    only what a watch can display — title, body, app, category.
 * 3. Nothing is **stored**. A notification is converted, handed onward, and
 *    forgotten. It never reaches the database, the server, or a log at any
 *    level a user could later export. The debug line below deliberately logs
 *    the app name and never the content.
 *
 * And on top of the filtering, the user's own rules: the blacklist and the
 * silent-notification switch in Settings, read here per notification so a
 * change takes effect immediately. See [NotificationRelayPreferences].
 *
 * ## Acting from the watch
 *
 * The watch can clear a notification, mute its app, reply, or press one of
 * the app's own buttons — see [respond]. For that this keeps, per relayed
 * notification, its system key and the app's action objects: handles to
 * perform things the notification already offered, not its content, and in
 * memory only, bounded like the buffers below. The watch names an action by
 * index; it cannot reach anything the notification did not carry.
 */
class TracksNotificationListener : NotificationListenerService() {

    /**
     * Where notifications go while a watch is connected.
     *
     * A plain static hand-off rather than a bound service or a broadcast,
     * because the system owns this service's lifecycle and gives us no way to
     * reach the rest of the app from inside it.
     */
    interface Sink {
        fun onNotification(notification: DeviceNotification)
        fun onDismissed(id: Int)
    }

    /**
     * The two-argument form, because the ranking is the argument that matters.
     *
     * Android calls this one and its default implementation forwards to the
     * one-argument version, which is what this used to override — and that
     * version cannot answer the only question the silent-notification setting
     * asks. See [importanceOf].
     */
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        // Recorded before any filtering, including the user's own: the app list
        // in Settings is built partly from this, and an app with no launcher
        // icon would vanish from that list the moment it was blocked if this
        // came after the policy check.
        if (sbn.packageName != packageName) rememberSource(sbn.packageName)

        val notification = NotificationCapture.from(
            sbn,
            packageManager,
            packageName,
            policy = NotificationRelayPreferences.policy(this),
            importance = importanceOf(sbn, rankingMap),
        ) ?: return

        rememberLive(notification.id, sbn)

        // App name only. The body is the user's private correspondence and has
        // no business in logcat, which any app with READ_LOGS or an attached
        // cable can read.
        Log.d(TAG, "notification from ${notification.appName} (${notification.category})")

        // Delivered straight through when a watch is listening, buffered when
        // one is not. Buffering unconditionally would put a second or two of
        // latency on every notification, which is exactly the thing a watch
        // relay is for.
        val sink = sink
        if (sink != null) {
            sink.onNotification(notification)
            return
        }

        pending += notification
        // Bounded so a burst — a group chat waking up, a sync flood — cannot
        // grow this without limit while no watch is connected to drain it.
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // The same derivation the posting side uses — see NotificationCapture.
        // `sbn.id` here against a key-derived id there would mean every
        // dismissal missed, leaving the watch's list to fill up with entries
        // the user had already cleared on the phone.
        val id = NotificationCapture.idOf(sbn)
        synchronized(live) { live.remove(id) }

        val sink = sink
        if (sink != null) {
            sink.onDismissed(id)
            return
        }

        dismissed += id
        while (dismissed.size > MAX_PENDING) dismissed.removeFirst()
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    /**
     * The same action list the watch was sent, in the same order — the index
     * the watch answers with is only meaningful against exactly this list.
     */
    private fun rememberLive(id: Int, sbn: StatusBarNotification) = synchronized(live) {
        val actions = NotificationCapture.actionsOf(sbn.notification).map { it.second }
        live.remove(id)
        live[id] = Live(sbn.key, sbn.packageName, actions)
        while (live.size > MAX_PENDING) live.remove(live.keys.first())
    }

    /**
     * How loudly Android itself intends to deliver this notification.
     *
     * This is where "silent" is written down. Long-pressing a notification and
     * choosing Silent drops its channel — or, since Android 12, that single
     * conversation — to `IMPORTANCE_LOW`, and none of that appears on the
     * notification itself: the flags say nothing about it, and another app's
     * channels cannot be read without being its companion app. The ranking is
     * a listener's supported view of the answer, and it already accounts for
     * every per-conversation adjustment the user has made.
     *
     * Falls back to the deprecated priority field, which is the same statement
     * in the older vocabulary and is all that apps targeting pre-channel
     * behaviour set. Null when neither has an opinion — [RelayPolicy] relays
     * those rather than guessing.
     */
    @Suppress("DEPRECATION") // Notification.priority, deliberately — see above.
    private fun importanceOf(sbn: StatusBarNotification, rankingMap: RankingMap?): Int? {
        val map = rankingMap ?: currentRanking
        val ranking = Ranking()
        if (map != null && map.getRanking(sbn.key, ranking)) return ranking.importance

        val priority = sbn.notification?.priority ?: return null
        return if (priority < Notification.PRIORITY_DEFAULT) {
            NotificationManager.IMPORTANCE_LOW
        } else {
            null
        }
    }

    /** What it takes to act on one relayed notification. See the class note. */
    private class Live(val key: String, val packageName: String, val actions: List<Notification.Action>)

    companion object {
        private const val TAG = "TracksNotify"
        private const val MAX_PENDING = 50

        /** Relayed notifications by watch id, oldest first. Guarded by itself. */
        private val live = LinkedHashMap<Int, Live>()

        /** The running listener, for [cancelNotification]; null while unbound. */
        @Volatile
        private var instance: TracksNotificationListener? = null

        /**
         * Do what the user chose on the watch. Returns false when there is
         * nothing to do it to — the notification has gone, or the phone was
         * restarted since it was relayed — which is not an error: the watch
         * acted on something that no longer exists.
         *
         * Never logs the reply. It is the user's message, and the same rule
         * as the class note applies to what they send as to what they receive.
         */
        fun respond(context: Context, response: NotificationResponse): Boolean {
            val target = synchronized(live) { live[response.notificationId] } ?: return false
            Log.d(TAG, "watch response: ${response::class.simpleName}")
            return when (response) {
                is NotificationResponse.Dismiss -> {
                    val listener = instance ?: return false
                    listener.cancelNotification(target.key)
                    true
                }
                // Into the same blacklist Settings edits, so it is undone there
                // and nowhere else needs to know a watch can write it.
                is NotificationResponse.MuteApp -> {
                    NotificationRelayPreferences.setBlocked(context, target.packageName, true)
                    true
                }
                is NotificationResponse.Reply -> {
                    val action = target.actions.firstOrNull { a ->
                        a.remoteInputs.orEmpty().any { it.allowFreeFormInput }
                    } ?: return false
                    send(context, action) { replyIntent(action, response.text) }
                }
                is NotificationResponse.Action -> {
                    val action = target.actions.getOrNull(response.index) ?: return false
                    send(context, action) { null }
                }
            }
        }

        /**
         * The reply, filled into every free-form input the action declares,
         * exactly as the system's own inline-reply field fills it — which is
         * what the posting app reads it back with.
         */
        private fun replyIntent(action: Notification.Action, text: String): Intent {
            val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }.toTypedArray()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            return intent
        }

        /**
         * Fire the app's own PendingIntent. A cancelled one — the app has
         * since replaced its notification — is a no longer existing button,
         * not a failure.
         */
        private inline fun send(context: Context, action: Notification.Action, fill: () -> Intent?): Boolean =
            try {
                action.actionIntent.send(context, 0, fill())
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            }

        /** Enough to cover the apps that notify a person in a day. */
        private const val MAX_SEEN = 200

        /**
         * Notifications waiting for a watch, in memory only.
         *
         * The device layer drains these when it connects. Persisting them
         * would mean writing the user's messages to disk to solve a problem
         * that lasts seconds.
         */
        val pending = ArrayDeque<DeviceNotification>()
        val dismissed = ArrayDeque<Int>()

        /**
         * Packages that have posted while the relay was running.
         *
         * Only so that Settings can offer them in the blacklist: an app with no
         * launcher icon — a carrier service, a system component — is invisible
         * to the package query the app list is built from, and would otherwise
         * be the one thing a user cannot block.
         *
         * Package names only, in memory only, bounded, and never written
         * anywhere. That is a deliberate line: which apps notify you is itself
         * worth protecting, and a list of them on disk would be a small profile
         * of your life that this app has no reason to keep. It empties when the
         * process does, and the launcher-based list covers the rest.
         */
        private val seen = LinkedHashSet<String>()

        val seenPackages: Set<String> get() = synchronized(seen) { seen.toSet() }

        /**
         * Note an app as a notification source, most recent last.
         *
         * Synchronized because this is written on the notification service's
         * own thread and read from the UI when the blacklist opens.
         */
        private fun rememberSource(packageName: String) = synchronized(seen) {
            seen.remove(packageName)
            seen += packageName
            while (seen.size > MAX_SEEN) seen.remove(seen.first())
        }

        /**
         * Volatile because it is written from the main thread and read on the
         * notification-service thread.
         */
        @Volatile
        var sink: Sink? = null

        /**
         * Hand over everything that arrived while nothing was listening, and
         * clear the buffers.
         *
         * Draining is destructive on purpose: these are notifications the user
         * has probably already seen on the phone, and replaying them a second
         * time on the next connect would make reconnecting feel like a
         * notification storm.
         */
        fun drain(into: Sink) {
            while (pending.isNotEmpty()) into.onNotification(pending.removeFirst())
            while (dismissed.isNotEmpty()) into.onDismissed(dismissed.removeFirst())
        }

        /**
         * Whether the user has granted notification access.
         *
         * Read from the system setting rather than tracked ourselves, because
         * the user can revoke it in Settings at any time without telling us.
         */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners",
            ) ?: return false
            val us = ComponentName(context, TracksNotificationListener::class.java)
            return flat.split(":").any {
                ComponentName.unflattenFromString(it) == us
            }
        }

        /** The system screen where access is granted. There is no runtime prompt. */
        fun settingsAction(): String = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    }
}
