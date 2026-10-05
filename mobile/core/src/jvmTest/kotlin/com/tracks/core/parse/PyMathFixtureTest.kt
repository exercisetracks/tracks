// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * [PyMath] agrees with CPython to the bit.
 *
 * Every parser output that is a rounded or averaged float goes through one of
 * these, and each is a place the obvious Kotlin is wrong: `round(2.675, 2)` is
 * 2.67 in Python and 2.68 from scale-and-round; a running double sum drifts
 * from `statistics.mean`; `sum()` compensates since Python 3.12. Expected
 * values are Python's own, from `spec/fixtures/py_math.json`.
 */
class PyMathFixtureTest {

    private val fixture = Canonical.parseJson(File(Canonical.root, "spec/fixtures/py_math.json")) as Map<*, *>

    private fun f(tagged: Any?): Double =
        java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong((tagged as Map<*, *>)["\$f"] as String, 16))

    private fun same(a: Double, b: Double) = a.toRawBits() == b.toRawBits()

    @Test
    fun round_to_n_digits_matches_python_including_binary_ties() {
        val failures = ArrayList<String>()
        for (case in fixture["round"] as List<*>) {
            case as Map<*, *>
            val x = f(case["x"])
            val n = (case["n"] as Canonical.NumberText).text.toInt()
            val got = PyMath.round(x, n)
            if (!same(got, f(case["expect"]))) failures += "round($x, $n) = $got, python ${f(case["expect"])}"
        }
        if (failures.isNotEmpty()) fail(failures.take(20).joinToString("\n"))
    }

    @Test
    fun sum_mean_and_median_match_python() {
        val failures = ArrayList<String>()
        for ((i, case) in (fixture["aggregates"] as List<*>).withIndex()) {
            case as Map<*, *>
            val xs = (case["xs"] as List<*>).map(::f)
            val checks = listOf(
                "sum" to PyMath.sum(xs),
                "mean" to PyMath.mean(xs),
                "median" to PyMath.median(xs),
            )
            for ((name, got) in checks) {
                if (!same(got, f(case[name]))) failures += "#$i $name: $got, python ${f(case[name])}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.take(20).joinToString("\n"))
    }

    @Test
    fun the_textbook_cases_are_in_the_corpus() {
        assertEquals(2.67, PyMath.round(2.675, 2))
        assertEquals(0.12, PyMath.round(0.125, 2))
        assertEquals(1.0, PyMath.sum(List(10) { 0.1 }))
        assertEquals(1.0, PyMath.sum(listOf(1e16, 1.0, -1e16)))
    }
}
