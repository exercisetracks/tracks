// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.Salsa20Engine
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.util.Arrays as BcArrays
import org.bouncycastle.util.Pack

/**
 * libsodium's sealed box, composed from Bouncy Castle primitives.
 *
 * ## Why this is written out rather than pulled from a library
 *
 * The wire format is fixed by the server, which uses PyNaCl's `SealedBox`. Every
 * Android library that implements it directly ships a prebuilt `libsodium.so`,
 * and F-Droid — the distribution target — does not accept binary blobs. Tink,
 * the obvious alternative, has X25519 but no XSalsa20-Poly1305; it went the HPKE
 * route instead. Bouncy Castle is pure Java and has all four pieces.
 *
 * So this is composition of standard primitives to a published construction, not
 * invented cryptography. The construction is:
 *
 * ```
 * ephemeral keypair (esk, epk)
 * nonce   = BLAKE2b-192(epk || recipient_pk)
 * shared  = HSalsa20(X25519(esk, recipient_pk), zeros)      // crypto_box_beforenm
 * sealed  = epk || crypto_secretbox(shared, nonce, message)
 * ```
 *
 * The safeguard is not that the code looks right — it is
 * `SealedBoxVectorTest`, which opens a ciphertext produced by the server's own
 * PyNaCl. If any of the four primitives were composed wrongly, that test could
 * not pass.
 *
 * ## The one subtle step
 *
 * `crypto_box` does not use the raw X25519 output as a key; it runs it through
 * HSalsa20 with a zero nonce first. Bouncy Castle does not expose HSalsa20, but
 * it does expose `Salsa20Engine.salsaCore`, which is the same permutation with
 * the input added back at the end. Subtracting the input recovers the raw
 * permutation output, which is exactly what HSalsa20 wants. Both directions are
 * mod 2^32, so this is exact rather than approximate.
 */
