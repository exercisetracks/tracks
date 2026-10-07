// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.donate

import android.content.Context

/**
 * When the gentle "support this app" banner is due, and nothing else.
 *
 * First due a week after the app is first opened — not on day one, which
 * would read as asking for money before earning any trust — then at most
 * once a month after a dismissal. Entirely local: nothing about this is
 * sent to the server or synced to any other device.
 */
object DonatePreference {

    private const val PREFS = "tracks_donate"
    private const val KEY_FIRST_SEEN = "first_seen"
    private const val KEY_LAST_SHOWN = "last_shown"

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

    fun isDue(context: Context): Boolean {
        val p = prefs(context)
        val firstSeen = recordFirstSeenIfAbsent(p)
        val now = System.currentTimeMillis()
        if (now - firstSeen < WEEK_MS) return false
        val lastShown = p.getLong(KEY_LAST_SHOWN, -1L)
        return lastShown < 0 || now - lastShown >= MONTH_MS
    }

    fun dismiss(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_SHOWN, System.currentTimeMillis()).apply()
    }

    // Starts the week-long clock the first time this is ever checked, rather
    // than on install — the banner is only ever asked about from Compose, so
    // "first checked" and "first seen" are the same moment in practice.
    private fun recordFirstSeenIfAbsent(p: android.content.SharedPreferences): Long {
        val existing = p.getLong(KEY_FIRST_SEEN, -1L)
        if (existing >= 0) return existing
        val now = System.currentTimeMillis()
        p.edit().putLong(KEY_FIRST_SEEN, now).apply()
        return now
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
