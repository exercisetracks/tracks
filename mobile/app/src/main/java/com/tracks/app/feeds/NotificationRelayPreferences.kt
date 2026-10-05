// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.content.Context
import android.content.SharedPreferences
import com.tracks.device.feeds.RelayPolicy
import org.json.JSONArray

/**
 * Which apps the user lets through to the watch, and whether silent
 * notifications count.
 *
 * ## Why a preferences object rather than a field on AppContainer
 *
 * The only code that has to read this on the hot path is
 * [TracksNotificationListener], and the system owns that service's lifecycle:
 * it is created, called and destroyed without the app's own object graph being
 * involved, and there is no supported way to reach into it. So the settings it
 * needs live where a bare `Context` can find them, exactly as
 * [com.tracks.app.device.WatchLinkPreference] does for the same reason.
 *
 * Reads are cheap enough to do per notification — `SharedPreferences` keeps the
 * file in memory after the first load — which is what makes a blacklist change
 * take effect on the next notification rather than the next reconnect.
 */
object NotificationRelayPreferences {

    private const val PREFS = "tracks_notifications"
    private const val KEY_BLOCKED = "blocked_packages"
    private const val KEY_SKIP_SILENT = "skip_silent"
    private const val KEY_QUICK_REPLIES = "quick_replies"

    /**
     * What the watch offers when replying, before the user has changed it.
     * Short, because a watch shows them as a scrolling list one line each, and
     * one for the reason this app exists — someone mid-run.
     */
    val DEFAULT_QUICK_REPLIES = listOf(
        "Yes", "No", "OK", "Thanks!", "On my way", "Can't talk now",
        "Call you later", "Out on a run, back soon",
    )

    /** The watch protocol's own ceiling on the list. */
    const val MAX_QUICK_REPLIES = 16

    /** Long enough for a sentence, short enough to read on a wrist. */
    const val MAX_QUICK_REPLY_LENGTH = 40

    /** The packages the user has blacklisted. */
    fun blocked(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_BLOCKED, null)?.toSet() ?: emptySet()

    fun setBlocked(context: Context, packages: Set<String>) {
        // A defensive copy on the way in as well as out: SharedPreferences does
        // not copy the set it is handed, and mutating it afterwards corrupts
        // the in-memory cache in ways that only show up after a restart.
        prefs(context).edit().putStringSet(KEY_BLOCKED, packages.toSet()).apply()
    }

    fun setBlocked(context: Context, packageName: String, blocked: Boolean) {
        val current = blocked(context)
        val updated = if (blocked) current + packageName else current - packageName
        if (updated != current) setBlocked(context, updated)
    }

    /**
     * Whether notifications Android delivers silently stay on the phone.
     *
     * On by default. Someone who long-presses a notification and chooses
     * "Silent" has told Android not to interrupt them for that app, and the
     * watch is the last place that instruction should be ignored.
     */
    fun skipSilent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SKIP_SILENT, true)

    fun setSkipSilent(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SKIP_SILENT, value).apply()
    }

    /** Both settings, in the shape the capture layer filters with. */
    /**
     * The quick replies, in the user's order.
     *
     * A JSON array rather than a string set, because the order is the order
     * the watch lists them in and a set has none.
     */
    fun quickReplies(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_QUICK_REPLIES, null) ?: return DEFAULT_QUICK_REPLIES
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        } catch (e: org.json.JSONException) {
            DEFAULT_QUICK_REPLIES
        }
    }

    /** Blank entries dropped, each trimmed and capped, the list capped — see the limits above. */
    fun setQuickReplies(context: Context, replies: List<String>) {
        val clean = replies.map { it.trim().take(MAX_QUICK_REPLY_LENGTH) }
            .filter { it.isNotEmpty() }
            .take(MAX_QUICK_REPLIES)
        prefs(context).edit().putString(KEY_QUICK_REPLIES, JSONArray(clean).toString()).apply()
    }

    /**
     * Call [onChange] whenever the quick replies change, until the returned
     * function is called. For the watch link, which pushes the new list to a
     * connected watch at once rather than at its next connect.
     *
     * The listener is held by the returned closure as well as registered:
     * SharedPreferences keeps only a weak reference, and a listener nothing
     * else holds stops firing at the next garbage collection.
     */
    fun observeQuickReplies(context: Context, onChange: (List<String>) -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_QUICK_REPLIES) onChange(quickReplies(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun policy(context: Context): RelayPolicy = RelayPolicy(
        blockedPackages = blocked(context),
        relaySilent = !skipSilent(context),
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
