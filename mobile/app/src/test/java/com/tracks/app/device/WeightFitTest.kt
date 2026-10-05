// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A weigh-in the watch will accept, and a typo it will not receive.
 *
 * The file goes to a device that uses the number in its own arithmetic — a
 * watch's calorie model runs on body mass — so a mistyped figure is not a
 * cosmetic problem there the way it is on a phone screen: it would be
 * corrected only by four levels of menu on the device itself.
 */
class WeightFitTest {

    private val at = 1_756_000_000L

    @Test
    fun `a weigh-in encodes to a readable FIT file`() {
        val bytes = WeightFit.build(82.5, at)
        assertNotNull(bytes)
        // Decoded by the same FIT reader the phone imports with, which is the
        // only check that the bytes are a FIT file rather than merely non-empty
        // (the reader throws on a bad header or CRC).
        val messages = com.tracks.core.fit.decode.FitReader(bytes!!).messages().toList()
        assertTrue(messages.isNotEmpty())
        assertTrue(bytes.size > 20)
    }

    @Test
    fun `the same weigh-in encodes to the same bytes`() {
        // The watch de-duplicates on file identity, so a retried push must not
        // land twice. Both the serial and the name come from the instant.
        assertTrue(WeightFit.build(82.5, at)!!.contentEquals(WeightFit.build(82.5, at)!!))
        assertEquals(WeightFit.filename(at), WeightFit.filename(at))
    }

    @Test
    fun `a different instant is a different file`() {
        assertTrue(WeightFit.filename(at) != WeightFit.filename(at + 1))
    }

    @Test
    fun `a figure no scale would report is refused`() {
        // Fat-fingered grams, or a field left holding a step count.
        assertNull(WeightFit.build(1750.0, at))
        assertNull(WeightFit.build(0.0, at))
        assertNull(WeightFit.build(-5.0, at))
    }

    @Test
    fun `the extremes of a person are still a person`() {
        assertNotNull(WeightFit.build(40.0, at))
        assertNotNull(WeightFit.build(200.0, at))
    }
}
