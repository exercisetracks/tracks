// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.nudge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tracks.app.TracksApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * The workout reminder's alarm, and the reboot that clears it.
 *
 * One receiver for both because both end the same way — arm the next alarm —
 * and a reboot needs no reminder of its own. See [WorkoutReminders].
 */
class WorkoutReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? TracksApplication ?: return
        val reboot = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val day = intent.getStringExtra(WorkoutReminders.EXTRA_DAY)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (!reboot && day == null) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Bounded well inside a broadcast's ten seconds. A store that
                // cannot be read in time loses one day's reminder; the next
                // app launch re-arms the chain.
                withTimeoutOrNull(TIMEOUT_MS) {
                    val sources = app.container.sources
                    if (day != null) WorkoutReminders.fire(context, sources, day)
                    else WorkoutReminders.rearm(context, sources)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}

private const val TIMEOUT_MS = 8_000L
