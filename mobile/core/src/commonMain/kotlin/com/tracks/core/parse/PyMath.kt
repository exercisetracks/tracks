// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import kotlin.math.withSign
import kotlin.math.abs

/**
 * Python's arithmetic where it is not IEEE's, for parsers that must agree with
 * the server to the last bit.
 *
 * Three functions the server's parsers lean on do not do what the obvious
 * Kotlin does:
 *
 * - **`round(x, n)`** rounds the *exact* binary value half-to-even, then reads
 *   the decimal back correctly rounded. `round(2.675, 2)` is 2.67 because the
 *   double is 2.67499999…; scaling by 100 and rounding would say 2.68.
 * - **`statistics.mean`** sums as exact fractions and divides once, correctly
 *   rounded. A running double sum drifts in the last bits over a few thousand
 *   track points, and `avg_speed` is stored unrounded.
 * - **`sum()`** on floats has used Neumaier's compensated summation since
 *   Python 3.12 — the server's version — so it is neither naive nor exact.
 *
 * The metrics port (com.tracks.core.metrics) adds `statistics.pstdev` and
 * `f"{x:.nf}"`, for Foster monotony and readiness notes. One implementation of
 * Python arithmetic for the whole core, not one per port.
 *
 * Each is checked against Python itself by `spec/fixtures/py_math.json` and
 * the `py_math` section of `spec/fixtures/metrics.json`.
 */
internal object PyMath {

    /**
     * `round(x, ndigits)` for a float, `ndigits` in 0..9.
     *
     * Exact: the double is m·2^e, so x·10^n is the integer m·10^n shifted by e,
     * which rounds half-to-even with no error; the result is then that integer
     * over 10^n, correctly rounded — which is what CPython's dtoa/strtod round
     * trip produces.
     */
    fun round(x: Double, ndigits: Int): Double {
        require(ndigits in 0..9) { "ndigits $ndigits" }
        if (x.isNaN() || x.isInfinite() || x == 0.0) return x
        val negative = x < 0
        val (m, e) = decompose(abs(x))
        // P = m·10^n·2^e. For e >= 0 it is already an integer and x is too:
        // rounding an integer to n >= 0 places returns it unchanged.
        if (e >= 0) return x
        val scaled = BigNat.of(m).times(pow10(ndigits))
        val shift = -e
        var n = scaled.shr(shift)
        val remainderIsHalfOrMore = scaled.bit(shift - 1)
        val remainderAboveHalf = remainderIsHalfOrMore && scaled.anyBitBelow(shift - 1)
        if (remainderAboveHalf || (remainderIsHalfOrMore && n.bit(0))) n = n.plus(BigNat.ONE)
        if (n.isZero()) return if (negative) -0.0 else 0.0
        val result = correctlyRounded(n, 0, pow10(ndigits))
        return if (negative) -result else result
    }

    /**
     * `statistics.mean` over numbers that are all ints or floats: the exact
     * rational mean, correctly rounded to a double.
     */
    fun mean(values: List<Double>): Double {
        require(values.isNotEmpty()) { "mean requires at least one data point" }
        var minExp = Int.MAX_VALUE
        val parts = values.map { v ->
            require(!v.isNaN() && !v.isInfinite()) { "non-finite mean input" }
            val (m, e) = decompose(abs(v))
            if (m != 0L && e < minExp) minExp = e
            Triple(v < 0, m, e)
        }
        if (minExp == Int.MAX_VALUE) return 0.0
        var pos = BigNat.ZERO
        var neg = BigNat.ZERO
        for ((negative, m, e) in parts) {
            if (m == 0L) continue
            val term = BigNat.of(m).shl(e - minExp)
            if (negative) neg = neg.plus(term) else pos = pos.plus(term)
        }
        val cmp = pos.compareTo(neg)
        if (cmp == 0) return 0.0
        val magnitude = if (cmp > 0) pos.minus(neg) else neg.minus(pos)
        val result = correctlyRounded(magnitude, minExp, values.size.toLong())
        return if (cmp > 0) result else -result
    }

