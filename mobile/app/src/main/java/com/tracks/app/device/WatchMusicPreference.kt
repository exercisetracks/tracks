// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which playlists the phone wants on the watch — an offer, not a command.
 *
 * The watch keeps its own list of ticked playlists and the user can change it
 * on the wrist. This is the phone's version of the same choice, for people who
 * would rather pick from a screen with a keyboard. It reaches the watch the
 * next time the watch asks the phone for its configuration, which the watch
 * app does every time it opens and before every sync.
 *
 * "Pending" is what keeps the two from fighting. A selection is handed over
 * once, when the user presses Send, and then stops being sent — so ticking a
 * playlist on the watch afterwards is not silently undone by a stale list from
 * the phone the next time the screens meet.
 */
object WatchMusicPreference {

    private const val PREFS = "tracks_watch_music"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_SONGS = "songs"
    private const val KEY_PENDING = "pending"
    private const val KEY_APP_ON_WATCH = "app_on_watch"
    private const val KEY_REVISION = "revision"
    private const val KEY_FINGERPRINT = "revision_fingerprint"
    private const val KEY_SALT = "revision_salt"

    /** A single song picked for the watch. The title is only for the phone's list. */
    data class PickedSong(val id: String, val albumId: String?, val title: String, val artist: String?)

    fun selected(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PLAYLISTS, emptySet())?.toSet() ?: emptySet()

    fun setSelected(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PLAYLISTS, ids.toSet()).apply()
    }

    fun pickedSongs(context: Context): List<PickedSong> {
        val raw = prefs(context).getString(KEY_SONGS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                PickedSong(o.getString("id"), o.optString("albumId").ifEmpty { null }, o.optString("title"), o.optString("artist").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    fun setPickedSongs(context: Context, songs: List<PickedSong>) {
        val array = JSONArray()
        for (s in songs) {
            array.put(JSONObject().put("id", s.id).put("albumId", s.albumId).put("title", s.title).put("artist", s.artist))
        }
        prefs(context).edit().putString(KEY_SONGS, array.toString()).apply()
    }

    fun isPending(context: Context): Boolean = prefs(context).getBoolean(KEY_PENDING, false)

    fun setPending(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_PENDING, pending).apply()
    }

    /**
     * Whether Tracks Music is on the watch, as last seen. Remembered because
     * asking the watch is a Bluetooth exchange, and Settings should not
     * offer to send an app that is already there while it waits for one.
     */
    fun isAppOnWatch(context: Context): Boolean = prefs(context).getBoolean(KEY_APP_ON_WATCH, false)

    fun setAppOnWatch(context: Context, onWatch: Boolean) {
        prefs(context).edit().putBoolean(KEY_APP_ON_WATCH, onWatch).apply()
    }

    // ── the revision the watch compares (see WatchConfigRevision) ────────────

    fun revision(context: Context): Long = prefs(context).getLong(KEY_REVISION, 0L)

    /** Record the settings as they are now; returns the revision that describes them. */
    @Synchronized
    fun reconcileRevision(context: Context, fingerprint: String): Long =
        store(context, WatchConfigRevision.reconcile(revisionState(context), fingerprint, System.currentTimeMillis()))

    /** A selection was sent: a change the fingerprint cannot see. */
    @Synchronized
    fun bumpRevision(context: Context): Long =
        store(context, WatchConfigRevision.bump(revisionState(context), System.currentTimeMillis()))

    fun fingerprint(context: Context, url: String, username: String, password: String): String =
        WatchConfigRevision.fingerprint(salt(context), url, username, password)

    private fun revisionState(context: Context) = WatchConfigRevision.State(
        revision = revision(context),
        fingerprint = prefs(context).getString(KEY_FINGERPRINT, null),
    )

    private fun store(context: Context, state: WatchConfigRevision.State): Long {
        // commit, not apply: the number is about to be handed to the watch,
        // and a revision the watch has seen must never be lost to a crash and
        // handed out again for different settings.
        prefs(context).edit()
            .putLong(KEY_REVISION, state.revision)
            .putString(KEY_FINGERPRINT, state.fingerprint)
            .commit()
        return state.revision
    }

    /** Random per install; see WatchConfigRevision.fingerprint for why. */
    @Synchronized
    private fun salt(context: Context): ByteArray {
        prefs(context).getString(KEY_SALT, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit().putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP)).commit()
        return salt
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
