// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.replica.SyncedRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AnimationRecapsTest {

    private fun p(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> error("unsupported")
    }

    private fun row(entity: String, uid: String, vararg f: Pair<String, Any?>) =
        SyncedRow(entity, uid, f.associate { it.first to p(it.second) })

    private fun step(cat: String, sub: Int, name: String) =
        JsonObject(mapOf("garmin_category" to p(cat), "garmin_subtype" to p(sub), "name" to p(name)))

    private fun workout(uid: String, vararg extra: Pair<String, Any?>) = SyncedRow(
        "planned_workout", uid,
        mapOf(
            "workout_type" to p("strength"), "title" to p("Upper"), "scheduled_date" to p("2026-09-20"),
            "watch_uploaded_at" to p("2026-09-19T08:00:00Z"),
            "steps" to JsonArray(listOf(step("squat", 37, "Goblet Squat"), step("row", 3, "Row"), step("row", 3, "Row"))),
        ) + extra.associate { it.first to p(it.second) },
    )

    /** One question per animation, not per set — the row appears twice in the workout. */
    @Test
    fun `a done workout that went to the watch asks once per unconfirmed animation`() {
        val recaps = AnimationRecaps.pending(listOf(workout("w1")), emptyList(), 3288L, "2026-09-25")
        assertEquals(listOf("Goblet Squat", "Row"), recaps.single().asks.map { it.name })
    }

    /** An animation already confirmed for this watch model is not asked again. */
    @Test
    fun `an answered animation is not asked again`() {
        val confirmed = row("animation_confirmation", "a", "product_id" to 3288, "garmin_category" to "row",
            "garmin_subtype" to 3, "animates" to true)
        val recaps = AnimationRecaps.pending(listOf(workout("w1")), listOf(confirmed), 3288L, "2026-09-25")
        assertEquals(listOf("Goblet Squat"), recaps.single().asks.map { it.name })
    }

    @Test
    fun `a workout never sent to the watch or already recapped is not asked about`() {
        val neverSent = workout("w1", "watch_uploaded_at" to null)
        val recapped = workout("w2", "recap_completed_at" to "2026-09-21T10:00:00Z")
        assertEquals(emptyList(), AnimationRecaps.pending(listOf(neverSent, recapped), emptyList(), 3288L, "2026-09-25"))
    }

    /** Asking before the workout is done would be asking about something not yet seen. */
    @Test
    fun `a workout still to come is not asked about until it is done`() {
        val future = workout("w1", "scheduled_date" to "2026-09-30")
        assertEquals(emptyList(), AnimationRecaps.pending(listOf(future), emptyList(), 3288L, "2026-09-25"))
        val tickedEarly = workout("w1", "scheduled_date" to "2026-09-30", "is_complete" to true)
        assertEquals(1, AnimationRecaps.pending(listOf(tickedEarly), emptyList(), 3288L, "2026-09-25").size)
    }

    @Test
    fun `no watch model means nothing to ask`() {
        assertEquals(emptyList(), AnimationRecaps.pending(listOf(workout("w1")), emptyList(), null, "2026-09-25"))
    }
}
