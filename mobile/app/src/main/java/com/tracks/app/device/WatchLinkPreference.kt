// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context
import com.tracks.app.WatchLinkService

/**
 * Whether the user wants the watch kept connected.
 *
 * ## Why this is a setting and not just the behaviour
 *
 * Holding a BLE link open costs battery on both ends and buys an ongoing
 * notification that cannot be dismissed. For the phone feeds — notifications,
 * music, weather on the wrist — that trade is obviously worth it, and it is why
 * anyone pairs a watch to a phone at all. For someone who only wants their
 * activities uploaded, it is pure cost, and the six-hourly sync already does
 * that job.
 *
 * This app's stated premise is that battery behaviour is a requirement rather
 * than polish, so the expensive option is the one the user picks rather than the
 * one they discover.
 *
 * Defaulted **on** once a watch is paired: pairing a watch to a phone is a
 * statement that you want them talking, and a relay that silently does nothing
 * until a setting is found is the failure this whole area just came out of.
 */
object WatchLinkPreference {

    private const val PREFS = "tracks_watch"
    private const val KEY_KEEP_CONNECTED = "keep_connected"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_CONNECTED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_CONNECTED, enabled).apply()
        if (enabled) start(context) else WatchLinkService.stop(context)
    }

    /**
     * Bring the link up if it should be up.
     *
     * Safe to call repeatedly — starting an already-running foreground service
     * re-delivers `onStartCommand`, which the service treats as a no-op while
     * its supervisor job is alive.
     */
    fun start(context: Context) {
        if (!isEnabled(context)) return
        // The service checks this too, and has to — the system restarts it
        // without coming through here. Checking again at the caller keeps a
        // fresh install from putting a foreground service through a start-and-
        // die cycle on every launch, before the user has been asked about
        // Bluetooth at all. Pairing asks first and then starts the link, so
        // there is no path where this silently withholds something the user
        // has already agreed to.
        if (!BluetoothPermissions.granted(context)) return
        WatchLinkService.start(context)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
