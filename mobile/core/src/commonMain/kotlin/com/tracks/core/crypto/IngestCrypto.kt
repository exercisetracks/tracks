// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.crypto

/**
 * The two cryptographic operations `/sync/ingest` needs, and nothing else.
 *
 * ## Why sealing at all
 *
 * Tracks encrypts each user's data under a key the server can only reconstruct
 * while that user has a live session. The sync-agent path deliberately does not
 * need one: the phone seals a FIT file with the user's *public* key and posts
 * it, and the server queues a blob it genuinely cannot open until the user next
 * logs in.
 *
 * That is the property that makes watch sync work on an expedition. The user has
 * not entered a password in three weeks, the server's cached key died when its
 * Redis restarted, and the day's ride still uploads — because nothing in that
 * path can read it anyway.
 *
 * ## Why an interface rather than expect/actual
 *
 * `core` declares iOS targets it does not build, so that shared code is forced
 * to stay shared. An `expect` here would demand an iOS `actual` and the honest
 * one would be `TODO()`, which turns that check into theatre. An interface lets
 * commonMain state what it needs while each platform supplies it when it can.
 *
 * ## Why hashing lives here too
 *
 * The server dedupes on the SHA-256 of the *plaintext*, computed by the agent.
 * It has to be plaintext: a sealed box is randomised, so the same file sealed
 * twice produces different ciphertext, and hashing that would make every retry
 * look like a new activity. Pairing the hash with the seal in one interface is
 * what keeps that pairing visible.
 */
interface IngestCrypto {

    /**
     * libsodium's `crypto_box_seal`: anonymous public-key encryption.
     *
     * Output is `ephemeral_public_key || ciphertext`, 48 bytes longer than the
     * input. There is no sender identity — the ephemeral key is discarded — so
     * the server learns only that *someone* holding the user's public key
     * produced this.
     */
    fun seal(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray

    /** Lowercase hex SHA-256, which is the form the server compares against. */
    fun sha256Hex(bytes: ByteArray): String
}
