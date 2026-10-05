// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.FitDataMessage
import com.tracks.core.fit.decode.FitReader

/**
 * Port of `backend/app/parsers/activity.py`: an activity file as the server's
 * importer receives it — the session, its track, laps, strength sets, climbing
 * splits, and the metrics computed from them.
 *
 * The reasoning behind each rule (which VO2max source to trust and why it is
 * range-checked, the sport-profile fallback, which climbing fields are read by
 * number) is written down at the Python and not repeated here; this file only
 * notes where Kotlin has to work to behave like Python.
 */
internal object ActivityParser {

    private const val SEMICIRCLES_TO_DEGREES = 180.0 / 2147483648.0
    private const val VO2MAX_MIN = 15.0
    private const val VO2MAX_MAX = 90.0

    fun canParse(bytes: ByteArray): Boolean = claims(bytes) { messages ->
        val first = messages.firstOrNull { it.name == "file_id" }
        first != null && Py.asStr(first.get("type")) == "activity"
    }

    private fun plausibleVo2max(raw: Any?, divisor: Double): Double? {
        if (raw == null) return null
        val value = Py.floatOrNull(raw)?.let { it / divisor } ?: return null
        return if (value in VO2MAX_MIN..VO2MAX_MAX) PyMath.round(value, 1) else null
    }

    fun parse(bytes: ByteArray): Map<String, Any?> {
        var deviceInfo: Map<String, Any?> = linkedMapOf()
        var activity: MutableMap<String, Any?> = linkedMapOf()
        val records = ArrayList<MutableMap<String, Any?>>()
        val laps = ArrayList<Map<String, Any?>>()
        val sets = ArrayList<Map<String, Any?>>()
        val splits = ArrayList<Map<String, Any?>>()
        var vo2max: Double? = null

        for (frame in FitReader(bytes).messages()) {
            when (frame.name) {
                "file_id" -> deviceInfo = ParseUtils.parseFileId(frame)
                "session" -> activity = parseSession(frame)
                "record" -> parseRecord(frame)?.let(records::add)
                "lap" -> parseLap(frame, laps.size + 1)?.let(laps::add)
                "set" -> parseSet(frame, sets.size + 1)?.let(sets::add)
                "split" -> parseClimbSplit(frame, splits.size + 1)?.let(splits::add)
                // `x or vo2max`: a result of 0.0 would fall through too, but
                // the range check means one never arrives.
                "unknown_229" -> vo2max = plausibleVo2max(frame.getByIter("unknown_2"), 10.0) ?: vo2max
                "unknown_140" -> {
                    val met = frame.getByIter("unknown_7")
                    if (Py.truthy(met)) vo2max = plausibleVo2max(met, 65536.0 / 3.5) ?: vo2max
                }
            }
        }

        if (vo2max != null) activity["vo2max_estimate"] = vo2max

        var points: List<MutableMap<String, Any?>> = records
        if (points.isNotEmpty()) {
            points = Smoother.smooth(points)
            activity = Smoother.recomputeSummary(activity, points)
        }

        val sport = activity["sport"]
        activity["efficiency_factor"] = ActivityMetrics.efficiencyFactor(
            sport = sport,
            avgHr = activity["avg_heart_rate"],
            normalizedPower = activity["normalized_power"],
            avgSpeed = activity["avg_speed"],
        )
        activity["aerobic_decoupling"] = ActivityMetrics.aerobicDecoupling(sport, points)

        val sportLower = if (Py.truthy(sport)) Py.str(sport).lowercase() else ""
        val powerCurve = if (sportLower in ActivityMetrics.cyclingSports) ActivityMetrics.powerCurve(points) else emptyMap()
        val paceCurve = if (sportLower in ActivityMetrics.runningSports) ActivityMetrics.paceCurve(points) else emptyMap()

        return linkedMapOf(
            "type" to "activity",
            "device" to deviceInfo,
            "activity" to activity,
            "data_points" to points,
            "laps" to laps,
            "strength_sets" to sets,
            "climb_splits" to splits,
            "power_curve" to powerCurve,
            "pace_curve" to paceCurve,
        )
    }

    private fun parseSession(frame: FitDataMessage): MutableMap<String, Any?> {
        val elapsed = frame.get("total_elapsed_time")
        return linkedMapOf(
            "name" to frame.get("sport_profile_name"),
            "sport" to resolveSport(frame),
            "sub_sport" to resolveSubSport(frame),
            "started_at" to frame.get("start_time"),
            "duration_seconds" to elapsed?.let(Py::int),
            "distance_meters" to frame.get("total_distance"),
            "avg_heart_rate" to frame.get("avg_heart_rate"),
            "max_heart_rate" to frame.get("max_heart_rate"),
            "total_calories" to frame.get("total_calories"),
            "training_stress_score" to frame.get("training_stress_score"),
            "intensity_factor" to frame.get("intensity_factor"),
            "aerobic_training_effect" to frame.get("total_training_effect"),
            "anaerobic_training_effect" to frame.get("total_anaerobic_training_effect"),
            "training_load_peak" to frame.get("training_load_peak"),
            "avg_speed" to frame.getEnhanced("avg_speed"),
            "max_speed" to frame.getEnhanced("max_speed"),
            "avg_cadence" to frame.get("avg_cadence"),
            "total_ascent" to frame.get("total_ascent"),
            "total_descent" to frame.get("total_descent"),
            "avg_power" to frame.get("avg_power"),
            "normalized_power" to frame.get("normalized_power"),
            "total_grit" to frame.get("total_grit"),
            "avg_flow" to frame.get("avg_flow"),
            // The watch's post-workout prompts, as recorded: see activity.py.
            "workout_feel" to frame.get("workout_feel"),
            "workout_rpe" to frame.get("workout_rpe"),
        )
    }

