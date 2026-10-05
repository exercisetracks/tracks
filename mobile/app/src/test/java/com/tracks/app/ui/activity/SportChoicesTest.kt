// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportChoicesTest {

    /**
     * A choice the taxonomy files under "other" would re-sport a ride into
     * the one bucket with no layout — the correction would make things worse.
     */
    @Test
    fun every_choice_but_the_catch_all_lands_on_a_real_sport_type() {
        val stray = SPORT_CHOICES.filter { it.sport != "generic" && it.type == "other" }
        assertTrue("choices the taxonomy files as other: $stray", stray.isEmpty())
    }

    /** Two choices with the same label are one choice the user cannot tell apart. */
    @Test
    fun no_two_choices_read_the_same() {
        val labels = SPORT_CHOICES.map { it.label }
        assertEquals("duplicate labels: $labels", labels.size, labels.toSet().size)
    }
}
