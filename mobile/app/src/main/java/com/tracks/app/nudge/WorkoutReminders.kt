// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.nudge

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tracks.app.MainActivity
import com.tracks.app.R
import com.tracks.app.ui.components.startedLocal
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.local.LocalSources
import com.tracks.core.nudge.WorkoutNudge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The day's one workout reminder, at the time this person usually starts.
 *
 * The time is learned by [WorkoutNudge] from when their workouts have started
 * before, with a fallback they choose in Settings. This side does the Android
 * part: one alarm, the notification, and the rules about which days are quiet.
 *
 * ## One alarm, re-armed by itself
 *
 * The same chain as [com.tracks.app.meds.MedicationReminders], for the same
 * reason: the person a reminder matters most for is the one who has not opened
 * the app in a fortnight. Each alarm's last act is to arm the next day's, so the
 * chain needs no app launch to keep going. Unlike a dose, the time is
 * recomputed every day from fresh history rather than carried in the alarm's
 * extras — that is the point of it — so firing needs a read of the store.
 *
 * ## At most once a day
 *
 * [KEY_SPENT_ON] records the day whose reminder has gone off or been skipped,
 * and is written *before* posting. Re-arming on launch, after a reboot or after
 * a settings change can then never produce a second reminder for the same day,
 * whichever order those happen in.
 *
 * ## Inexact, deliberately
 *
 * `setAndAllowWhileIdle`, as for medications: a few minutes late is fine for a
 * nudge whose target is itself a smoothed estimate, and exact alarms are a
 * permission with store-policy weight that this does not need.
 *
 * ## What stays on the phone
 *
 * Preferences only hold the switch, the fallback time and when the next alarm
 * is — no history. The history is read from the encrypted store at the moment
 * it is needed and is never copied out of it.
 */
object WorkoutReminders {

    const val CHANNEL_ID = "workout_reminders"

    /** Clear of the backup reminder's 7401 and the medication range from 20000. */
    private const val NOTIFICATION_ID = 7_501
    private const val REQUEST_CODE = 7_501

    private const val PREFS = "workout_reminders"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FALLBACK = "fallback"
    private const val KEY_SPENT_ON = "spent_on"

    /** After-work, which suits more people than any morning hour does. */
    const val DEFAULT_FALLBACK = "17:30"

    /**
     * Sports that are not training. Walks are mostly a commute or the dog; a
     * lunchtime walk habit would otherwise pull the reminder to noon, and a walk
     * to the shop would count as having trained today.
     */
    private val NOT_TRAINING = setOf("walking")

    /** How far either side of today a planned session means the plan is live. */
    private const val PLAN_HORIZON_DAYS = 7L

    /** The next reminder, for the Settings line. Null when switched off. */
    data class Next(val at: LocalDateTime, val learned: Boolean, val habit: LocalTime?)

    private val _next = MutableStateFlow<Next?>(null)
    val next: StateFlow<Next?> get() = _next

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun fallback(context: Context): String =
        prefs(context).getString(KEY_FALLBACK, DEFAULT_FALLBACK) ?: DEFAULT_FALLBACK

    suspend fun setEnabled(context: Context, sources: LocalSources, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        rearm(context, sources)
    }

    suspend fun setFallback(context: Context, sources: LocalSources, hhmm: String) {
        prefs(context).edit().putString(KEY_FALLBACK, hhmm).apply()
        rearm(context, sources)
    }

    /**
     * Arm the next reminder: today's if its time is still ahead and today has
     * not had one, otherwise tomorrow's.
     *
     * A reminder whose time has already passed is not sent late. Someone who
     * runs at 7:00 and opens the app at 10:00 gets nothing until tomorrow — a
     * nudge hours after the moment it was aimed at is the noise this avoids.
     */
    suspend fun rearm(context: Context, sources: LocalSources, now: LocalDateTime = LocalDateTime.now()) {
        if (!enabled(context)) {
            cancel(context)
            _next.value = null
            return
        }
        val starts = history(sources)
        val fallback = minuteOf(fallback(context))
        val spent = prefs(context).getString(KEY_SPENT_ON, null)
        val today = now.toLocalDate()
        for (day in listOf(today, today.plusDays(1))) {
            if (day.toString() == spent) continue
            val timing = WorkoutNudge.timing(relativeTo(starts, day), day.dayOfWeek.value, fallback)
            val at = day.atTime(LocalTime.of(timing.minuteOfDay / 60, timing.minuteOfDay % 60))
            if (!at.isAfter(now)) continue
            arm(context, at)
            _next.value = Next(at, timing.learned, timing.habitMinute?.let { LocalTime.of(it / 60, it % 60) })
            return
        }
    }

