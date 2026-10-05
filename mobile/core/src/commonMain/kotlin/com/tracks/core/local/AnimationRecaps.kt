// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.replica.SyncedRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One Garmin animation to ask about: the (category, subtype) pair and a name to show. */
data class AnimationAsk(val category: String, val subtype: Int, val name: String)

/** A workout that went to the watch, done, with animations nobody has confirmed yet. */
data class AnimationRecap(val workoutUid: String, val title: String, val asks: List<AnimationAsk>)

/**
 * The post-workout animation question, as the web's WorkoutRecapModal asks it.
 *
 * After a strength or stretching workout that was pushed to the watch has been
 * done (ticked off, or its day has passed), ask once — per exercise whose
 * animation is not yet confirmed for this watch model — whether it animated.
 * That is when the answer is known; the ✓/✗ that used to sit on every library
 * row asked it at the wrong moment, and users read them as something else.
 *
 * The answers are ordinary synced rows (animation_confirmation, one per watch
 * model and animation), and finishing marks the workout's recap done
 * (planned_workout.recap_completed_at), so no other device asks again either.
 */
object AnimationRecaps {

    private val kinds = setOf("strength", "mobility", "flexibility")

    /** What to ask about, oldest first. [today] is an ISO date. Pure. */
    fun pending(
        workouts: List<SyncedRow>,
        confirmations: List<SyncedRow>,
        productId: Long?,
        today: String,
    ): List<AnimationRecap> {
        if (productId == null) return emptyList()
        val answered = confirmations
            .filter { !it.isTombstone && it.num("product_id") == productId }
            .mapNotNull { r -> r.text("garmin_category")?.let { it to (r.num("garmin_subtype") ?: -1L) } }
            .toSet()
        return workouts.asSequence()
            .filter { !it.isTombstone && it.text("workout_type") in kinds }
            .filter { it.text("watch_uploaded_at") != null && it.text("recap_completed_at") == null }
            .filter { it.flag("is_complete") == true || (it.text("scheduled_date") ?: "9999") < today }
            .sortedBy { it.text("scheduled_date") }
            .mapNotNull { w ->
                val asks = asks(w)
                    .filter { (it.category to it.subtype.toLong()) !in answered }
                    .distinctBy { it.category to it.subtype }
                if (asks.isEmpty()) null else AnimationRecap(w.uid, w.text("title") ?: "Workout", asks)
            }
            .toList()
    }

    /** The primary watch's product id — the model a confirmation is about. */
    fun productId(devices: List<SyncedRow>): Long? {
        val live = devices.filter { !it.isTombstone }
        val primary = live.firstOrNull { it.flag("is_primary") == true } ?: live.firstOrNull()
        return primary?.num("product_id")
    }

    suspend fun load(sources: LocalSources, today: String): List<AnimationRecap> {
        val replica = sources.replica
        return pending(
            replica.rows("planned_workout"),
            replica.rows("animation_confirmation"),
            productId(replica.rows("device")),
            today,
        )
    }

    /**
     * Record the answers and close the recap. [answers] holds only the asks
     * the user answered; "not sure" writes nothing.
     */
    suspend fun answer(sources: LocalSources, recap: AnimationRecap, answers: Map<AnimationAsk, Boolean>, nowIso: String) {
        val product = productId(sources.replica.rows("device"))
        if (product != null) {
            for ((ask, animates) in answers) {
                sources.createValues(
                    "animation_confirmation",
                    mapOf(
                        "product_id" to product, "garmin_category" to ask.category,
                        "garmin_subtype" to ask.subtype, "animates" to animates,
                    ),
                    keyValues = mapOf(
                        "product_id" to product.toString(), "garmin_category" to ask.category,
                        "garmin_subtype" to ask.subtype.toString(),
                    ),
                )
            }
        }
        sources.setValues("planned_workout", sources.idOf(recap.workoutUid), mapOf("recap_completed_at" to nowIso))
    }

    private fun asks(w: SyncedRow): List<AnimationAsk> =
        (w.fields["steps"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val cat = (o["garmin_category"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val sub = (o["garmin_subtype"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            AnimationAsk(cat, sub, (o["name"] as? JsonPrimitive)?.contentOrNull ?: cat)
        }

    private fun SyncedRow.text(f: String) = (fields[f] as? JsonPrimitive)?.contentOrNull
    private fun SyncedRow.num(f: String) = text(f)?.toDoubleOrNull()?.toLong()
    private fun SyncedRow.flag(f: String) = (fields[f] as? JsonPrimitive)?.booleanOrNull
}
