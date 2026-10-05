// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.ui.graphics.Color
import com.tracks.app.ui.dashboard.zoneFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arc that carries a total *and* its parts.
 *
 * Two dials depend on this being right in the same way: the calorie ring is a
 * resting burn plus what was earned, and the sleep ring is deep plus REM plus
 * light. Both draw the parts end to end and both put the total in the middle,
 * so a band that is off by its own width is a dial whose centre number does not
 * match its own fill — which nobody reports as a bug because it just looks
 * slightly wrong.
 */
class StackedScaleTest {

    private val red = Color.Red
    private val green = Color.Green
    private val blue = Color.Blue
    private val grey = Color.Gray

    private fun part(label: String, amount: Double, color: Color = red) =
        MetricScale.Stacked.Part(label, amount, color)

    @Test
    fun `parts sit end to end from zero`() {
        val zones = stackedZones(
            MetricScale.Stacked(
                listOf(part("Deep", 2.0, red), part("REM", 1.5, green), part("Light", 3.0, blue)),
                target = 8.0,
            ),
            grey,
        )

        assertEquals(listOf("Deep", "REM", "Light", "To goal"), zones.map { it.label })
        assertEquals(0.0, zones[0].min, 1e-9)
        assertEquals(2.0, zones[0].max, 1e-9)
        assertEquals(2.0, zones[1].min, 1e-9)
        assertEquals(3.5, zones[1].max, 1e-9)
        assertEquals(3.5, zones[2].min, 1e-9)
        assertEquals(6.5, zones[2].max, 1e-9)
    }

    /** The fill stops at the total, so the parts must end exactly there. */
    @Test
    fun `the parts end where the total does`() {
        val total = 2.0 + 1.5 + 3.0
        val zones = stackedZones(
            MetricScale.Stacked(
                listOf(part("Deep", 2.0), part("REM", 1.5), part("Light", 3.0)),
                target = 8.0,
            ),
            grey,
        )
        assertEquals(total, zones.first { it.label == "To goal" }.min, 1e-9)
    }

    @Test
    fun `the remainder runs to the goal and takes its own colour`() {
        val zones = stackedZones(
            MetricScale.Stacked(listOf(part("Resting", 1500.0)), target = 2000.0, remainderColor = green),
            grey,
        )
        val gap = zones.last()
        assertEquals("To goal", gap.label)
        assertEquals(1500.0, gap.min, 1e-9)
        assertEquals(2000.0, gap.max, 1e-9)
        assertEquals(green, gap.color)
    }

    @Test
    fun `no remainder colour falls back to the caller's`() {
        val zones = stackedZones(
            MetricScale.Stacked(listOf(part("Deep", 1.0)), target = 8.0),
            grey,
        )
        assertEquals(grey, zones.last().color)
    }

    /** A goal beaten ends on the last part rather than trailing dead arc. */
    @Test
    fun `past the goal there is no remainder band`() {
        val zones = stackedZones(
            MetricScale.Stacked(
                listOf(part("Deep", 3.0), part("REM", 3.0), part("Light", 3.0)),
                target = 8.0,
            ),
            grey,
        )
        assertEquals(listOf("Deep", "REM", "Light"), zones.map { it.label })
        assertEquals(9.0, zones.last().max, 1e-9)
    }

    /**
     * A night with no REM should not put a REM band on the arc — and more to
     * the point, [zoneFor] hands back the first band whose max exceeds the
     * value, so a zero-width band would be unreachable and yet selectable.
     */
    @Test
    fun `empty parts are dropped`() {
        val zones = stackedZones(
            MetricScale.Stacked(
                listOf(part("Deep", 2.0), part("REM", 0.0), part("Light", 1.0)),
                target = 8.0,
            ),
            grey,
        )
        assertEquals(listOf("Deep", "Light", "To goal"), zones.map { it.label })
        assertEquals(2.0, zones[1].min, 1e-9)
        assertEquals(3.0, zones[1].max, 1e-9)
    }

    /** An arc with no extent draws nothing, which reads as a broken dial. */
    @Test
    fun `nothing measured still yields a drawable arc`() {
        val zones = stackedZones(MetricScale.Stacked(emptyList(), target = 8.0), grey)
        assertEquals(1, zones.size)
        assertTrue("arc has extent", zones.first().max > zones.first().min)
    }

    @Test
    fun `a zero target still yields a drawable arc`() {
        val zones = stackedZones(MetricScale.Stacked(emptyList(), target = 0.0), grey)
        assertTrue("arc has extent", zones.first().max > zones.first().min)
    }

    /** Every part is fully lit at the total, which is what the dial fills to. */
    @Test
    fun `the remainder can name itself`() {
        val zones = stackedZones(
            MetricScale.Stacked(
                listOf(part("Just existing", 1500.0)),
                target = 2000.0,
                remainderLabel = "Still to earn",
            ),
            grey,
        )
        assertEquals("Still to earn", zones.last().label)
    }

    /**
     * The word beside the figure. Read off the stacked bands it produced
     * "1,552 kcal — Still to earn", which is not a verdict about anything;
     * these bands are what it reads instead, and they have to sit on the same
     * scale the dial shows, which starts at the day's resting burn.
     */
    @Test
    fun `the day's verdict is measured from resting, not from zero`() {
        val resting = 1_529.0
        val zones = activityZones(resting)

        assertEquals("Sedentary", zoneFor(resting + 23.0, zones).label)
        assertEquals("Light", zoneFor(resting + 200.0, zones).label)
        assertEquals("Active", zoneFor(resting + 400.0, zones).label)
        assertEquals("Goal met", zoneFor(resting + ACTIVE_CALORIE_GOAL, zones).label)
    }

    @Test
    fun `the verdict turns on the same goal the arc does`() {
        val zones = activityZones(0.0)
        assertEquals(ACTIVE_CALORIE_GOAL, zones.first { it.label == "Goal met" }.min, 1e-9)
    }

    /** Bands with a hole in them would hand back a neutral zone mid-scale. */
    @Test
    fun `the verdict bands are contiguous`() {
        activityZones(1_000.0).zipWithNext { a, b ->
            assertEquals("bands meet at ${a.label}/${b.label}", a.max, b.min, 1e-9)
        }
    }

    @Test
    fun `a day past every band still lands somewhere`() {
        assertEquals("Goal met", zoneFor(99_999.0, activityZones(1_000.0)).label)
    }

    @Test
    fun `the total falls in the remainder rather than in a part`() {
        val zones = stackedZones(
            MetricScale.Stacked(
                listOf(part("Deep", 2.0), part("REM", 1.5), part("Light", 3.0)),
                target = 8.0,
            ),
            grey,
        )
        assertEquals("To goal", zoneFor(6.5, zones).label)
    }
}