    /**
     * The alarm went off for [day]: post if the day still wants a reminder, then
     * arm tomorrow's. Marked spent first, so nothing after it can double up.
     *
     * A day already spent does nothing but re-arm. That case is real: an alarm
     * that starts the process also runs the launch-time [rearm], which can read
     * the day as unspent a moment before this marks it, and arm a second alarm
     * for later the same day.
     */
    suspend fun fire(context: Context, sources: LocalSources, day: LocalDate) {
        val prefs = prefs(context)
        if (prefs.getString(KEY_SPENT_ON, null) == day.toString()) {
            rearm(context, sources)
            return
        }
        prefs.edit().putString(KEY_SPENT_ON, day.toString()).commit()
        try {
            if (enabled(context) && canNotify(context)) {
                val starts = history(sources)
                val planned = sources.plannedWorkouts(
                    from = day.minusDays(PLAN_HORIZON_DAYS).toString(),
                    to = day.plusDays(PLAN_HORIZON_DAYS).toString(),
                )
                val todays = planned.filter { it.scheduledDate == day.toString() }
                val remind = WorkoutNudge.shouldRemind(
                    trainedToday = starts.any { it.toLocalDate() == day },
                    planActive = planned.isNotEmpty(),
                    plannedToday = todays.map { it.workoutType },
                    plannedTodayComplete = todays.filter { it.workoutType != "rest" }.all { it.isComplete },
                )
                if (remind) {
                    val habit = WorkoutNudge.timing(relativeTo(starts, day), day.dayOfWeek.value, 0)
                        .takeIf { it.learned }?.habitMinute
                    post(context, todays.firstOrNull { it.workoutType != "rest" && !it.isComplete }, habit)
                }
            }
        } finally {
            rearm(context, sources)
        }
    }

    /**
     * Whether a notification would actually appear: the Android 13 grant, and
     * the app's notifications not switched off in system settings, which no
     * permission check catches.
     */
    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Local start times of every recent training activity. */
    private suspend fun history(sources: LocalSources): List<LocalDateTime> {
        val cutoff = LocalDate.now().minusDays(WorkoutNudge.WINDOW_DAYS + 1L)
        return sources.activities()
            .filter { it.sport !in NOT_TRAINING }
            .mapNotNull { a -> a.startedAt?.let { startedLocal(it)?.toLocalDateTime() } }
            .filter { it.toLocalDate().isAfter(cutoff) }
    }

    private fun relativeTo(starts: List<LocalDateTime>, day: LocalDate): List<WorkoutNudge.Start> =
        starts.map {
            WorkoutNudge.Start(
                minuteOfDay = it.hour * 60 + it.minute,
                dayOfWeek = it.dayOfWeek.value,
                daysAgo = ChronoUnit.DAYS.between(it.toLocalDate(), day).toInt(),
            )
        }

    private fun minuteOf(hhmm: String): Int =
        runCatching { LocalTime.parse(hhmm.take(5)) }.getOrDefault(LocalTime.of(17, 30))
            .let { it.hour * 60 + it.minute }

    private fun arm(context: Context, at: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, alarmIntent(context, at.toLocalDate()))
    }

    private fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, WorkoutReminderReceiver::class.java).setData(ALARM_URI),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    /**
     * The day rides in the extras because an inexact alarm can land a few
     * minutes late, and one aimed at 23:58 must still count for the day it was
     * for. (The clamp keeps reminders before 21:00, but the reason holds.)
     */
    private fun alarmIntent(context: Context, day: LocalDate): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, WorkoutReminderReceiver::class.java)
                .setData(ALARM_URI)
                .putExtra(EXTRA_DAY, day.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun post(context: Context, session: PlannedWorkout?, habitMinute: Int?) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TODAY)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = when {
            session != null -> context.getString(R.string.workout_reminder_planned_title, session.title.ifBlank { "your session" }) to
                (session.durationMinutes?.let { context.getString(R.string.workout_reminder_planned_minutes, it) }
                    ?: context.getString(R.string.workout_reminder_planned_text))
            habitMinute != null -> context.getString(R.string.workout_reminder_title) to
                context.getString(R.string.workout_reminder_habit_text, "%d:%02d".format(habitMinute / 60, habitMinute % 60))
            else -> context.getString(R.string.workout_reminder_title) to context.getString(R.string.workout_reminder_text)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /**
     * Its own channel, at default importance: a nudge, not a demand like a
     * dose. Switchable off in system settings without losing the others.
     */
    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.workout_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.workout_reminder_channel_description) },
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val EXTRA_DAY = "day"
    private val ALARM_URI: Uri = Uri.parse("tracks://workout-reminder")
}
