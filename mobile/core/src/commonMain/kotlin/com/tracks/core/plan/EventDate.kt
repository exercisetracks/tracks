// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * A recommended date for a new race / event goal — backend
 * app/calculators/event_date.py, which has the reasoning; held to it by
 * spec/fixtures/goal_planning.json.
 *
 * The web asks the server for this date and the phone works it out itself,
 * offline, so both must land on the same Sunday with the same reasons.
 * Rounding is `floor(x + 0.5)` on both sides rather than either language's
 * `round`, so no tie is left to a language default.
 */
object EventDate {

    data class Advice(val date: CivilDate, val weeks: Int, val basis: String, val reasons: List<String>)

    private val SPEED_KMH = mapOf(
        "running" to 10.0, "cycling" to 25.0, "mountain_biking" to 15.0, "swimming" to 3.0,
        "triathlon" to 20.0, "hiking" to 4.0, "rowing" to 12.0, "paddling" to 8.0,
    )
    private const val DEFAULT_SPEED_KMH = 10.0
    private const val DEFAULT_HOURS = 2.0
    private val GENERAL = listOf(0.5 to 4.0, 1.0 to 7.0, 2.0 to 11.0, 4.0 to 18.0, 8.0 to 26.0)
    private const val STRENGTH_WEEKS = 12

    const val BUILD_PER_WEEK = 3.0
    const val TAPER_WEEKS = 2
    const val MAX_WEEKS = 30
    /** "Recent training": CTL's own time constant, in days. */
    const val HISTORY_DAYS = 42

    private fun halfUp(x: Double): Int = floor(x + 0.5).toInt()

    private fun family(sport: String?) = PlanBase.sportFamily((sport ?: "running").lowercase())

    fun eventHours(sport: String?, distanceMeters: Double?): Double? {
        if (distanceMeters == null || distanceMeters <= 0) return null
        return distanceMeters / 1000.0 / (SPEED_KMH[family(sport)] ?: DEFAULT_SPEED_KMH)
    }

    fun generalWeeks(hours: Double): Int {
        if (hours <= GENERAL[0].first) return halfUp(GENERAL[0].second)
        for (i in 0 until GENERAL.size - 1) {
            val (h0, w0) = GENERAL[i]
            val (h1, w1) = GENERAL[i + 1]
            if (hours <= h1) return halfUp(w0 + (w1 - w0) * (hours - h0) / (h1 - h0))
        }
        return halfUp(GENERAL.last().second)
    }

    fun targetCtl(hours: Double): Double = min(90.0, 25.0 + 10.0 * hours)

    /** Whether training in [activitySport] is training for an event in [eventSport]. */
    fun countsToward(eventSport: String?, activitySport: String?): Boolean {
        val ef = family(eventSport)
        val af = PlanBase.sportFamily((activitySport ?: "").lowercase())
        return when (ef) {
            "generic" -> true
            "triathlon" -> af in setOf("running", "cycling", "swimming", "triathlon")
            "cycling", "mountain_biking" -> af in setOf("cycling", "mountain_biking")
            else -> af == ef
        }
    }

    private fun fmtDuration(hours: Double): String {
        val minutes = halfUp(hours * 60 / 5) * 5
        if (minutes < 60) return "$minutes min"
        val halves = halfUp(hours * 2)
        return "${halves / 2}${if (halves % 2 != 0) ".5" else ""} h"
    }

    private fun sundayOnOrAfter(d: CivilDate): CivilDate {
        // Monday = 0, as Python's weekday(); 1970-01-01 was a Thursday.
        val weekday = ((d.epochDay + 3) % 7 + 7) % 7
        return CivilDate.fromEpochDay(d.epochDay + (6 - weekday))
    }

