// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

/**
 * CPython's `random.Random`, as far as the planners use it: seeded from a
 * string or a small int, then `shuffle`, `random` and weighted `choices`.
 *
 * ## Why reproduce Python's generator rather than use Kotlin's
 *
 * The strength and mobility planners rotate their picks with
 * `Random(f"{sport}-{split}-{slot}-{week}").shuffle(top)`, and which exercise
 * lands first *is* the plan. Any other generator — or the same Mersenne Twister
 * seeded a different way — puts a different lift on the phone's Tuesday than on
 * the server's, for the same person, and two replicas of one plan disagree.
 * So this is the exact algorithm: MT19937 seeded by `init_by_array`, a string
 * seed turned into an integer the way CPython 3.2+ does it (the UTF-8 bytes
 * followed by their SHA-512, read big-endian), and `shuffle` over
 * `_randbelow_with_getrandbits`. `spec/fixtures/strength_plan.json` pins its
 * output against CPython 3.12 directly.
 */
internal class PyRandom private constructor(key: IntArray) {

    /** `random.Random(str)`: the UTF-8 bytes and their SHA-512, as one integer. */
    constructor(seed: String) : this(
        seed.encodeToByteArray().let { utf8 -> Companion.keyWords(utf8 + Sha512.digest(utf8)) },
    )

    /**
     * `random.Random(int)`: the integer's absolute value, as 32-bit words
     * least significant first — the endurance planner seeds its MTB skills
     * picker with a small int.
     */
    constructor(seed: Long) : this(Companion.intKey(seed))

    private val mt = IntArray(N)
    private var index = N

    init {
        initByArray(key)
    }

    /** `random.random()`: 53 bits from two draws, exactly as CPython's `genrand_res53`. */
    fun random(): Double {
        val a = nextUInt32() ushr 5
        val b = nextUInt32() ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    /**
     * `random.choices(population, weights, k=1)[0]`: one pick against the
     * running sum of the weights, found with `bisect_right` over all but the
     * last cumulative weight, as CPython 3.12 does it.
     */
    fun <T> choice(population: List<T>, weights: List<Long>): T {
        val cum = ArrayList<Long>(weights.size)
        var acc = 0L
        for (w in weights) { acc += w; cum += acc }
        val total = cum.last() + 0.0
        require(total > 0.0) { "total of weights must be greater than zero" }
        val x = random() * total
        var lo = 0
        var hi = population.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (x < cum[mid]) hi = mid else lo = mid + 1
        }
        return population[lo]
    }

    /** `random.shuffle`: Fisher–Yates from the end, exactly as CPython walks it. */
    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = randBelow(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }

    /** `random.randrange(n)` for n > 0: CPython draws it with `_randbelow`. */
    fun randrange(n: Int): Int {
        require(n > 0) { "empty range for randrange()" }
        return randBelow(n)
    }

    /** `_randbelow_with_getrandbits`: rejection sampling, never a modulo. */
    private fun randBelow(n: Int): Int {
        val k = 32 - n.countLeadingZeroBits()
        var r = getRandBits(k)
        while (r >= n) r = getRandBits(k)
        return r
    }

    /** `getrandbits(k)` for k ≤ 31: the top k bits of one 32-bit draw. */
    private fun getRandBits(k: Int): Int = (nextUInt32() ushr (32 - k)).toInt()

    private fun nextUInt32(): Long {
        if (index >= N) twist()
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y.toLong() and 0xffffffffL
    }

    private fun twist() {
        for (i in 0 until N) {
            val y = (mt[i] and UPPER_MASK) or (mt[(i + 1) % N] and LOWER_MASK)
            var v = mt[(i + M) % N] xor (y ushr 1)
            if (y and 1 != 0) v = v xor MATRIX_A
            mt[i] = v
        }
        index = 0
    }

    private fun initGenrand(s: Int) {
        mt[0] = s
        for (i in 1 until N) {
            val prev = mt[i - 1]
            mt[i] = 1812433253 * (prev xor (prev ushr 30)) + i
        }
        index = N
    }

