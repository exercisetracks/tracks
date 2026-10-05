// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import com.tracks.device.NotificationAction
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The identity a notification carries to the watch.
 *
 * Robolectric because `StatusBarNotification` computes its own `key` from the
 * package, id, tag and user — and that derivation is precisely what is under
 * test, so stubbing it out would test nothing.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationCaptureTest {

    private fun sbn(
        pkg: String,
        id: Int,
        tag: String? = null,
        notification: Notification = builder().build(),
    ): StatusBarNotification =
        StatusBarNotification(
            pkg,
            pkg,
            id,
            tag,
            /* uid = */ 0,
            /* initialPid = */ 0,
            /* score = */ 0,
            notification,
            UserHandle.getUserHandleForUid(0),
            System.currentTimeMillis(),
        )

    private fun builder() = Notification.Builder(
        org.robolectric.RuntimeEnvironment.getApplication(),
        "channel",
    )

    /**
     * A message notification shaped the way Signal, WhatsApp and every other
     * modern messenger shape theirs: `MessagingStyle`, with the phone's owner
     * as the style's user and the incoming message attributed to whoever sent
     * it.
     */
    private fun chat(
        from: String = "Ada",
        text: String = "on my way",
        contentTitle: String = from,
        conversation: String? = null,
    ): Notification {
        // The CharSequence overloads rather than the Person ones: the platform
        // wraps them in exactly the same Person and writes the same extras —
        // including `selfDisplayName`, which is what this file is here to prove
        // never reaches the watch — and they work on every API level this app
        // supports rather than only on 28 and up.
        @Suppress("DEPRECATION")
        val style = Notification.MessagingStyle("Alex")
            .addMessage(text, 1_760_000_000_000L, from)
        if (conversation != null) style.conversationTitle = conversation
        return builder()
            .setContentTitle(contentTitle)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .build()
    }

    private fun capture(
        sbn: StatusBarNotification,
        policy: RelayPolicy = RelayPolicy(),
        importance: Int? = null,
    ) = NotificationCapture.from(
        sbn,
        org.robolectric.RuntimeEnvironment.getApplication().packageManager,
        ownPackage = "com.tracks.app",
        policy = policy,
        importance = importance,
    )

    /**
     * The regression this derivation exists for.
     *
     * Two apps both posting their notification as id 1 is not a corner case —
     * it is the common case, because an app with one notification usually calls
     * it 1 or 0. With `sbn.id` the watch received the second as a *modification*
     * of the first, so one replaced the other on the wrist and dismissing
     * either dismissed both.
     */
    @Test
    fun `different apps sharing a notification id get different watch ids`() {
        assertNotEquals(
            NotificationCapture.idOf(sbn("com.example.mail", 1)),
            NotificationCapture.idOf(sbn("com.example.chat", 1)),
        )
    }

    /**
     * The other half: an app updating its notification in place — a progress
     * count, a new message in the same conversation — must keep the same id, or
     * the watch shows a fresh entry for every edit instead of updating the one
     * already there.
     */
    @Test
    fun `the same notification keeps its id across updates`() {
        assertEquals(
            NotificationCapture.idOf(sbn("com.example.chat", 7)),
            NotificationCapture.idOf(sbn("com.example.chat", 7)),
        )
    }

    /** Tags are how one app distinguishes its own notifications. */
    @Test
    fun `one app's tagged notifications are distinct`() {
        assertNotEquals(
            NotificationCapture.idOf(sbn("com.example.chat", 1, tag = "alice")),
            NotificationCapture.idOf(sbn("com.example.chat", 1, tag = "bob")),
        )
    }

    // ── Who the message is from ──────────────────────────────────────────────

    /**
     * The regression this file's second half exists for.
     *
     * The capture used to read `android.selfDisplayName`, which is the *phone
     * owner's* name in the conversation — always present in a `MessagingStyle`
     * notification, and never the person who wrote. For messages the Garmin
     * protocol draws the sender as the heading, so every Signal message arrived
     * on the wrist labelled with the user's own name.
     */
    @Test
    fun `a message is attributed to whoever sent it, not to the phone's owner`() {
        val captured = capture(sbn("org.thoughtcrime.securesms", 1, notification = chat()))

        assertEquals("Ada", captured?.sender)
    }

    /** A group is headed by the group. Which member spoke is already in the body. */
    @Test
    fun `a group chat is headed by the conversation`() {
        val captured = capture(
            sbn(
                "org.thoughtcrime.securesms",
                1,
                notification = chat(
                    from = "Ada",
                    text = "Ada: on my way",
                    contentTitle = "Book club",
                    conversation = "Book club",
                ),
            ),
        )

        assertEquals("Book club", captured?.sender)
    }

    /**
     * A parcel update has no sender, and saying so is the point: the device
     * layer falls back to the title, which is the only heading such a
     * notification has.
     */
    @Test
    fun `a notification that is not a conversation has no sender`() {
        val plain = builder().setContentTitle("Delivered").setContentText("Left at the door").build()

        assertNull(capture(sbn("com.example.parcels", 3, notification = plain))?.sender)
    }

    // ── The user's own rules ─────────────────────────────────────────────────

    @Test
    fun `a blacklisted app never leaves the phone`() {
        val notification = sbn("org.thoughtcrime.securesms", 1, notification = chat())

        assertNull(
            capture(
                notification,
                policy = RelayPolicy(blockedPackages = setOf("org.thoughtcrime.securesms")),
            ),
        )
        // And its neighbours are unaffected — a blacklist of one blocks one.
        assertEquals(
            "Ada",
            capture(
                sbn("com.example.chat", 1, notification = chat()),
                policy = RelayPolicy(blockedPackages = setOf("org.thoughtcrime.securesms")),
            )?.sender,
        )
    }

    @Test
    fun `silent notifications stay on the phone by default`() {
        val quiet = sbn("com.example.chat", 1, notification = chat())

        assertNull(capture(quiet, importance = NotificationManager.IMPORTANCE_LOW))
        assertNull(capture(quiet, importance = NotificationManager.IMPORTANCE_MIN))
    }

    @Test
    fun `silent notifications are relayed when the user asks for them`() {
        val captured = capture(
            sbn("com.example.chat", 1, notification = chat()),
            policy = RelayPolicy(relaySilent = true),
            importance = NotificationManager.IMPORTANCE_LOW,
        )

        assertEquals("Ada", captured?.sender)
    }

    /**
     * An importance we could not read is not evidence of silence. Dropping on
     * a failed ranking lookup would silently disable the relay on any phone
     * whose ranking we cannot see.
     */
    @Test
    fun `an unknown importance is relayed`() {
        assertEquals(
            "Ada",
            capture(sbn("com.example.chat", 1, notification = chat()), importance = null)?.sender,
        )
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()

    private fun broadcast(name: String) =
        PendingIntent.getBroadcast(app, name.hashCode(), Intent(name), PendingIntent.FLAG_IMMUTABLE)

    private fun action(label: String, input: RemoteInput? = null, intent: PendingIntent = broadcast(label)) =
        Notification.Action.Builder(null, label, intent).apply { input?.let { addRemoteInput(it) } }.build()

    private fun freeForm() = RemoteInput.Builder("reply").setLabel("Reply").build()

    private fun labels(n: Notification) = NotificationCapture.actionsOf(n).map { it.first }

    @Test
    fun `a messenger's reply and mark-as-read reach the watch in the app's order`() {
        val n = builder().setContentTitle("Ada")
            .addAction(action("Reply", freeForm()))
            .addAction(action("Mark as read"))
            .build()
        assertEquals(
            listOf(
                NotificationAction("Reply", NotificationAction.Kind.REPLY),
                NotificationAction("Mark as read", NotificationAction.Kind.SIMPLE),
            ),
            labels(n),
        )
        // And they are carried on the captured notification itself.
        assertEquals(2, capture(sbn("com.example.chat", 1, notification = n))?.actions?.size)
    }

    /**
     * The watch answers with its own quick-reply text. An input that accepts
     * only fixed choices would reject it, so offering it would be a reply
     * button that never sends.
     */
    @Test
    fun `a reply that only takes fixed choices is not offered`() {
        val choicesOnly = RemoteInput.Builder("pick")
            .setChoices(arrayOf("Yes", "No"))
            .setAllowFreeFormInput(false)
            .build()
        val n = builder().setContentTitle("Poll").addAction(action("Vote", choicesOnly)).build()
        assertEquals(emptyList(), labels(n))
    }

    @Test
    fun `actions the app made for watches win over its phone actions`() {
        val n = builder().setContentTitle("Ada")
            .addAction(action("Open chat"))
            .extend(Notification.WearableExtender().addAction(action("Reply", freeForm())))
            .build()
        assertEquals(listOf("Reply"), labels(n).map { it.label })
    }

    /** On 31 and up, the first release that lets a listener ask. */
    @Test
    @org.robolectric.annotation.Config(sdk = [34])
    fun `a button that would open a screen on the phone is not offered`() {
        val opens = PendingIntent.getActivity(app, 0, Intent("open"), PendingIntent.FLAG_IMMUTABLE)
        val n = builder().setContentTitle("Ada")
            .addAction(action("Open", intent = opens))
            .addAction(action("Archive"))
            .build()
        assertEquals(listOf("Archive"), labels(n).map { it.label })
    }
}
