// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * The account's time zone is the phone's own, kept in step without asking.
 *
 * It used to be a text field in Settings and onboarding, which nobody should
 * have to fill in — the phone knows — and which went stale the first time its
 * owner travelled: the plan's day boundaries and the server's "today" stayed
 * at home while the person did not. So there is no field on the phone any
 * more; the app compares the synced `timezone` setting with the phone's zone
 * whenever that could have changed and writes it when they differ:
 *
 * - at startup and after each pull ([AppContainer.refreshPreferences]);
 * - when the app comes back to the foreground (MainActivity.onResume);
 * - on ACTION_TIMEZONE_CHANGED while the process is alive ([TimezoneReceiver]).
 *
 * The web keeps its field, for accounts used only in a browser; the phone
 * wins on its next check, which is the point — it is the device that travels.
 */
internal object TimezoneSync {
    /**
     * The zone to write, or null to leave the row alone.
     *
     * Never while the settings row does not exist yet: on a phone that has
     * signed in but not yet pulled, writing would create a settings row of
     * its own ahead of the account's. The check after the first pull writes
     * it instead. A zone the phone reports but Java cannot name is never
     * written — the server parses this value.
     */
    fun zoneToWrite(stored: String?, phone: String, rowExists: Boolean): String? = when {
        !rowExists -> null
        phone.isBlank() || runCatching { java.time.ZoneId.of(phone) }.isFailure -> null
        stored == phone -> null
        else -> phone
    }
}

/**
 * ACTION_TIMEZONE_CHANGED, registered at runtime by the Application. A system
 * broadcast, so it is delivered to a not-exported receiver; manifest
 * registration would also work (it is on Android's implicit-broadcast
 * exemption list) but would start the process for it, and the next launch
 * or resume checks anyway.
 */
internal class TimezoneReceiver(private val onChanged: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) onChanged()
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context, this, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