    private fun resolveSport(frame: FitDataMessage): String? {
        val sport = Py.asStr(frame.get("sport"))
        if (sport != null && Py.isDigit(sport)) {
            val profile = frame.get("sport_profile_name")
            if (Py.truthy(profile)) return pyStrip(profile).lowercase()
        }
        return sport
    }

    private fun resolveSubSport(frame: FitDataMessage): String? {
        val sub = Py.asStr(frame.get("sub_sport"))
        return if (sub != null && Py.isDigit(sub)) null else sub
    }

    private fun parseLap(frame: FitDataMessage, lapNumber: Int): Map<String, Any?>? {
        val elapsed = frame.get("total_elapsed_time") ?: return null
        val strokes = frame.get("total_strokes")
        val putts = frame.get("total_putts")
        val strokeDist = frame.get("avg_stroke_distance")
        val timeInZone = frame.get("hole_time_in_zone")
        return linkedMapOf(
            "lap_number" to lapNumber.toLong(),
            "start_time" to frame.get("start_time"),
            "duration_seconds" to Py.float(elapsed),
            "distance_meters" to frame.get("total_distance"),
            "avg_heart_rate" to frame.get("avg_heart_rate"),
            "max_heart_rate" to frame.get("max_heart_rate"),
            "avg_speed" to frame.getEnhanced("avg_speed"),
            "max_speed" to frame.getEnhanced("max_speed"),
            "avg_cadence" to frame.get("avg_cadence"),
            "total_ascent" to frame.get("total_ascent"),
            "total_descent" to frame.get("total_descent"),
            "avg_power" to frame.get("avg_power"),
            "total_calories" to frame.get("total_calories"),
            "total_grit" to frame.get("total_grit"),
            "avg_flow" to frame.get("avg_flow"),
            "total_strokes" to strokes?.let(Py::asInt),
            "total_putts" to putts?.let(Py::asInt),
            "avg_stroke_distance" to strokeDist?.let(Py::float),
            "hole_time_in_zone" to timeInZone?.let(Py::float),
        )
    }

    private fun parseSet(frame: FitDataMessage, setNumber: Int): Map<String, Any?>? {
        val setType = Py.asStr(frame.get("set_type")) ?: return null
        // Array fields — the first element is the one that matters.
        val rawCat = frame.get("category")
        val rawSub = frame.get("category_subtype")
        val category = if (rawCat is List<*>) rawCat[0] else rawCat
        val subtype = if (rawSub is List<*>) rawSub[0] else rawSub
        val catStr = ExerciseNames.decodeCategory(category)
        val subtypeInt = subtype?.let(Py::int)
        val duration = frame.get("duration")
        return linkedMapOf(
            "set_number" to setNumber.toLong(),
            "set_type" to setType,
            "exercise_category" to catStr,
            "exercise_name" to if (setType == "active") ExerciseNames.resolve(catStr, subtypeInt) else null,
            "weight_kg" to frame.get("weight"),
            "repetitions" to Py.asInt(frame.get("repetitions")),
            "duration_seconds" to duration?.let(Py::float),
            "start_time" to frame.get("start_time"),
        )
    }

    private fun parseClimbSplit(frame: FitDataMessage, splitNumber: Int): Map<String, Any?>? {
        val splitType = Py.asStr(frame.get("split_type"))
        if (splitType != "climb_active" && splitType != "climb_rest") return null
        val duration = frame.get("total_elapsed_time")
        val calories = frame.get("total_calories")
        return linkedMapOf(
            "split_number" to splitNumber.toLong(),
            "split_type" to splitType,
            "start_time" to frame.get("start_time"),
            "end_time" to frame.get("end_time"),
            "duration_seconds" to duration?.let(Py::float),
            "total_ascent" to frame.get("total_ascent"),
            "avg_vert_speed" to frame.get("avg_vert_speed"),
            "total_calories" to calories?.let(Py::int),
            "min_heart_rate" to frame.getByIter("unknown_15"),
            "max_heart_rate" to frame.getByIter("unknown_16"),
            "difficulty_score" to frame.getByIter("unknown_80"),
            "grade_level" to frame.getByIter("unknown_70"),
            "climb_result" to frame.getByIter("unknown_71"),
        )
    }

    private fun parseRecord(frame: FitDataMessage): MutableMap<String, Any?>? {
        val timestamp = frame.get("timestamp") ?: return null
        return linkedMapOf(
            "recorded_at" to timestamp,
            "lat" to frame.get("position_lat")?.let { Py.num(it) * SEMICIRCLES_TO_DEGREES },
            "lng" to frame.get("position_long")?.let { Py.num(it) * SEMICIRCLES_TO_DEGREES },
            "altitude" to frame.getEnhanced("altitude"),
            "heart_rate" to frame.get("heart_rate"),
            "power" to frame.get("power"),
            "cadence" to frame.get("cadence"),
            "speed" to frame.getEnhanced("speed"),
            "grit" to frame.get("grit"),
            "flow" to frame.get("flow"),
        )
    }

    /** `str.strip()` — but a non-string has no `strip`, which is an AttributeError. */
    private fun pyStrip(v: Any?): String =
        (v as? String ?: throw PyError("AttributeError: no strip on ${Py.str(v)}")).trim()
}