    private fun initByArray(key: IntArray) {
        initGenrand(19650218)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            val prev = mt[i - 1]
            mt[i] = (mt[i] xor ((prev xor (prev ushr 30)) * 1664525)) + key[j] + j
            i++; j++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            val prev = mt[i - 1]
            mt[i] = (mt[i] xor ((prev xor (prev ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) { mt[0] = mt[N - 1]; i = 1 }
            k--
        }
        mt[0] = 0x80000000.toInt()
    }

    private companion object {
        const val N = 624
        const val M = 397
        const val MATRIX_A = 0x9908b0df.toInt()
        const val UPPER_MASK = 0x80000000.toInt()
        const val LOWER_MASK = 0x7fffffff

        /** `init_by_array`'s key for an int seed: |n| as 32-bit words, least significant first. */
        fun intKey(seed: Long): IntArray {
            val n = if (seed < 0) -seed else seed
            val lo = (n and 0xffffffffL).toInt()
            val hi = (n ushr 32).toInt()
            return if (hi == 0) intArrayOf(lo) else intArrayOf(lo, hi)
        }

        /**
         * The big-endian integer `bytes` spells, as `init_by_array` wants it:
         * 32-bit words least significant first, leading zero words dropped
         * (CPython sizes the key by the integer's bit length), `[0]` for zero.
         */
        fun keyWords(bytes: ByteArray): IntArray {
            val words = ArrayList<Int>()
            var end = bytes.size
            while (end > 0) {
                val start = maxOf(0, end - 4)
                var w = 0
                for (b in start until end) w = (w shl 8) or (bytes[b].toInt() and 0xff)
                words += w
                end = start
            }
            while (words.size > 1 && words.last() == 0) words.removeAt(words.size - 1)
            return if (words.isEmpty()) intArrayOf(0) else words.toIntArray()
        }
    }
}

/**
 * SHA-512 (FIPS 180-4), in common code because no multiplatform dependency
 * here provides it. Only [PyRandom] needs it, to turn a string seed into
 * CPython's integer seed.
 */
internal object Sha512 {
    // The standard constants, as hex strings: most exceed Long.MAX_VALUE, and
    // writing them as signed literals is where transcription errors hide.
    private val K: LongArray = hex(
        "428a2f98d728ae22", "7137449123ef65cd", "b5c0fbcfec4d3b2f", "e9b5dba58189dbbc",
        "3956c25bf348b538", "59f111f1b605d019", "923f82a4af194f9b", "ab1c5ed5da6d8118",
        "d807aa98a3030242", "12835b0145706fbe", "243185be4ee4b28c", "550c7dc3d5ffb4e2",
        "72be5d74f27b896f", "80deb1fe3b1696b1", "9bdc06a725c71235", "c19bf174cf692694",
        "e49b69c19ef14ad2", "efbe4786384f25e3", "0fc19dc68b8cd5b5", "240ca1cc77ac9c65",
        "2de92c6f592b0275", "4a7484aa6ea6e483", "5cb0a9dcbd41fbd4", "76f988da831153b5",
        "983e5152ee66dfab", "a831c66d2db43210", "b00327c898fb213f", "bf597fc7beef0ee4",
        "c6e00bf33da88fc2", "d5a79147930aa725", "06ca6351e003826f", "142929670a0e6e70",
        "27b70a8546d22ffc", "2e1b21385c26c926", "4d2c6dfc5ac42aed", "53380d139d95b3df",
        "650a73548baf63de", "766a0abb3c77b2a8", "81c2c92e47edaee6", "92722c851482353b",
        "a2bfe8a14cf10364", "a81a664bbc423001", "c24b8b70d0f89791", "c76c51a30654be30",
        "d192e819d6ef5218", "d69906245565a910", "f40e35855771202a", "106aa07032bbd1b8",
        "19a4c116b8d2d0c8", "1e376c085141ab53", "2748774cdf8eeb99", "34b0bcb5e19b48a8",
        "391c0cb3c5c95a63", "4ed8aa4ae3418acb", "5b9cca4f7763e373", "682e6ff3d6b2b8a3",
        "748f82ee5defb2fc", "78a5636f43172f60", "84c87814a1f0ab72", "8cc702081a6439ec",
        "90befffa23631e28", "a4506cebde82bde9", "bef9a3f7b2c67915", "c67178f2e372532b",
        "ca273eceea26619c", "d186b8c721c0c207", "eada7dd6cde0eb1e", "f57d4f7fee6ed178",
        "06f067aa72176fba", "0a637dc5a2c898a6", "113f9804bef90dae", "1b710b35131c471b",
        "28db77f523047d84", "32caab7b40c72493", "3c9ebe0a15c9bebc", "431d67c49c100d4c",
        "4cc5d4becb3e42b6", "597f299cfc657e2a", "5fcb6fab3ad6faec", "6c44198c4a475817",
    )

    private fun hex(vararg words: String): LongArray = LongArray(words.size) { words[it].toULong(16).toLong() }

    fun digest(input: ByteArray): ByteArray {
        val h = hex(
            "6a09e667f3bcc908", "bb67ae8584caa73b", "3c6ef372fe94f82b", "a54ff53a5f1d36f1",
            "510e527fade682d1", "9b05688c2b3e6c1f", "1f83d9abfb41bd6b", "5be0cd19137e2179",
        )
        // Pad to a multiple of 128 bytes: 0x80, zeros, then the bit length in
        // the last 16 bytes (the high 8 are zero for any input that fits here).
        val paddedLen = ((input.size + 17 + 127) / 128) * 128
        val msg = input.copyOf(paddedLen)
        msg[input.size] = 0x80.toByte()
        val bits = input.size.toLong() * 8
        for (i in 0 until 8) msg[paddedLen - 1 - i] = (bits ushr (8 * i)).toByte()

        val w = LongArray(80)
        for (block in 0 until paddedLen / 128) {
            for (t in 0 until 16) {
                var v = 0L
                for (b in 0 until 8) v = (v shl 8) or (msg[block * 128 + t * 8 + b].toLong() and 0xff)
                w[t] = v
            }
            for (t in 16 until 80) {
                val s0 = w[t - 15].rotateRight(1) xor w[t - 15].rotateRight(8) xor (w[t - 15] ushr 7)
                val s1 = w[t - 2].rotateRight(19) xor w[t - 2].rotateRight(61) xor (w[t - 2] ushr 6)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 80) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(64)
        for (i in 0 until 8) for (b in 0 until 8) out[i * 8 + b] = (h[i] ushr (56 - 8 * b)).toByte()
        return out
    }
}