    /** `statistics.mean` of integers. */
    fun meanOfLongs(values: List<Long>): Double = mean(values.map { it.toDouble() })

    /**
     * Python 3.12's built-in `sum()` over floats, starting from the int 0:
     * the first float is added to 0 plainly, every later one with Neumaier's
     * compensation, and the compensation is folded in at the end.
     */
    fun sum(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var f = 0.0 + values[0]
        var c = 0.0
        for (i in 1 until values.size) {
            val x = values[i]
            val t = f + x
            c += if (abs(f) >= abs(x)) (f - t) + x else (x - t) + f
            f = t
        }
        if (c != 0.0 && !c.isNaN() && !c.isInfinite()) f += c
        return f
    }

    /** `statistics.median` over ints and floats (held as doubles, which they fit). */
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty()) { "no median for empty data" }
        val data = values.sorted()
        val n = data.size
        if (n % 2 == 1) return data[n / 2]
        val i = n / 2
        return (data[i - 1] + data[i]) / 2
    }

    /**
     * `statistics.pstdev` over floats: the exact variance, then a correctly
     * rounded square root — CPython's `_float_sqrt_of_frac`, step for step,
     * because Foster monotony divides by it and rounds to two places.
     */
    fun pstdev(values: List<Double>): Double {
        require(values.isNotEmpty()) { "pstdev requires at least one data point" }
        var minExp = Int.MAX_VALUE
        val parts = values.map { v ->
            require(!v.isNaN() && !v.isInfinite()) { "non-finite pstdev input" }
            val (m, e) = decompose(abs(v))
            if (m != 0L && e < minExp) minExp = e
            Triple(v < 0, m, e)
        }
        if (minExp == Int.MAX_VALUE) return 0.0
        var pos = BigNat.ZERO
        var neg = BigNat.ZERO
        var squares = BigNat.ZERO
        for ((negative, m, e) in parts) {
            if (m == 0L) continue
            val term = BigNat.of(m).shl(e - minExp)
            if (negative) neg = neg.plus(term) else pos = pos.plus(term)
            squares = squares.plus(term.times(term))
        }
        val sum = if (pos >= neg) pos.minus(neg) else neg.minus(pos)
        // Variance = (n·Σx² − (Σx)²) / n², in units of 2^(2·minExp).
        val n = BigNat.of(values.size.toLong())
        val num = squares.times(n).minus(sum.times(sum))
        val den = n.times(n)
        val e2 = 2 * minExp
        return if (e2 >= 0) sqrtOfFrac(num.shl(e2), den) else sqrtOfFrac(num, den.shl(-e2))
    }

    private fun sqrtOfFrac(n: BigNat, m: BigNat): Double {
        if (n.isZero()) return 0.0
        val q = (n.bitLength() - m.bitLength() - (2 * 53 + 3)).floorDiv(2)
        return if (q >= 0) {
            correctlyRounded(isqrtOfFracRto(n, m.shl(2 * q)), q, 1)
        } else {
            correctlyRounded(isqrtOfFracRto(n.shl(-2 * q), m), q, 1)
        }
    }

    /** √(n/m) as an integer, rounded to odd. */
    private fun isqrtOfFracRto(n: BigNat, m: BigNat): BigNat {
        val a = n.divRem(m).first.isqrt()
        return if (a.times(a).times(m) != n && !a.bit(0)) a.plus(BigNat.ONE) else a
    }

    /** `f"{x:.{n}f}"` — the exact value rounded half to even, as Python prints it. */
    fun fixed(x: Double, ndigits: Int): String {
        require(ndigits in 0..9 && !x.isNaN() && !x.isInfinite())
        val negative = x.toRawBits() < 0
        val (m, e) = decompose(abs(x))
        val scaled = BigNat.of(m).times(pow10(ndigits))
        val digits = when {
            m == 0L -> BigNat.ZERO
            e >= 0 -> scaled.shl(e)
            else -> {
                val shift = -e
                var r = scaled.shr(shift)
                val half = scaled.bit(shift - 1)
                if (half && (scaled.anyBitBelow(shift - 1) || r.bit(0))) r = r.plus(BigNat.ONE)
                r
            }
        }.toString().padStart(ndigits + 1, '0')
        val body = if (ndigits == 0) digits else digits.dropLast(ndigits) + "." + digits.takeLast(ndigits)
        return if (negative) "-$body" else body
    }

    /**
     * CPython's `float_divmod`: `(x // y, x % y)` for floats.
     *
     * Not `floor(x / y)`: CPython divides `x - fmod(x, y)` and then corrects,
     * which differs from the naive quotient right at the boundaries — a pace
     * of 359.99999999999994 s must print as 5:59, not 6:00. The modulus takes
     * the divisor's sign, as Python's does.
     */
    fun divmod(x: Double, y: Double): Pair<Double, Double> {
        var mod = x % y // fmod, as C's
        var div = (x - mod) / y
        if (mod != 0.0) {
            if ((y < 0) != (mod < 0)) {
                mod += y
                div -= 1.0
            }
        } else {
            mod = 0.0.withSign(y)
        }
        val floorDiv = if (div != 0.0) {
            var f = kotlin.math.floor(div)
            if (div - f > 0.5) f += 1.0
            f
        } else 0.0.withSign(x / y)
        return floorDiv to mod
    }

    /** Python's `round(x)` with no ndigits: half to even, to an integer. */
    fun roundToLong(x: Double): Long = kotlin.math.round(x).toLong()

    // ── Exact arithmetic ─────────────────────────────────────────────────────

    /** |x| as m·2^e with m an integer below 2^53. */
    private fun decompose(x: Double): Pair<Long, Int> {
        val bits = x.toRawBits()
        val exponent = ((bits ushr 52) and 0x7ff).toInt()
        val fraction = bits and 0xfffffffffffffL
        return if (exponent == 0) {
            fraction to -1074
        } else {
            (fraction or (1L shl 52)) to (exponent - 1075)
        }
    }

    private fun pow10(n: Int): Long {
        var p = 1L
        repeat(n) { p *= 10 }
        return p
    }

    /**
     * The double nearest to numerator·2^exp2 / divisor, ties to even.
     *
     * The quotient is taken to at least 55 significant bits with the remainder
     * kept as a sticky bit, which is all round-to-nearest needs to be exact.
     */
    fun correctlyRounded(numerator: BigNat, exp2: Int, divisor: Long): Double {
        require(divisor > 0 && divisor <= Int.MAX_VALUE.toLong()) { "divisor $divisor" }
        if (numerator.isZero()) return 0.0
        val divisorBits = 64 - divisor.countLeadingZeroBits()
        val shift = maxOf(0, 56 + divisorBits - numerator.bitLength())
        val (q, rem) = numerator.shl(shift).divRem(divisor.toInt())
        var exp = exp2 - shift
        val length = q.bitLength()
        val drop = length - 53
        var kept: Long
        if (drop <= 0) {
            kept = q.toLong()
        } else {
            kept = q.shr(drop).toLong()
            val halfBit = q.bit(drop - 1)
            val belowHalf = q.anyBitBelow(drop - 1) || rem != 0L
            if (halfBit && (belowHalf || (kept and 1L) == 1L)) kept += 1
            exp += drop
            if (kept == 1L shl 53) {
                kept = kept shr 1
                exp += 1
            }
        }
        return scalb(kept.toDouble(), exp)
    }

    /** x·2^exp, exact while the result is a normal double. */
    private fun scalb(x: Double, exp: Int): Double {
        var result = x
        var e = exp
        while (e > 1000) { result *= twoTo(1000); e -= 1000 }
        while (e < -1000) { result *= twoTo(-1000); e += 1000 }
        return result * twoTo(e)
    }

    private fun twoTo(e: Int): Double = Double.fromBits((e + 1023).toLong() shl 52)
}