    /**
     * The recommended date and why. [ctl] is today's fitness; [sportTss] and
     * [totalTss] the last [HISTORY_DAYS] days' load that [countsToward] this
     * event, and in all.
     */
    fun recommend(
        sport: String?,
        distanceMeters: Double?,
        today: CivilDate,
        ctl: Double?,
        sportTss: Double,
        totalTss: Double,
    ): Advice {
        val fam = family(sport)
        if (fam == "strength") {
            return result(today, STRENGTH_WEEKS, "general", listOf("A strength block runs about $STRENGTH_WEEKS weeks."))
        }
        val known = eventHours(sport, distanceMeters)
        val hours = known ?: DEFAULT_HOURS
        val general = generalWeeks(hours)
        val demand = if (known != null) {
            "An event of about ${fmtDuration(hours)} usually gets about $general weeks of preparation."
        } else {
            "With no distance set, a mid-length event is assumed: about $general weeks of preparation."
        }
        if (ctl == null || ctl <= 0 || totalTss <= 0) {
            return result(today, general, "general", listOf(
                demand,
                "There is no recent training to judge your fitness from, so this is the general recommendation.",
            ))
        }

        val share = min(1.0, max(0.0, sportTss / totalTss))
        val effective = ctl * (0.5 + 0.5 * share)
        val target = targetCtl(hours)
        val build = ceil(max(0.0, target - effective) / BUILD_PER_WEEK).toInt()
        val floorWeeks = max(4, halfUp(general / 2.0))
        val weeks = min(MAX_WEEKS, max(floorWeeks, build + TAPER_WEEKS))

        val reasons = mutableListOf(
            if (fam == "generic") "Your fitness is CTL ${halfUp(ctl)}."
            else "Your fitness is CTL ${halfUp(ctl)}, ${halfUp(share * 100)}% of it from this sport.",
        )
        if (build + TAPER_WEEKS >= floorWeeks) {
            reasons += "This event wants about CTL ${halfUp(target)}: at +${halfUp(BUILD_PER_WEEK)} a week " +
                "that is $build weeks of building, plus $TAPER_WEEKS to taper."
        } else {
            val have = if (effective >= target) "you already have" else "you are close to"
            reasons += "This event wants about CTL ${halfUp(target)}, which $have: " +
                "$weeks weeks leaves time to sharpen and taper."
        }
        if (weeks == MAX_WEEKS && build + TAPER_WEEKS > MAX_WEEKS) {
            reasons += "Capped at $MAX_WEEKS weeks: a shorter event first would get you there sooner."
        }
        return result(today, weeks, "history", reasons)
    }

    private fun result(today: CivilDate, weeks: Int, basis: String, reasons: List<String>) =
        Advice(sundayOnOrAfter(CivilDate.fromEpochDay(today.epochDay + 7L * weeks)), weeks, basis, reasons)
}

/**
 * Which edits make a plan stale and so rebuild it — backend
 * app/calculators/plan/staleness.py, which has the reasoning (why goal
 * fields are a deny-list and settings an allow-list); held to it by
 * spec/fixtures/goal_planning.json. There is no Regenerate button: these
 * rules are the only thing that rebuilds a plan after an edit.
 */
object PlanStaleness {
    val NON_PLAN_GOAL_FIELDS = setOf("event_name", "notes", "target_weekly_km", "volume_sport")

    val PLAN_SETTINGS = setOf(
        "units",
        "ftp_mode", "ftp_manual",
        "threshold_hr_mode", "threshold_hr_manual",
        "max_hr_mode", "max_hr_manual",
        "equipment_available", "strength_experience",
        "hidden_sports", "activity_frequency",
    )

    /** Deactivating is not a plan change; activating is (its plan is from whenever it was last active). */
    fun goalEditStalesPlan(changed: Map<String, Any?>): Boolean = changed.any { (field, value) ->
        if (field == "is_active") value == true else field !in NON_PLAN_GOAL_FIELDS
    }

    fun settingStalesPlan(field: String): Boolean = field in PLAN_SETTINGS
}
