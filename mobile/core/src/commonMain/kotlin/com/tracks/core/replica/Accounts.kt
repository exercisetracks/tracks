// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

/**
 * The account a phone's data belongs to: a server (by its node id) and an
 * account on it. Fixed at the first sign-in; see [ReplicaStore.link].
 */
data class AccountBinding(val serverId: String, val account: String)

/** What happened when an account signed in. */
sealed interface LinkResult {
    /** The phone was unbound or empty, and now belongs to this account. */
    data object Linked : LinkResult

    /** The same account as before, signing back in. */
    data object Resumed : LinkResult

    /**
     * Someone else's data is on this phone: a different account on the *same*
     * server. The sign-in must not proceed; the UI offers "erase this phone's
     * data?" and only an explicit erase clears the way.
     */
    data class OtherAccount(val bound: AccountBinding) : LinkResult

    /**
     * The phone's data came from a server with a different id. That cannot be
     * told apart from the same server rebuilt — a rebuilt database mints new
     * ids for everything, accounts included — so it is a question, never an
     * automatic merge: "Restore it into [username] here?" Yes is
     * [AccountGate.restore]; no is [AccountGate.decline].
     *
     * The session is still live while the question is open, so the answer
     * does not need a second password.
     */
    data class DifferentServer(
        val bound: AccountBinding,
        val server: ServerIdentity,
        val account: String,
        val username: String?,
    ) : LinkResult
}
