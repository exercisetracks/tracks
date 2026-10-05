// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import java.security.MessageDigest

/**
 * The number the phone stamps on its answer to the watch music app, and when
 * it moves.
 *
 * The watch asks the phone for its music settings on every open and before
 * every sync, and applies an answer only when this number is newer than the
 * last one it applied (see `watchapp/source/TracksPairing.mc`). So the number
 * has to move exactly when the settings did: too rarely and a change never
 * reaches the watch; too often and an account typed on the watch is
 * overwritten by the phone's for no reason.
 *
 * It moves when:
 *  - the music server's address, user or password changed, or the server was
 *    removed — detected by [reconcile] from a fingerprint, so a change made in
 *    the web app counts as well as one made on this phone;
 *  - a playlist or song selection is sent ([bump]).
 *
 * ## Why wall-clock based
 *
 * A plain counter restarts at zero when the app's data is cleared or the app is
 * reinstalled, and a watch that had applied revision 7 would then ignore the
 * phone until it had counted past 7 again. Using the time of the change —
 * never less than one more than the last — keeps the number rising across a
 * reinstall, and still rising if the clock is set back.
 */
object WatchConfigRevision {

    /**
     * What is persisted: the revision, and a fingerprint of the settings it
     * describes. A null fingerprint means nothing has been recorded yet.
     */
    data class State(val revision: Long = 0L, val fingerprint: String? = null)

    /** The fingerprint of "no music server". */
    const val NO_SERVER = ""

    /** The revision after [previous], at wall-clock time [now] (ms). */
    fun next(previous: Long, now: Long): Long = maxOf(previous + 1, now)

    /** The settings changed in a way the fingerprint cannot see: a selection was sent. */
    fun bump(state: State, now: Long): State = state.copy(revision = next(state.revision, now))

    /**
     * Bring [state] up to date with the settings the phone has now, as
     * [fingerprint]. Moves the revision only when they differ from what it was
     * last stamped on — with one exception: a phone that has never recorded
     * anything and has no server keeps revision 0, which the phone does not
     * announce (see WatchAppConfig.signOut). Nothing changed there; a fresh
     * install with no music server must not sign a watch out.
     */
    fun reconcile(state: State, fingerprint: String, now: Long): State = when {
        state.fingerprint == fingerprint -> state
        state.fingerprint == null && fingerprint == NO_SERVER -> state.copy(fingerprint = fingerprint)
        else -> State(next(state.revision, now), fingerprint)
    }

    /**
     * A fingerprint of one server login, for telling whether it changed.
     *
     * It covers the password, because a changed password is exactly the change
     * the watch most needs to hear about. That makes it a password-derived
     * value, so it is salted with a random per-install [salt] and kept in the
     * app's private preferences — next to the session that can fetch the
     * password itself from the server, so it exposes nothing that storage did
     * not already hold, and it is useless for recognising the password
     * anywhere else.
     */
    fun fingerprint(salt: ByteArray, url: String, username: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        // NUL-separated so that ("ab", "c") and ("a", "bc") differ.
        for (part in listOf(url.trimEnd('/'), username, password)) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
