// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import com.tracks.core.api.SyncHttpException
import com.tracks.core.api.TracksClient

/**
 * The account check that runs right after a password sign-in.
 *
 * Sign-in has to succeed before the phone can learn *who* signed in, so the
 * tokens exist by the time this runs. On [LinkResult.OtherAccount] it signs
 * straight back out, which leaves the phone exactly as it was: the other
 * account's data untouched and no session that could sync into it. On
 * [LinkResult.DifferentServer] it leaves the session up and waits for the
 * user's answer — [restore] or [decline].
 *
 * A server that does not speak the sync protocol yet (404 on the pull) is let
 * through as [LinkResult.Linked] without binding anything, because there is
 * nothing it could merge into and refusing would lock the user out of an
 * older server entirely. The binding is made at the first sign-in that can.
 */
class AccountGate(
    private val client: TracksClient,
    private val store: ReplicaStore,
    private val transport: SyncTransport = HttpSyncTransport(client),
) {
    suspend fun afterSignIn(): LinkResult {
        val server = try {
            transport.identify()
        } catch (e: SyncHttpException) {
            if (e.status == 404) return LinkResult.Linked
            throw e
        }
        val user = client.currentUser()
        val result = store.link(server, user.id.toString(), user.username)
        if (result is LinkResult.OtherAccount) client.logout()
        return result
    }

    /** Yes: restore this phone's data into the account on the new server. */
    suspend fun restore(question: LinkResult.DifferentServer) {
        store.restoreInto(question.server, question.account)
    }

    /** No: drop the session, keep the data and its binding exactly as they were. */
    suspend fun decline() {
        client.logout()
    }
}
