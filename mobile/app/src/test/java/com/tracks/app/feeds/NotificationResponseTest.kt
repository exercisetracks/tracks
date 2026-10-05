// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.tracks.device.NotificationResponse
import com.tracks.device.feeds.NotificationCapture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The watch's answer, carried out on the phone the way the posting app expects
 * to receive it. What the app gets is the observable: a reply has to arrive in
 * the app's own RemoteInput key, or the app sees an empty send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationResponseTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val listener = Robolectric.buildService(TracksNotificationListener::class.java).create().get()

    @AfterTest
    fun reset() {
        NotificationRelayPreferences.setBlocked(app, emptySet())
    }

    private fun broadcast(name: String) = PendingIntent.getBroadcast(
        app, name.hashCode(), Intent(name), PendingIntent.FLAG_MUTABLE,
    )

    /** A chat notification with Reply and Mark as read, posted through the listener. */
    private fun post(pkg: String = "com.example.chat", id: Int = 1): Int {
        val reply = Notification.Action.Builder(null, "Reply", broadcast("reply"))
            .addRemoteInput(RemoteInput.Builder("message_text").setLabel("Reply").build())
            .build()
        val read = Notification.Action.Builder(null, "Mark as read", broadcast("read")).build()
        val n = Notification.Builder(app, "channel")
            .setContentTitle("Ada")
            .setContentText("are you out?")
            .addAction(reply)
            .addAction(read)
            .build()
        val sbn = StatusBarNotification(
            pkg, pkg, id, null, 0, 0, 0, n, UserHandle.getUserHandleForUid(0), System.currentTimeMillis(),
        )
        listener.onNotificationPosted(sbn, null)
        return NotificationCapture.idOf(sbn)
    }

    private fun sent(): List<Intent> = shadowOf(app).broadcastIntents

    @Test
    fun `a reply from the watch arrives in the app's own reply field`() {
        val id = post()
        assertTrue(TracksNotificationListener.respond(app, NotificationResponse.Reply(id, "On my way")))

        val delivered = sent().single { it.action == "reply" }
        assertEquals("On my way", RemoteInput.getResultsFromIntent(delivered)?.getCharSequence("message_text"))
    }

    @Test
    fun `a button pressed on the watch presses that button`() {
        val id = post()
        // Index 1 in the order the app gave: Reply, Mark as read.
        assertTrue(TracksNotificationListener.respond(app, NotificationResponse.Action(id, 1)))
        assertEquals(listOf("read"), sent().map { it.action })
    }

    @Test
    fun `muting from the watch blocks the app in the relay's own list`() {
        val id = post(pkg = "com.example.noisy")
        assertTrue(TracksNotificationListener.respond(app, NotificationResponse.MuteApp(id)))
        assertTrue("com.example.noisy" in NotificationRelayPreferences.blocked(app))
    }

    /**
     * The watch can act on a notification the phone has already dropped — the
     * user cleared it on the phone a second earlier. That must do nothing,
     * and in particular must not fire whatever now sits at that index.
     */
    @Test
    fun `a notification cleared on the phone can no longer be acted on`() {
        val id = post(id = 7)
        val sbn = StatusBarNotification(
            "com.example.chat", "com.example.chat", 7, null, 0, 0, 0,
            Notification.Builder(app, "channel").build(), UserHandle.getUserHandleForUid(0), 0,
        )
        listener.onNotificationRemoved(sbn)

        assertFalse(TracksNotificationListener.respond(app, NotificationResponse.Action(id, 1)))
        assertEquals(emptyList(), sent())
    }

    @Test
    fun `an index past the app's buttons does nothing`() {
        val id = post()
        assertFalse(TracksNotificationListener.respond(app, NotificationResponse.Action(id, 5)))
        assertEquals(emptyList(), sent())
    }
}
