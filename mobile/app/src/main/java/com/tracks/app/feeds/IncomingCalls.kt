// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.tracks.device.DeviceNotification
import com.tracks.device.NotificationCategory

/**
 * Ringing phone calls, sent to the watch the moment the phone starts ringing.
 *
 * ## Two sources, one call
 *
 * **Telephony** says a call is ringing first, and says who: the
 * `PHONE_STATE` broadcast arrives as the ringing starts, with the number when
 * READ_CALL_LOG is granted. The name is then looked up in Contacts.
 *
 * **The phone app's call notification** arrives later — about two seconds
 * later on GrapheneOS's dialer, which is the delay the watch used to have,
 * because this used to wait for it. It is still read, for two things only
 * it has: the phone app's own Answer and Decline buttons, and a name the
 * phone app resolved itself (a business caller ID, say) when Contacts had
 * none. It is also the whole source when the telephony permissions are not
 * granted, so calls still ring on the watch without them, just later.
 *
 * Neither can be relied on for the number alone. Telephony withholds it
 * without READ_CALL_LOG, and phone apps differ in what their notification
 * carries: some attach a `tel:` person, GrapheneOS's puts the number in the
 * title and nothing else. That title is recognised as a number
 * ([numberIn]) rather than shown as a name, which is what left the watch
 * with no one to text a reply to.
 *
 * ## Permissions
 *
 * All asked for together, from one switch in Settings, and all optional
 * ([PERMISSIONS]): READ_PHONE_STATE and READ_CALL_LOG for the ring and the
 * number, READ_CONTACTS for the name, ANSWER_PHONE_CALLS so the watch can
 * answer or decline before the phone app's notification has arrived, and
 * SEND_SMS for the reply. The number and name go to the watch and nowhere
 * else; neither is logged nor stored past the call.
 *
 * A reply from the watch is a decline plus a text to the caller, as the
 * phone's own "respond with a message" does.
 */
object IncomingCalls {