class BouncyCastleIngestCrypto(
    private val random: SecureRandom = SecureRandom(),
) : IngestCrypto {

    override fun seal(recipientPublicKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(recipientPublicKey.size == KEY_SIZE) {
            "A recipient public key is $KEY_SIZE bytes, got ${recipientPublicKey.size}"
        }

        val ephemeralSecret = ByteArray(KEY_SIZE)
        val ephemeralPublic = ByteArray(KEY_SIZE)
        X25519.generatePrivateKey(random, ephemeralSecret)
        X25519.generatePublicKey(ephemeralSecret, 0, ephemeralPublic, 0)

        try {
            val nonce = sealNonce(ephemeralPublic, recipientPublicKey)
            val shared = sharedKey(ephemeralSecret, recipientPublicKey)
            try {
                return ephemeralPublic + secretBox(shared, nonce, plaintext)
            } finally {
                BcArrays.fill(shared, 0)
            }
        } finally {
            // The ephemeral secret is what makes a sealed box anonymous; leaving
            // it in a heap that may be dumped would undo that for no benefit,
            // since it is never needed again.
            BcArrays.fill(ephemeralSecret, 0)
        }
    }

    override fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> HEX[(byte.toInt() shr 4) and 0xF].toString() + HEX[byte.toInt() and 0xF] }

    /**
     * Open a sealed box. **Tests only.**
     *
     * The app never holds a user's private key — that is the entire point of
     * sealing — so this is not part of [IngestCrypto]. It exists because a
     * cryptographic implementation that can only be checked by reading it is not
     * checked at all, and being able to decrypt PyNaCl's own output is the
     * strongest evidence available that the composition above is right.
     */
    internal fun open(recipientSecretKey: ByteArray, sealed: ByteArray): ByteArray {
        require(recipientSecretKey.size == KEY_SIZE) { "A secret key is $KEY_SIZE bytes" }
        // >=, not >: a sealed empty file is exactly 48 bytes, and a watch can
        // and does offer zero-length files.
        require(sealed.size >= KEY_SIZE + MAC_SIZE) { "Sealed box is shorter than its own overhead" }

        val ephemeralPublic = sealed.copyOfRange(0, KEY_SIZE)
        val box = sealed.copyOfRange(KEY_SIZE, sealed.size)

        val recipientPublic = ByteArray(KEY_SIZE)
        X25519.generatePublicKey(recipientSecretKey, 0, recipientPublic, 0)

        val nonce = sealNonce(ephemeralPublic, recipientPublic)
        val shared = sharedKey(recipientSecretKey, ephemeralPublic)
        try {
            return openSecretBox(shared, nonce, box)
        } finally {
            BcArrays.fill(shared, 0)
        }
    }

    /**
     * The nonce is derived, not random.
     *
     * Deriving it from both public keys is what lets a recipient recompute it
     * from the ciphertext alone — there is nowhere to put a nonce in a sealed
     * box, and it is safe because the ephemeral key is fresh for every message,
     * so the (key, nonce) pair never repeats.
     */
    private fun sealNonce(ephemeralPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val digest = Blake2bDigest(null, NONCE_SIZE, null, null)
        digest.update(ephemeralPublic, 0, ephemeralPublic.size)
        digest.update(recipientPublic, 0, recipientPublic.size)
        val out = ByteArray(NONCE_SIZE)
        digest.doFinal(out, 0)
        return out
    }

    /** `crypto_box_beforenm`: X25519 followed by HSalsa20 with a zero nonce. */
    private fun sharedKey(secretKey: ByteArray, publicKey: ByteArray): ByteArray {
        val dh = ByteArray(KEY_SIZE)
        if (!X25519.calculateAgreement(secretKey, 0, publicKey, 0, dh, 0)) {
            // A small-order public key produces an all-zero shared secret, which
            // would encrypt everything under a key an attacker also knows.
            // Bouncy Castle already rejects it; failing loudly beats returning
            // ciphertext that is not actually secret.
            throw IllegalArgumentException("X25519 agreement failed: degenerate public key")
        }
        try {
            return hSalsa20(dh, ByteArray(16))
        } finally {
            BcArrays.fill(dh, 0)
        }
    }

    /**
     * HSalsa20, built from the Salsa20 core.
     *
     * `salsaCore` computes `input + permute(input)`; HSalsa20 wants
     * `permute(input)` alone, at eight specific word positions. Subtracting the
     * input recovers it exactly.
     */
    private fun hSalsa20(key: ByteArray, input: ByteArray): ByteArray {
        // The Salsa20 state layout: constants on the diagonal, the key split
        // either side of the input block.
        val state = IntArray(16)
        state[0] = SIGMA[0]
        state[5] = SIGMA[1]
        state[10] = SIGMA[2]
        state[15] = SIGMA[3]
        for (i in 0 until 4) {
            state[1 + i] = Pack.littleEndianToInt(key, i * 4)
            state[11 + i] = Pack.littleEndianToInt(key, 16 + i * 4)
            state[6 + i] = Pack.littleEndianToInt(input, i * 4)
        }

        val permuted = IntArray(16)
        Salsa20Engine.salsaCore(20, state, permuted)

        val out = ByteArray(KEY_SIZE)
        val positions = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        positions.forEachIndexed { index, position ->
            Pack.intToLittleEndian(permuted[position] - state[position], out, index * 4)
        }
        return out
    }

    /**
     * `crypto_secretbox`: XSalsa20 for confidentiality, Poly1305 for integrity.
     *
     * The first 32 bytes of the keystream are consumed as the Poly1305 one-time
     * key and never used to encrypt anything — that is what makes the MAC key
     * unique per message, and skipping it would reuse a MAC key across messages,
     * which leaks the authentication key outright.
     */
    private fun secretBox(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = XSalsa20Engine()
        cipher.init(true, ParametersWithIV(KeyParameter(key), nonce))

        val macKey = ByteArray(MAC_KEY_SIZE)
        cipher.processBytes(macKey, 0, macKey.size, macKey, 0)

        val ciphertext = ByteArray(plaintext.size)
        cipher.processBytes(plaintext, 0, plaintext.size, ciphertext, 0)

        val poly = Poly1305()
        poly.init(KeyParameter(macKey))
        BcArrays.fill(macKey, 0)
        poly.update(ciphertext, 0, ciphertext.size)
        val mac = ByteArray(MAC_SIZE)
        poly.doFinal(mac, 0)

        return mac + ciphertext
    }

    private fun openSecretBox(key: ByteArray, nonce: ByteArray, box: ByteArray): ByteArray {
        val mac = box.copyOfRange(0, MAC_SIZE)
        val ciphertext = box.copyOfRange(MAC_SIZE, box.size)

        val cipher = XSalsa20Engine()
        cipher.init(false, ParametersWithIV(KeyParameter(key), nonce))

        val macKey = ByteArray(MAC_KEY_SIZE)
        cipher.processBytes(macKey, 0, macKey.size, macKey, 0)

        val poly = Poly1305()
        poly.init(KeyParameter(macKey))
        BcArrays.fill(macKey, 0)
        poly.update(ciphertext, 0, ciphertext.size)
        val expected = ByteArray(MAC_SIZE)
        poly.doFinal(expected, 0)

        // Constant-time: a timing-variable compare here is the textbook way to
        // turn an authenticated cipher back into an unauthenticated one.
        if (!BcArrays.constantTimeAreEqual(expected, mac)) {
            throw IllegalArgumentException("Sealed box failed authentication")
        }

        val plaintext = ByteArray(ciphertext.size)
        cipher.processBytes(ciphertext, 0, ciphertext.size, plaintext, 0)
        return plaintext
    }

    private companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 24
        const val MAC_SIZE = 16
        const val MAC_KEY_SIZE = 32

        /** "expand 32-byte k", as four little-endian words. */
        val SIGMA = intArrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574)

        const val HEX = "0123456789abcdef"
    }
}
