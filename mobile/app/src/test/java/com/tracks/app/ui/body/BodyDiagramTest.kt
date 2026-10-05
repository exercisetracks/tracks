// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import com.tracks.core.spec.BODY_VIEWER_REGIONS
import com.tracks.core.spec.MALE_BACK
import com.tracks.core.spec.MALE_FRONT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reduction from muscle activation to shaded shapes.
 *
 * Worth its own tests because the artwork is coarser than the vocabulary, so
 * this is a lossy collapse with a judgement in it — and because the failure
 * mode is a diagram that looks plausible while showing the wrong thing, which
 * no amount of running the app catches.
 */
class BodyDiagramTest {

    @Test
    fun `shades a part from the muscle behind it`() {
        val fills = partFills(mapOf("chest" to 0.8f), BodyView.Front)
        assertEquals(0.8f, fills["chest"])
    }

    @Test
    fun `a shape shared by several muscles takes the strongest`() {
        // One `quadriceps` shape covers quads and hip flexors. Taking the
        // weakest would make a hard leg day read as an easy one.
        val fills = partFills(
            mapOf("quads" to 0.9f, "hip_flexors" to 0.2f),
            BodyView.Front,
        )
        assertEquals(0.9f, fills["quadriceps"])
    }

    @Test
    fun `deltoids shade from the front on the front and the rear on the back`() {
        // The artwork has one deltoid shape per view and Tracks measures three
        // deltoid groups. Which one a view reads is the whole distinction.
        val front = partFills(
            mapOf("front_delts" to 0.7f, "rear_delts" to 0.1f),
            BodyView.Front,
        )
        val back = partFills(
            mapOf("front_delts" to 0.7f, "rear_delts" to 0.1f),
            BodyView.Back,
        )
        assertEquals(0.7f, front["deltoids"])
        assertEquals(0.1f, back["deltoids"])
    }

    @Test
    fun `the neck shades on the back view too`() {
        // It did not, in the implementation this was ported from: the back
        // neck regions had no pairing entry and looked up an activation key
        // nothing produces. Corrected in spec/muscle_groups.yaml.
        val fills = partFills(mapOf("neck" to 0.6f), BodyView.Back)
        assertEquals(0.6f, fills["neck"])
    }

    @Test
    fun `parts with no muscle behind them stay unshaded`() {
        val fills = partFills(mapOf("chest" to 1f), BodyView.Front)
        // Present in the artwork, absent from the mapping — and the renderer
        // paints null as neutral rather than as the cold end of the scale.
        assertTrue("head" in MALE_FRONT.parts)
        assertNull(fills["head"])
    }

    @Test
    fun `a selected muscle pins to the top of the scale`() {
        val fills = partFills(emptyMap(), BodyView.Front, selected = setOf("chest"))
        assertEquals(1f, fills["chest"])
    }

    /**
     * Every slug the mapping names must exist in the artwork it names it for.
     * A typo here draws nothing at all — the muscle simply never shades, with
     * no error anywhere.
     */
    @Test
    fun `every mapped slug exists in the artwork for its view`() {
        for ((region, mapping) in BODY_VIEWER_REGIONS) {
            val art = if (mapping.view == "front") MALE_FRONT else MALE_BACK
            assertTrue(
                mapping.slug in art.parts,
                "$region maps to '${mapping.slug}' on the ${mapping.view}, " +
                    "which that body does not draw",
            )
        }
    }
}
