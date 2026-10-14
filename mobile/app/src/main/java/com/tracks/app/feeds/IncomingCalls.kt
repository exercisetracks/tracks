// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.util.Log

/**
 * Ringing phone calls, read from the phone app's own call notification.
 *
 * ## Why from the notification
 *
 * The notification listener already sees it, and it carries everything the
 * watch needs — who is calling, and the phone app's own Answer and Decline
 * buttons. Reading the call state from the telephony stack instead would mean
 * asking for READ_PHONE_STATE (and READ_CALL_LOG for the number) on top of
 * notification access, and answering would need ANSWER_PHONE_CALLS. Pressing
 * the buttons the notification already offers needs nothing more.
 *
 * The relay drops ongoing notifications (see NotificationCapture), and a
 * ringing call is one — which is why calls never reached the watch.
 *
 * A reply from the watch is a decline plus a text to the caller, as the
 * phone's own "respond with a message" does. That alone needs a permission,
 * SEND_SMS, asked for only when the user turns it on in Settings.
 */
object IncomingCalls {

    /** A call ringing now, and the phone app's buttons for it. */
    class Ringing(
        val caller: String,
        val number: String?,
        val answer: PendingIntent?,
        val decline: PendingIntent?,
    )

    /** The ringing call this notification announces, or null if it is not one. */
    fun ringing(sbn: StatusBarNotification): Ringing? {
        val n = sbn.notification ?: return null
        if (n.category != Notification.CATEGORY_CALL) return null
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val type = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
            when (type) {
                Notification.CallStyle.CALL_TYPE_INCOMING -> Unit
                0 -> if (n.fullScreenIntent == null) return null   // not CallStyle: ringing shows full screen
                else -> return null                                 // ongoing or screening: not ringing
            }
        } else if (n.fullScreenIntent == null) {
            return null
        }

        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        } else null
        val caller = person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: "Incoming call"
        val number = telOf(person?.uri)
            ?: peopleOf(extras)?.firstNotNullOfOrNull { telOf(it.uri) }

        val actions = n.actions.orEmpty().toList()
        fun byWords(vararg words: String) = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { it in t }
        }?.actionIntent
        val answer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
        } else null
        val decline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
                ?: extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
        } else null
        return Ringing(
            caller = caller,
            number = number,
            // Phone apps put Decline first and Answer last; matching by word
            // first, position as the fallback.
            answer = answer ?: byWords("answer", "accept") ?: actions.lastOrNull()?.actionIntent,
            decline = decline ?: byWords("decline", "reject", "dismiss") ?: actions.firstOrNull()?.actionIntent,
        )
    }

    @Suppress("DEPRECATION")
    private fun peopleOf(extras: android.os.Bundle): List<Person>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
        }

    private fun telOf(uri: String?): String? =
        uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")?.let { android.net.Uri.decode(it) }
            ?.takeIf { it.isNotBlank() }

    /**
     * Text the caller the watch's reply. False — and nothing sent — without the
     * permission or a number: the call is still declined, and the phone says
     * why the text did not go.
     */
    fun textReply(context: Context, number: String?, text: String): Boolean {
        if (number.isNullOrBlank()) return false
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            true
        }.getOrElse { e ->
            Log.w("TracksCalls", "could not send the reply text", e)
            false
        }
    }

    fun press(context: Context, intent: PendingIntent?): Boolean =
        try {
            intent?.send(context, 0, null)
            intent != null
        } catch (e: PendingIntent.CanceledException) {
            false
        }
}
