// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

/**
 * SHA-1 and SHA-256, in common code.
 *
 * Written out rather than pulled in because `commonMain` has to compile for
 * iOS, and the digests available here otherwise are either JVM-only
 * (`MessageDigest`) or a whole crypto library for two functions. Neither use
 * is a security boundary on its own:
 *
 * - SHA-1 only because UUIDv5 is *defined* over SHA-1 — the uid has to match
 *   the one the server derives from the same name, not resist collisions.
 * - SHA-256 identifies FIT files and checks a download arrived intact. The
 *   file is sealed and authenticated separately on the way in.
 *
 * Both are checked against published test vectors in the jvmTest suite, which
 * is what makes a hand-rolled digest acceptable at all.
 */
internal object Digest {

    fun sha1(input: ByteArray): ByteArray {
        val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
        val w = IntArray(80)
        for (block in pad(input).chunked64()) {
            for (i in 0 until 16) w[i] = block.intAt(i * 4)
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
            for (i in 0 until 80) {
                val (f, k) = when (i) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = t
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
        }
        return h.toBytes()
    }

    fun sha256(input: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        val w = IntArray(64)
        for (block in pad(input).chunked64()) {
            for (i in 0 until 16) w[i] = block.intAt(i * 4)
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K256[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.toBytes()
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Merkle–Damgård padding shared by both: 0x80, zeros, then the bit length big-endian. */
    private fun pad(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        var padded = input.size + 1
        while (padded % 64 != 56) padded++
        val out = input.copyOf(padded + 8)
        out[input.size] = 0x80.toByte()
        for (i in 0 until 8) out[padded + i] = (bitLength ushr (56 - 8 * i)).toByte()
        return out
    }

    private fun ByteArray.chunked64(): List<ByteArray> =
        (indices step 64).map { copyOfRange(it, it + 64) }

    private fun ByteArray.intAt(i: Int): Int =
        ((this[i].toInt() and 0xff) shl 24) or ((this[i + 1].toInt() and 0xff) shl 16) or
            ((this[i + 2].toInt() and 0xff) shl 8) or (this[i + 3].toInt() and 0xff)

    private fun IntArray.toBytes(): ByteArray {
        val out = ByteArray(size * 4)
        forEachIndexed { n, v -> for (i in 0 until 4) out[n * 4 + i] = (v ushr (24 - 8 * i)).toByte() }
        return out
    }

    private val K256 = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
}
