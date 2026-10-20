// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.Manifest
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.tracks.device.DeviceNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Recognising a ringing call — from the phone app's own notification, and
 * from telephony ahead of it. Calls never reached the watch at first: the
 * relay drops ongoing notifications, and a ringing call is one. Then they
 * reached it two seconds late, as a bare number, with no number to text a
 * reply to.
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
        assertEquals("Alex Rivera", call!!.name)
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
        assertEquals("Sam", call?.name)
        assertEquals(pi("a"), call?.answer)
        assertEquals(pi("d"), call?.decline)
    }

    @Test
    fun a_number_in_the_title_is_the_callers_number_and_not_their_name() {
        // GrapheneOS's dialer: the number as the call's person, with no tel: uri.
        val unnamed = Person.Builder().setName("+1 509-555-0123").build()
        val n = base().setStyle(Notification.CallStyle.forIncomingCall(unnamed, pi("decline"), pi("answer"))).build()
        val call = IncomingCalls.ringing(sbn(n))
        assertNull("a number is not a name; Contacts or the number itself is shown instead", call?.name)
        assertEquals("+15095550123", call?.number)
    }

    @Test
    fun names_and_withheld_callers_are_not_numbers() {
        assertNull(IncomingCalls.numberIn("Alex Rivera"))
        assertNull(IncomingCalls.numberIn("Private number"))
        assertNull(IncomingCalls.numberIn("Unknown"))
        assertEquals("5095550123", IncomingCalls.numberIn("(509) 555.0123"))
    }

    // ── The call ringing now ─────────────────────────────────────────────────

    private val sent = mutableListOf<DeviceNotification>()
    private val withdrawn = mutableListOf<Int>()
    private val stopWatching = run {
        TracksNotificationListener.sink = object : TracksNotificationListener.Sink {
            override fun onNotification(notification: DeviceNotification) { sent += notification }
            override fun onDismissed(id: Int) { withdrawn += id }
        }
        IncomingCalls.watch(context)
    }

    @After
    fun stop() {
        stopWatching()
        TracksNotificationListener.sink = null
    }

    private fun grant(vararg permissions: String) = shadowOf(context).grantPermissions(*permissions)

    @Suppress("DEPRECATION")
    private fun phone(state: String, number: String? = null) {
        context.sendBroadcast(Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            .putExtra(TelephonyManager.EXTRA_STATE, state)
            .apply { number?.let { putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, it) } })
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun ringingFrom(n: Notification) = IncomingCalls.ringing(sbn(n))!!

    @Test
    fun the_watch_rings_from_telephony_without_waiting_for_the_phone_apps_notification() {
        grant(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
        phone(TelephonyManager.EXTRA_STATE_RINGING, "+15095550123")
        assertEquals(1, sent.size)
        assertTrue(sent[0].ringingCall)
        assertEquals("+15095550123", sent[0].phoneNumber)

        // The notification, two seconds on, adds its buttons and rings nothing new.
        val n = base().setStyle(Notification.CallStyle.forIncomingCall(caller, pi("decline"), pi("answer"))).build()
        IncomingCalls.onNotification(context, 99, "com.android.dialer", "Phone", ringingFrom(n).let {
            IncomingCalls.Ringing(null, it.number, it.answer, it.decline)
        })
        assertEquals("nothing the watch shows changed", 1, sent.size)
        assertTrue("its decline button is the one pressed", IncomingCalls.decline(context, sent[0].id, null))
    }

    @Test
    fun the_numberless_ring_waits_for_the_one_that_carries_the_number() {
        grant(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
        phone(TelephonyManager.EXTRA_STATE_RINGING)
        assertEquals("a nameless ring would be corrected a moment later", 0, sent.size)
        phone(TelephonyManager.EXTRA_STATE_RINGING, "+15095550123")
        assertEquals(1, sent.size)
    }

    @Test
    fun the_watch_stops_ringing_when_the_phone_does() {
        grant(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
        phone(TelephonyManager.EXTRA_STATE_RINGING, "+15095550123")
        phone(TelephonyManager.EXTRA_STATE_OFFHOOK)
        assertEquals(listOf(sent[0].id), withdrawn)
    }

    @Test
    fun a_phone_app_that_shows_only_a_number_still_gives_a_reply_someone_to_text() {
        // No telephony permissions: the notification is the only source, and
        // its title the only place the number is.
        grant(Manifest.permission.SEND_SMS)
        val unnamed = Person.Builder().setName("+1 509-555-0123").build()
        val n = base().setStyle(Notification.CallStyle.forIncomingCall(unnamed, pi("decline"), pi("answer"))).build()
        IncomingCalls.onNotification(context, 7, "com.android.dialer", "Phone", ringingFrom(n))
        // What the reply is texted to: it was null here, so the watch's reply
        // declined the call and texted no one.
        assertEquals("+15095550123", sent.single().phoneNumber)
        assertTrue(IncomingCalls.decline(context, 7, "Out on a run, back soon"))
    }
}
