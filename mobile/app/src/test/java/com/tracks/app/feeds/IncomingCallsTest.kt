// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Recognising a ringing call from the phone app's own notification. Calls
 * never reached the watch: the relay drops ongoing notifications, and a
 * ringing call is one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class IncomingCallsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun pi(name: String) = PendingIntent.getBroadcast(context, name.hashCode(), Intent(name), PendingIntent.FLAG_IMMUTABLE)
    private val caller = Person.Builder().setName("Alex Rivera").setUri("tel:+15095550123").build()

    private fun sbn(n: Notification) =
        StatusBarNotification("com.google.android.dialer", "com.google.android.dialer", 1, "call", Process.myUid(),
                              0, 0, n, Process.myUserHandle(), System.currentTimeMillis())

    private fun base() = Notification.Builder(context, "calls")
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setCategory(Notification.CATEGORY_CALL)
        .setOngoing(true)

    @Test
    fun a_ringing_call_is_recognised_with_its_caller_number_and_buttons() {
        val n = base().setStyle(Notification.CallStyle.forIncomingCall(caller, pi("decline"), pi("answer"))).build()
        val call = IncomingCalls.ringing(sbn(n))
        assertNotNull(call)
        assertEquals("Alex Rivera", call!!.caller)
        assertEquals("+15095550123", call.number)
        assertNotNull(call.answer)
        assertNotNull(call.decline)
    }

    @Test
    fun a_call_already_answered_is_not_ringing() {
        val n = base().setStyle(Notification.CallStyle.forOngoingCall(caller, pi("hangup"))).build()
        assertNull("the watch stops ringing once it is answered", IncomingCalls.ringing(sbn(n)))
    }

    @Test
    fun an_ordinary_notification_is_not_a_call() {
        val n = Notification.Builder(context, "msgs").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Hi").setCategory(Notification.CATEGORY_MESSAGE).build()
        assertNull(IncomingCalls.ringing(sbn(n)))
    }

    @Test
    fun an_older_phone_apps_call_is_found_by_its_full_screen_alert_and_buttons() {
        val n = base().setContentTitle("Sam")
            .setFullScreenIntent(pi("screen"), true)
            .addAction(Notification.Action.Builder(null, "Decline", pi("d")).build())
            .addAction(Notification.Action.Builder(null, "Answer", pi("a")).build())
            .build()
        val call = IncomingCalls.ringing(sbn(n))
        assertEquals("Sam", call?.caller)
        assertEquals(pi("a"), call?.answer)
        assertEquals(pi("d"), call?.decline)
    }
}
