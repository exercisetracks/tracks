// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.meds

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tracks.app.MainActivity
import com.tracks.app.R
import com.tracks.app.TracksApplication
import com.tracks.core.api.MedicationLog
import com.tracks.core.api.MedicationLogCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * A dose is due: say so, then arm the next one.
 *
 * The re-arming is the important half and it happens whether or not the
 * notification is allowed to appear — see [MedicationReminders] for why the
 * chain has to be self-sustaining rather than refreshed when the app opens.
 */
class MedicationAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val spec = MedicationReminders.decodeSpec(
            intent.getStringExtra(MedicationReminders.EXTRA_SPEC),
        ) ?: return
        val at = intent.getLongExtra(MedicationReminders.EXTRA_AT, System.currentTimeMillis())
        val due = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()

        // From the moment it was due rather than from now, so a phone that woke
        // late does not skip a same-day repeat. Armed first, so nothing below
        // can break the chain.
        MedicationReminders.arm(context, spec, due)

        // Quiet for a dose already recorded — taken early, or ticked off on the
        // web. Fails open: if the log cannot be read in time, the reminder is
        // shown, because a reminder too many is a nuisance and one too few is
        // a missed dose.
        val app = context.applicationContext as? TracksApplication
        if (app == null) { notify(context, spec, at); return }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val logged = withTimeoutOrNull(LOG_READ_TIMEOUT_MS) {
                    val log = app.container.sources.list("medication_log", MedicationLog.serializer())
                    MedicationReminders.alreadyLogged(spec.scheduleId, due.toLocalDate(), log)
                } ?: false
                if (!logged) notify(context, spec, at)
            } catch (_: Exception) {
                notify(context, spec, at)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, spec: MedicationReminders.Spec, at: Long) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        MedicationReminders.ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, MedicationReminders.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.medication_due_title, spec.name))
            .setContentText(spec.dose ?: context.getString(R.string.medication_due_text))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Logged from the shade, because the whole point of the reminder is
            // that you are holding the tablet and not the phone. Opening the app
            // to tick a box is the step people skip, and a dose recorded nowhere
            // is a dose that gets taken twice.
            .addAction(
                0,
                context.getString(R.string.medication_action_taken),
                actionIntent(context, spec, at, MedicationLogCreate.STATUS_TAKEN),
            )
            .addAction(
                0,
                context.getString(R.string.medication_action_skip),
                actionIntent(context, spec, at, MedicationLogCreate.STATUS_SKIPPED),
            )
            .build()

        runCatching {
            manager.notify(MedicationReminders.notificationId(spec.scheduleId), notification)
        }
    }

    private fun actionIntent(
        context: Context,
        spec: MedicationReminders.Spec,
        at: Long,
        status: String,
    ): PendingIntent {
        val intent = Intent(context, MedicationActionReceiver::class.java)
            .setData(Uri.parse("tracks://medication/${spec.scheduleId}/$status/$at"))
            .putExtra(MedicationReminders.EXTRA_SPEC, MedicationReminders.encodeSpec(spec))
            .putExtra(MedicationReminders.EXTRA_AT, at)
            .putExtra(MedicationReminders.EXTRA_STATUS, status)
        return PendingIntent.getBroadcast(
            context,
            spec.scheduleId * 2 + if (status == MedicationLogCreate.STATUS_TAKEN) 0 else 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * "Taken" or "Skip", from the notification.
 *
 * Goes through the outbox like every other write on this screen, so a dose
 * ticked off with no signal is recorded rather than refused. The local mirror is
 * updated at the same time — without that, the Health screen would still be
 * offering a dose the user has already told the phone they took, and offering a
 * second dose is a real-world harm rather than a stale-UI annoyance.
 */
class MedicationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val spec = MedicationReminders.decodeSpec(
            intent.getStringExtra(MedicationReminders.EXTRA_SPEC),
        ) ?: return
        val status = intent.getStringExtra(MedicationReminders.EXTRA_STATUS)
            ?: MedicationLogCreate.STATUS_TAKEN
        val at = intent.getLongExtra(MedicationReminders.EXTRA_AT, System.currentTimeMillis())

        NotificationManagerCompat.from(context)
            .cancel(MedicationReminders.notificationId(spec.scheduleId))

        val app = context.applicationContext as? TracksApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.container
                val scheduledFor = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(at),
                    ZoneId.systemDefault(),
                ).toString()
                val entry = MedicationLogCreate(
                    medicationId = spec.medicationId,
                    scheduleId = spec.scheduleId,
                    status = status,
                    scheduledFor = scheduledFor,
                )
                // A log entry in the replica, like a dose ticked on the Health
                // page: recorded here and now, synced when there is a server.
                container.sources.create(
                    "medication_log", entry, MedicationLogCreate.serializer(),
                    extra = mapOf("logged_at" to OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()),
                )
            } finally {
                pending.finish()
            }
        }
    }

}

/**
 * A reboot clears the alarm table; the reminder list survives it.
 *
 * The one event that can silently end every reminder, and the only reason this
 * app asks for `RECEIVE_BOOT_COMPLETED` at all.
 */
class MedicationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        MedicationReminders.rearmAll(context)
    }
}

/** Well inside a broadcast's ten seconds, so the system never kills the receiver first. */
private const val LOG_READ_TIMEOUT_MS = 4_000L
