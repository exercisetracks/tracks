// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.metrics

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.local.LocalImporter
import com.tracks.core.spec.SpecFixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays `spec/fixtures/hr_review.json` — the server's heart-rate review over
 * seeded synthetic recordings, clean and with every failure it looks for —
 * through the phone's port, then through the phone importer's hrTSS.
 *
 * Exact equality: the reviewed average feeds a load rounded to one decimal, so
 * a last-bit difference would sooner or later be a different load on the
 * phone than on the server for the same file.
 */
class HrReviewFixtureTest {

    private val corpus = SpecFixtures.load("hr_review")

    private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
    private fun JsonObject.doubles(k: String): List<Double?> = this[k]!!.jsonArray.map { it.dbl() }

    private fun result(o: JsonElement?): HrReview.Result? {
        val e = (o as? JsonObject) ?: return null
        return HrReview.Result(
            usable = e["usable"]!!.jsonPrimitive.booleanOrNull!!,
            avgHr = e["avg_hr"].dbl(), maxHr = e["max_hr"].dbl(),
            replaced = e["replaced"]!!.jsonPrimitive.longOrNull!!.toInt(),
            samples = e["samples"]!!.jsonPrimitive.longOrNull!!.toInt(),
        )
    }

    /** Python's `isoformat()` of an aware UTC datetime, back to an instant. */
    private fun instant(iso: String): FitDateTime {
        val date = CivilDate(iso.substring(0, 4).toInt(), iso.substring(5, 7).toInt(), iso.substring(8, 10).toInt())
        val h = iso.substring(11, 13).toLong()
        val m = iso.substring(14, 16).toLong()
        val s = iso.substring(17, 19).toLong()
        val micros = if (iso.length > 19 && iso[19] == '.') iso.substring(20, 26).toLong() else 0L
        return FitDateTime(((date.epochDay * 86_400L) + h * 3600 + m * 60 + s) * 1_000_000L + micros)
    }

    private fun plain(e: JsonElement?): Any? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content
        return p.longOrNull ?: p.doubleOrNull
    }

    @Test
    fun every_recording_is_reviewed_exactly_as_the_server_reviews_it() {
        val cases = corpus["review"]!!.jsonArray.map { it.jsonObject }
        for ((n, c) in cases.withIndex()) {
            val got = HrReview.review(
                (c["sport"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
                c.doubles("times").map { it!! }, c.doubles("hrs"), c.doubles("speeds"),
                c.doubles("altitudes"), c.doubles("cadences"),
            )
            assertEquals(result(c["expect"]), got, "case $n: ${c["sport"]} ${c["defect"]} ${c["sampling"]}")
        }
    }

    @Test
    fun the_importer_scores_reviewed_recordings_as_the_server_does() {
        for (c in corpus["import"]!!.jsonArray.map { it.jsonObject }) {
            val activity = c["activity"]!!.jsonObject.mapValues { plain(it.value) }
            val points = (c["points"] as JsonArray).map { p ->
                p.jsonObject.mapValues { (k, v) -> if (k == "recorded_at") instant(v.jsonPrimitive.content) else plain(v) }
            }
            val label = "${activity["sport"]} ${c["defect"]}"
            assertEquals(result(c["review"]), HrReview.reviewPoints(activity["sport"] as String?, points), label)
            for ((thr, want) in c["tss"]!!.jsonObject) {
                val threshold = if (thr == "None") null else thr.toDouble()
                assertEquals(want.dbl(), LocalImporter.hrTss(activity, threshold, points), "$label @ $thr")
            }
        }
    }
}