    /** Everything the call features use, asked for together. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.SEND_SMS,
    )

    fun allGranted(context: Context): Boolean = PERMISSIONS.all { granted(context, it) }

    /** What the phone app's call notification says about a ringing call. */
    class Ringing(
        /** The caller's name as the phone app shows it; null when it shows only a number. */
        val name: String?,
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
        val shown = person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val shownNumber = shown?.let(::numberIn)
        val number = telOf(person?.uri)
            ?: peopleOf(extras)?.firstNotNullOfOrNull { telOf(it.uri) }
            ?: shownNumber

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
            // A number in the name slot is not a name: the watch is better
            // served by Contacts' name for it, or by the number shown as one.
            name = shown?.takeIf { shownNumber == null },
            number = number,
            // Phone apps put Decline first and Answer last; matching by word
            // first, position as the fallback.
            answer = answer ?: byWords("answer", "accept") ?: actions.lastOrNull()?.actionIntent,
            decline = decline ?: byWords("decline", "reject", "dismiss") ?: actions.firstOrNull()?.actionIntent,
        )
    }

    /**
     * The phone number [text] is, or null if it is anything else — a name,
     * "Unknown", "Private number". Separators are dropped, as a dialer
     * formats them in ("+1 509-555-0123", "(509) 555 0123").
     */
    fun numberIn(text: String): String? {
        val bare = text.trim().replace(SEPARATORS, "")
        return bare.takeIf { NUMBER.matches(it) }
    }

    private val SEPARATORS = Regex("[\\s\\-().\\u00A0\\u2010-\\u2015]")
    private val NUMBER = Regex("\\+?\\d{3,}")

    @Suppress("DEPRECATION")
    private fun peopleOf(extras: android.os.Bundle): List<Person>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
        }

    private fun telOf(uri: String?): String? =
        uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")?.let { Uri.decode(it) }
            ?.takeIf { it.isNotBlank() }

    // ── The call ringing now ─────────────────────────────────────────────────

    /**
     * The call ringing on the watch. [id] is what the watch was told and
     * what its answers name, so it stays fixed for the call's life, whichever
     * source arrives second.
     */
    private class Call(
        val id: Int,
        val startedAt: Long,
        var number: String?,
        /** Contacts' name for [number], looked up once per number. */
        var contactName: String?,
        /** The phone app's own name for the caller, when it shows one. */
        var shownName: String?,
        var answer: PendingIntent? = null,
        var decline: PendingIntent? = null,
        /** The phone app's call notification, once it has posted. */
        var notificationId: Int? = null,
        var packageName: String,
        var appName: String,
    ) {
        /** Contacts first: it is the name the user gave this person. */
        val caller: String get() = contactName ?: shownName ?: number ?: "Incoming call"
    }

    private val lock = Any()
    private var current: Call? = null

    /** The watch id of a call telephony announced; only one call rings at a time. */
    private const val TELEPHONY_ID = 0x7CA11

    /**
     * Listen for the phone ringing until the returned function is called.
     * For the watch link, which only wants calls while a watch is connected.
     */
    fun watch(context: Context): () -> Unit {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                @Suppress("DEPRECATION") // still delivered, to holders of READ_CALL_LOG
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
                onPhoneState(app, state, number)
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        return {
            runCatching { app.unregisterReceiver(receiver) }
            synchronized(lock) { current = null }
        }
    }

    private fun onPhoneState(context: Context, state: String?, number: String?) {
        if (state != TelephonyManager.EXTRA_STATE_RINGING) {
            // Answered (OFFHOOK) or over (IDLE), on the phone or anywhere else.
            end()
            return
        }
        // Since Android 10 the ring is broadcast twice: without the number to
        // READ_PHONE_STATE holders, and with it to those that also hold
        // READ_CALL_LOG. With both, ringing on the first would send the watch
        // a nameless call and then correct it a moment later; the second is
        // close behind, so it is the one acted on.
        if (number == null && granted(context, Manifest.permission.READ_CALL_LOG)) return

        val dialer = defaultDialer(context)
        if (!NotificationRelayPreferences.policy(context).allows(dialer ?: "", null)) return

        val send = synchronized(lock) {
            val call = current
            if (call == null) {
                current = Call(
                    id = TELEPHONY_ID,
                    startedAt = SystemClock.elapsedRealtime(),
                    number = number,
                    contactName = number?.let { contactName(context, it) },
                    shownName = null,
                    packageName = dialer ?: "android",
                    appName = dialer?.let { appLabel(context, it) } ?: "Phone",
                )
                Log.d(TAG, "call ringing, from telephony")
                current
            } else if (number != null && call.number == null) {
                // The call came from the notification first, without a number.
                call.number = number
                call.contactName = contactName(context, number)
                call
            } else null
        }
        send?.let(::deliver)
    }

    /**
     * The phone app's call notification, ringing. Starts the call on the watch
     * when telephony has not, and otherwise only adds what it alone knows.
     */
    fun onNotification(context: Context, notificationId: Int, packageName: String, appName: String, ringing: Ringing) {
        val send = synchronized(lock) {
            val call = current
            if (call == null) {
                current = Call(
                    id = notificationId,
                    startedAt = SystemClock.elapsedRealtime(),
                    number = ringing.number,
                    contactName = ringing.number?.let { contactName(context, it) },
                    shownName = ringing.name,
                    answer = ringing.answer,
                    decline = ringing.decline,
                    notificationId = notificationId,
                    packageName = packageName,
                    appName = appName,
                )
                Log.d(TAG, "call ringing, from the phone app's notification")
                current
            } else {
                if (call.notificationId == null) {
                    // How long the phone app took: the delay the watch would
                    // have had without telephony. Timing only, never who.
                    Log.d(TAG, "call notification ${SystemClock.elapsedRealtime() - call.startedAt} ms after the ring")
                }
                val before = call.caller to call.number
                call.notificationId = notificationId
                call.answer = ringing.answer ?: call.answer
                call.decline = ringing.decline ?: call.decline
                call.shownName = ringing.name ?: call.shownName
                call.packageName = packageName
                call.appName = appName
                if (call.number == null && ringing.number != null) {
                    call.number = ringing.number
                    call.contactName = contactName(context, ringing.number)
                }
                call.takeIf { (it.caller to it.number) != before }
            }
        }
        send?.let(::deliver)
    }

    /**
     * The notification with [notificationId] is no longer a ringing call —
     * answered, declined or gone. Returns whether it was the call.
     */
    fun onNotificationGone(notificationId: Int): Boolean {
        val ringing = synchronized(lock) { current?.notificationId == notificationId }
        if (ringing) end()
        return ringing
    }

    /** The phone stopped ringing: stop the watch too. */
    private fun end() {
        val call = synchronized(lock) { current.also { current = null } } ?: return
        TracksNotificationListener.sink?.onDismissed(call.id)
    }

    private fun deliver(call: Call) {
        // Straight to the watch or nowhere: a call that rang while no watch
        // was connected is over or answered by the time one is.
        TracksNotificationListener.sink?.onNotification(DeviceNotification(
            id = call.id,
            appName = call.appName,
            packageName = call.packageName,
            title = call.caller,
            body = call.caller,
            category = NotificationCategory.CALL,
            timestamp = System.currentTimeMillis(),
            sender = call.caller,
            ringingCall = true,
            phoneNumber = call.number,
        ))
    }

    // ── Answers from the watch ───────────────────────────────────────────────

    /** Answer the call the watch knows as [id]. False when it is no longer ringing. */
    fun answer(context: Context, id: Int): Boolean {
        val call = synchronized(lock) { current?.takeIf { it.id == id } } ?: return false
        return press(context, call.answer) || telecom(context) { it.acceptRingingCall(); true }
    }

    /**
     * Decline the call the watch knows as [id], and text the caller [text] if
     * the watch sent one. A text that could not go is said on the phone, with
     * why; the call is declined regardless.
     */
    fun decline(context: Context, id: Int, text: String?): Boolean {
        val call = synchronized(lock) { current?.takeIf { it.id == id } } ?: return false
        @Suppress("DEPRECATION") // endCall: still the one way for a non-dialer app
        val declined = press(context, call.decline) || telecom(context) { it.endCall() }
        if (text != null && !textReply(context, call.number, text)) {
            val why = when {
                call.number == null && !granted(context, Manifest.permission.READ_CALL_LOG) ->
                    "the phone app did not share the caller's number — allow Tracks the call permissions in Settings › Notifications on the watch"
                call.number == null -> "the caller's number is hidden"
                !granted(context, Manifest.permission.SEND_SMS) ->
                    "texting callers is off — turn it on in Settings › Notifications on the watch"
                else -> "the text could not be sent"
            }
            (context.applicationContext as? com.tracks.app.TracksApplication)?.container?.status
                ?.post("Could not text ${call.caller}: $why")
        }
        return declined
    }

    /**
     * Text the caller the watch's reply. False — and nothing sent — without the
     * permission or a number.
     */
    fun textReply(context: Context, number: String?, text: String): Boolean {
        if (number.isNullOrBlank()) return false
        if (!granted(context, Manifest.permission.SEND_SMS)) return false
        return runCatching {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
            true
        }.getOrElse { e ->
            Log.w(TAG, "could not send the reply text", e)
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

    /**
     * The system's own answer and hang-up, for a call whose phone app has not
     * posted its notification yet — the first second or two of ringing, which
     * is now when the watch rings.
     */
    @SuppressLint("MissingPermission") // checked here
    private inline fun telecom(context: Context, act: (TelecomManager) -> Boolean): Boolean {
        if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return false
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return runCatching { act(telecom) }.getOrElse { e ->
            Log.w(TAG, "the system refused to answer or end the call", e)
            false
        }
    }

    /** The caller's name in the user's contacts, or null. */
    private fun contactName(context: Context, number: String): String? {
        if (!granted(context, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun defaultDialer(context: Context): String? =
        runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    private fun appLabel(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private const val TAG = "TracksCalls"
}
