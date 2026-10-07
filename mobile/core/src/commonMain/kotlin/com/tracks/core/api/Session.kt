// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

/**
 * Credentials the client holds, and where they live.
 *
 * ## Three credentials, three jobs
 *
 * Tracks has two independent expiries, and conflating them is the single
 * easiest way to build a client that nags the user weekly:
 *
 * - The **access token** (JWT) proves who you are. 30 days.
 * - The **refresh token** mints new access tokens without a password. 90 days,
 *   rotating: every use returns a new one and invalidates the old, and
 *   presenting a consumed one revokes the whole family as a suspected replay.
 * - The **device key** re-opens the *vault* — the server-side cached
 *   decryption key, which lapses after 7 days idle and dies whenever Redis
 *   restarts. Nothing else can do this. A refresh token cannot, because the
 *   key is derived from a secret it does not carry.
 *
 * So `session_expired` is not a logout. It means "authenticated, but the vault
 * is shut", and the fix is a device unlock, not a password prompt. Only a
 * client with no device key has to ask the user for anything.
 *
 * ## Storage
 *
 * [TokenStore] is an interface because where these live is a platform
 * decision, and on Android it is not negotiable: the device secret decrypts
 * this user's data, so it belongs in the Keystore — hardware-backed, gated
 * behind device unlock — and never in SharedPreferences.
 */

/** A device key: the id the server knows it by, and the secret it holds. */
data class DeviceKeyCredential(val id: Int, val secret: String)

data class StoredSession(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val deviceKey: DeviceKeyCredential? = null,
    /**
     * A fourth credential, and a genuinely separate one: the sync-agent token
     * authenticates the `/sync` endpoints, where the JWT does not, and it needs
     * no vault.
     *
     * That is why watch sync survives everything else expiring. It is issued
     * exactly once — the server keeps only a hash — so losing it means
     * registering a new agent rather than recovering this one.
     */
    val agentToken: String? = null,
) {
    val isAuthenticated: Boolean get() = accessToken != null

    /** Whether this client can reopen the vault without asking the user. */
    val canSelfUnlock: Boolean get() = deviceKey != null

    /** Whether this phone can upload watch files without a live session. */
    val canIngest: Boolean get() = agentToken != null
}

/**
 * Persistence for [StoredSession]. Implementations must be safe to call from
 * multiple coroutines; the client reads and writes it around every request.
 */
interface TokenStore {
    suspend fun load(): StoredSession
    suspend fun save(session: StoredSession)
    suspend fun clear()
}

/** An in-memory store. Fine for tests; useless across process death. */
class InMemoryTokenStore(initial: StoredSession = StoredSession()) : TokenStore {
    private var session = initial
    override suspend fun load(): StoredSession = session
    override suspend fun save(session: StoredSession) { this.session = session }
    override suspend fun clear() { session = StoredSession() }
}

/**
 * What the app should show. Emitted by the client as it recovers (or fails to)
 * so the UI never has to infer state from HTTP codes.
 */
sealed interface SessionState {
    /** No credentials, or they were revoked. Show the login screen. */
    data object LoggedOut : SessionState

    /** Working normally. */
    data object Active : SessionState

    /**
     * Authenticated, but the vault is shut and this client cannot reopen it —
     * no device key enrolled. Prompt for the password, then offer to enrol one
     * so it does not happen again.
     */
    data object VaultLocked : SessionState
}

/** Thrown when a request needs the vault and the client could not reopen it. */
class VaultLockedException(message: String = "Vault is locked") : Exception(message)

/** Thrown when credentials are gone or rejected and the user must log in. */
class NotAuthenticatedException(message: String = "Not authenticated") : Exception(message)

/** A password change refused because the current password was wrong. */
class WrongPasswordException(message: String = "Current password is incorrect") : Exception(message)

/**
 * A sync call the server answered with an error. Carries the status so the
 * caller can tell a server that does not speak the protocol yet (404) from one
 * that is merely failing (5xx) — the first will not fix itself by retrying.
 */
class SyncHttpException(val status: Int, detail: String) : Exception("sync HTTP $status: $detail")

/**
 * The server answered an API call with an error. The message is the server's
 * own `detail` when it sent one — "Wrong username or password" — rather than
 * Ktor's complaint that an error page is not the JSON it expected, which is
 * what a user used to see (with "Kotlin reflection is not available" in it).
 */
class ServerErrorException(val status: Int, detail: String?) : Exception(
    detail?.takeIf { it.isNotBlank() }
        ?: if (status >= 500) "The server hit an error ($status)" else "The server refused that ($status)",
)

/**
 * Thrown when the server and this client cannot work together.
 *
 * Distinct from a transport failure on purpose: retrying will not help, and the
 * user needs to be told to update something rather than to check their signal.
 */
class IncompatibleServerException(
    val compatibility: Compatibility,
    val capabilities: Capabilities,
) : Exception(
    when (compatibility) {
        Compatibility.CLIENT_TOO_OLD ->
            "This app is too old for the server (needs API v${capabilities.minClientApiVersion})"
        Compatibility.SERVER_TOO_OLD ->
            "The server is too old for this app (it speaks API v${capabilities.apiVersion})"
        Compatibility.OK -> "Compatible"
    },
)