/**
 * A non-negative arbitrary-precision integer — just enough of one for exact
 * means and rounding. Little-endian 32-bit limbs.
 */
internal class BigNat private constructor(private val limbs: IntArray) : Comparable<BigNat> {

    fun isZero(): Boolean = limbs.isEmpty()

    fun bitLength(): Int =
        if (limbs.isEmpty()) 0 else (limbs.size - 1) * 32 + (32 - limbs.last().countLeadingZeroBits())

    fun bit(index: Int): Boolean {
        if (index < 0) return false
        val limb = index / 32
        if (limb >= limbs.size) return false
        return (limbs[limb] ushr (index % 32)) and 1 == 1
    }

    /** Any bit set strictly below [index]. */
    fun anyBitBelow(index: Int): Boolean {
        if (index <= 0) return false
        val full = index / 32
        for (i in 0 until minOf(full, limbs.size)) if (limbs[i] != 0) return true
        if (full < limbs.size) {
            val rest = index % 32
            if (rest > 0 && (limbs[full] and ((1 shl rest) - 1)) != 0) return true
        }
        return false
    }

    fun toLong(): Long {
        require(bitLength() <= 63) { "BigNat does not fit a Long" }
        var v = 0L
        for (i in limbs.indices.reversed()) v = (v shl 32) or (limbs[i].toLong() and MASK)
        return v
    }

