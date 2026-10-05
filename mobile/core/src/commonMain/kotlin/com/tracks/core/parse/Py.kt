// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.fit.decode.FitTimeOfDay

/**
 * Python's built-in conversions, as the server's parsers rely on them.
 *
 * The parsers are dynamically typed and lean on that: `as_int` turns an enum
 * name into None because `int("running")` raises; `float(x)` of a tuple fails
 * an import outright; `x or y` skips a zero. A FIT value can be any of a
 * handful of runtime types (see [com.tracks.core.fit.decode.FitValue]), and
 * which one it is depends on the file, so the port cannot assume. These
 * helpers reproduce what each builtin does with each type — including which
 * failures are caught where. `int()` of an infinity raises `OverflowError`,
 * which the parsers' `except (TypeError, ValueError)` does *not* catch, so it
 * is thrown as [PyOverflow] and deliberately escapes [intOrNull].
 */
internal open class PyError(message: String) : Exception(message)

/** Python's `OverflowError`: not a TypeError or ValueError, so the parsers' guards let it through. */
internal class PyOverflow(message: String) : PyError(message)

internal object Py {

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Long -> v != 0L
        is Int -> v != 0
        is Double -> v != 0.0
        is ULong -> v != 0UL
        is String -> v.isNotEmpty()
        is List<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** Python's `a or b`. */
    fun or(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    fun isNumber(v: Any?): Boolean = v is Long || v is Double || v is Int || v is ULong || v is Boolean

    /** A number as a double, for arithmetic. Anything else is the TypeError Python would raise. */
    fun num(v: Any?): Double = when (v) {
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Double -> v
        is ULong -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> throw PyError("TypeError: unsupported operand type ${typeName(v)}")
    }

    /** Python's `int(v)`. */
    fun int(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is ULong -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        is Double -> {
            if (v.isNaN()) throw PyError("ValueError: cannot convert float NaN to integer")
            if (v.isInfinite()) throw PyOverflow("OverflowError: cannot convert float infinity to integer")
            kotlin.math.truncate(v).toLong()
        }
        is String -> parseIntLiteral(v) ?: throw PyError("ValueError: invalid literal for int(): '$v'")
        else -> throw PyError("TypeError: int() argument must be a string or a number, not ${typeName(v)}")
    }

    /** `try: int(v) except (TypeError, ValueError): None`. */
    fun intOrNull(v: Any?): Long? = try {
        int(v)
    } catch (e: PyOverflow) {
        throw e
    } catch (e: PyError) {
        null
    }

    /** Python's `float(v)`. */
    fun float(v: Any?): Double = when (v) {
        is Long, is Int, is Double, is ULong, is Boolean -> num(v)
        is String -> parseFloatLiteral(v) ?: throw PyError("ValueError: could not convert string to float: '$v'")
        else -> throw PyError("TypeError: float() argument must be a string or a real number, not ${typeName(v)}")
    }

    /** `try: float(v) except (TypeError, ValueError): None`. */
    fun floatOrNull(v: Any?): Double? = try {
        float(v)
    } catch (e: PyError) {
        null
    }

    /** The server's `utils.as_str`. Enum values are already names, so this is `str(v)`. */
    fun asStr(v: Any?): String? = if (v == null) null else str(v)

    /** The server's `utils.as_int`: `int(v)`, or None when that raises TypeError/ValueError. */
    fun asInt(v: Any?): Long? = if (v == null) null else intOrNull(v)

    /** `str.isdigit()`: non-empty and every character a digit. */
    fun isDigit(s: String): Boolean = s.isNotEmpty() && s.all { it.isDigit() }

    /**
     * `str.title()`: the first cased character after any uncased one is
     * upper-cased, every other cased character lower-cased. So `3_way squat`
     * becomes `3_Way Squat` — a digit or underscore starts a new word.
     */
    fun title(s: String): String {
        val out = StringBuilder(s.length)
        var previousCased = false
        for (c in s) {
            val cased = c.isUpperCase() || c.isLowerCase() || c.isTitleCase()
            out.append(
                if (!cased) c else if (previousCased) c.lowercaseChar() else c.titlecaseChar(),
            )
            previousCased = cased
        }
        return out.toString()
    }

    /** Python's `str(v)`. */
    fun str(v: Any?): String = when (v) {
        null -> "None"
        is String -> v
        is Boolean -> if (v) "True" else "False"
        is Long, is Int, is ULong -> v.toString()
        is Double -> floatRepr(v)
        is FitDateTime -> v.isoformat().replaceFirst('T', ' ')
        is CivilDate -> v.isoformat()
        is FitTimeOfDay -> v.isoformat()
        is List<*> -> when (v.size) {
            0 -> "()"
            1 -> "(${repr(v[0])},)"
            else -> v.joinToString(", ", "(", ")") { repr(it) }
        }
        else -> v.toString()
    }

    private fun repr(v: Any?): String = when (v) {
        is String -> if (v.contains('\'') && !v.contains('"')) "\"$v\"" else "'" + v.replace("'", "\\'") + "'"
        is FitDateTime, is CivilDate, is FitTimeOfDay -> "<${str(v)}>"
        else -> str(v)
    }

    /**
     * `repr(float)`: the shortest round-tripping digits, positional between
     * 1e-4 and 1e16 and scientific (`1e+16`) outside it.
     */
    fun floatRepr(d: Double): String {
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        // Kotlin's toString is the shortest round-trip form (JDK 19+); only its
        // layout differs from Python's. Extract digits and exponent from it.
        val text = d.toString()
        val negative = text.startsWith('-')
        val body = text.removePrefix("-")
        val mantissa = body.substringBefore('E')
        val exp = if (body.contains('E')) body.substringAfter('E').toInt() else 0
        val intPart = mantissa.substringBefore('.')
        val fracPart = mantissa.substringAfter('.', "")
        var digits = (intPart + fracPart).trimStart('0')
        val leadingZeros = (intPart + fracPart).length - (intPart + fracPart).trimStart('0').length
        val decimalExp = intPart.length + exp - leadingZeros // position of the decimal point in `digits`
        digits = digits.trimEnd('0').ifEmpty { "0" }
        val sci = decimalExp - 1
        val result = if (sci < -4 || sci >= 16) {
            val m = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            val sign = if (sci < 0) "-" else "+"
            m + "e" + sign + kotlin.math.abs(sci).toString().padStart(2, '0')
        } else if (decimalExp <= 0) {
            "0." + "0".repeat(-decimalExp) + digits
        } else if (decimalExp >= digits.length) {
            digits + "0".repeat(decimalExp - digits.length) + ".0"
        } else {
            digits.substring(0, decimalExp) + "." + digits.substring(decimalExp)
        }
        return if (negative) "-$result" else result
    }

    private fun parseIntLiteral(s: String): Long? {
        val t = s.trim()
        val body = t.removePrefix("+").removePrefix("-")
        if (body.isEmpty() || body.startsWith('_') || body.endsWith('_') || body.contains("__")) return null
        val digits = body.replace("_", "")
        if (!digits.all { it in '0'..'9' }) return null
        return (if (t.startsWith('-')) "-$digits" else digits).toLongOrNull()
    }

    private val floatLiteral = Regex("[+-]?((\\d+(_\\d+)*)?\\.?\\d+(_\\d+)*|\\d+(_\\d+)*\\.)([eE][+-]?\\d+(_\\d+)*)?")

    private fun parseFloatLiteral(s: String): Double? {
        val t = s.trim()
        when (t.lowercase().removePrefix("+")) {
            "inf", "infinity" -> return Double.POSITIVE_INFINITY
            "nan" -> return Double.NaN
        }
        when (t.lowercase()) {
            "-inf", "-infinity" -> return Double.NEGATIVE_INFINITY
            "-nan" -> return Double.NaN
        }
        if (!floatLiteral.matches(t)) return null
        return t.replace("_", "").toDoubleOrNull()
    }

    private fun typeName(v: Any?): String = when (v) {
        null -> "NoneType"
        is List<*> -> "tuple"
        is String -> "str"
        is FitDateTime -> "datetime"
        else -> v::class.simpleName ?: "object"
    }
}
