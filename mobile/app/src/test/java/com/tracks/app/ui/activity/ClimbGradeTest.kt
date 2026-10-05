// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two ladders, one number.
 *
 * The watch records a grade as an index, and which ladder that index is on is
 * decided by the sport rather than by the number — so the same 10 is V10 on a
 * mat and 5.10a on a rope. Rendering every grade as V was not a formatting
 * slip: it reported a gym session at roughly four grades harder than it was.
 */
class ClimbGradeTest {

    @Test
    fun `a bouldering grade is the index itself`() {
        assertEquals("V0", climbGradeLabel(0, GradeScale.V))
        assertEquals("V8", climbGradeLabel(8, GradeScale.V))
    }

    @Test
    fun `YDS runs whole numbers up to 5_9`() {
        assertEquals("5.0", climbGradeLabel(0, GradeScale.YDS))
        assertEquals("5.7", climbGradeLabel(7, GradeScale.YDS))
        assertEquals("5.9", climbGradeLabel(9, GradeScale.YDS))
    }

    @Test
    fun `YDS takes letters from 5_10 onwards`() {
        // The letters are the point: 5.10a and 5.10d are three grades apart,
        // and a table that renders both as "5.10" throws that away.
        assertEquals("5.10a", climbGradeLabel(10, GradeScale.YDS))
        assertEquals("5.10b", climbGradeLabel(11, GradeScale.YDS))
        assertEquals("5.10d", climbGradeLabel(13, GradeScale.YDS))
        assertEquals("5.11a", climbGradeLabel(14, GradeScale.YDS))
        assertEquals("5.12a", climbGradeLabel(18, GradeScale.YDS))
        assertEquals("5.15d", climbGradeLabel(33, GradeScale.YDS))
    }

    @Test
    fun `a grade past the end of the ladder still reads as a grade`() {
        // Nobody has climbed it, but a corrupt index must not crash a card.
        assertEquals("5.16a", climbGradeLabel(34, GradeScale.YDS))
    }
}