    fun shl(bits: Int): BigNat {
        require(bits >= 0)
        if (isZero() || bits == 0) return this
        val limbShift = bits / 32
        val bitShift = bits % 32
        val out = IntArray(limbs.size + limbShift + 1)
        for (i in limbs.indices) {
            val v = limbs[i].toLong() and MASK
            val shifted = v shl bitShift
            out[i + limbShift] = out[i + limbShift] or shifted.toInt()
            out[i + limbShift + 1] = out[i + limbShift + 1] or (shifted ushr 32).toInt()
        }
        return normalized(out)
    }

    fun shr(bits: Int): BigNat {
        require(bits >= 0)
        if (bits == 0) return this
        val limbShift = bits / 32
        if (limbShift >= limbs.size) return ZERO
        val bitShift = bits % 32
        val out = IntArray(limbs.size - limbShift)
        for (i in out.indices) {
            val lo = limbs[i + limbShift].toLong() and MASK
            val hi = if (i + limbShift + 1 < limbs.size) limbs[i + limbShift + 1].toLong() and MASK else 0L
            out[i] = (((hi shl 32) or lo) ushr bitShift).toInt()
        }
        return normalized(out)
    }

    fun plus(other: BigNat): BigNat {
        val n = maxOf(limbs.size, other.limbs.size) + 1
        val out = IntArray(n)
        var carry = 0L
        for (i in 0 until n) {
            val a = if (i < limbs.size) limbs[i].toLong() and MASK else 0L
            val b = if (i < other.limbs.size) other.limbs[i].toLong() and MASK else 0L
            val s = a + b + carry
            out[i] = s.toInt()
            carry = s ushr 32
        }
        return normalized(out)
    }

    /** this − other; requires this ≥ other. */
    fun minus(other: BigNat): BigNat {
        require(this >= other) { "negative BigNat" }
        val out = IntArray(limbs.size)
        var borrow = 0L
        for (i in limbs.indices) {
            val a = limbs[i].toLong() and MASK
            val b = if (i < other.limbs.size) other.limbs[i].toLong() and MASK else 0L
            var d = a - b - borrow
            borrow = if (d < 0) { d += 1L shl 32; 1L } else 0L
            out[i] = d.toInt()
        }
        return normalized(out)
    }

    fun times(small: Long): BigNat {
        require(small >= 0 && small <= Int.MAX_VALUE.toLong())
        if (small == 0L || isZero()) return ZERO
        val out = IntArray(limbs.size + 2)
        var carry = 0L
        for (i in limbs.indices) {
            val p = (limbs[i].toLong() and MASK) * small + carry
            out[i] = p.toInt()
            carry = p ushr 32
        }
        out[limbs.size] = carry.toInt()
        out[limbs.size + 1] = (carry ushr 32).toInt()
        return normalized(out)
    }

    fun times(other: BigNat): BigNat {
        if (isZero() || other.isZero()) return ZERO
        val out = LongArray(limbs.size + other.limbs.size + 1)
        for (i in limbs.indices) {
            val a = limbs[i].toLong() and MASK
            var carry = 0L
            for (j in other.limbs.indices) {
                val b = other.limbs[j].toLong() and MASK
                // a·b can reach 2^64 − 2^33 + 1: split a so no product overflows.
                val lo = (a and 0xffff) * b
                val hi = (a ushr 16) * b
                val cur = out[i + j] + (lo and MASK) + ((hi and 0xffff) shl 16) + carry
                out[i + j] = cur and MASK
                carry = (cur ushr 32) + (lo ushr 32) + (hi ushr 16)
            }
            var k = i + other.limbs.size
            while (carry != 0L) {
                val cur = out[k] + carry
                out[k] = cur and MASK
                carry = cur ushr 32
                k++
            }
        }
        return normalized(IntArray(out.size) { out[it].toInt() })
    }

    /** Quotient and remainder by another BigNat — binary long division. */
    fun divRem(divisor: BigNat): Pair<BigNat, BigNat> {
        require(!divisor.isZero())
        if (this < divisor) return ZERO to this
        val q = IntArray(limbs.size)
        var r = ZERO
        for (i in bitLength() - 1 downTo 0) {
            r = r.shl(1)
            if (bit(i)) r = r.plus(ONE)
            if (r >= divisor) {
                r = r.minus(divisor)
                q[i / 32] = q[i / 32] or (1 shl (i % 32))
            }
        }
        return normalized(q) to r
    }

    /** ⌊√this⌋ by Newton's method from above. */
    fun isqrt(): BigNat {
        if (isZero()) return ZERO
        var x = ONE.shl((bitLength() + 1) / 2)
        while (true) {
            val y = x.plus(divRem(x).first).shr(1)
            if (y >= x) return x
            x = y
        }
    }

    override fun toString(): String {
        if (isZero()) return "0"
        val chunks = ArrayList<Long>()
        var cur = this
        while (!cur.isZero()) {
            val (q, r) = cur.divRem(1_000_000_000)
            chunks.add(r)
            cur = q
        }
        val sb = StringBuilder(chunks.last().toString())
        for (i in chunks.size - 2 downTo 0) sb.append(chunks[i].toString().padStart(9, '0'))
        return sb.toString()
    }

    /** Quotient and remainder by a positive Int. */
    fun divRem(divisor: Int): Pair<BigNat, Long> {
        require(divisor > 0)
        val d = divisor.toLong()
        val out = IntArray(limbs.size)
        var rem = 0L
        for (i in limbs.indices.reversed()) {
            val cur = (rem shl 32) or (limbs[i].toLong() and MASK)
            out[i] = (cur / d).toInt()
            rem = cur % d
        }
        return normalized(out) to rem
    }

    override fun compareTo(other: BigNat): Int {
        if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
        for (i in limbs.indices.reversed()) {
            val a = limbs[i].toLong() and MASK
            val b = other.limbs[i].toLong() and MASK
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is BigNat && compareTo(other) == 0

    override fun hashCode(): Int = limbs.contentHashCode()

    companion object {
        private const val MASK = 0xffffffffL
        val ZERO = BigNat(IntArray(0))
        val ONE = of(1)

        fun of(value: Long): BigNat {
            require(value >= 0)
            return normalized(intArrayOf(value.toInt(), (value ushr 32).toInt()))
        }

        private fun normalized(limbs: IntArray): BigNat {
            var n = limbs.size
            while (n > 0 && limbs[n - 1] == 0) n--
            return BigNat(if (n == limbs.size) limbs else limbs.copyOf(n))
        }
    }
}
