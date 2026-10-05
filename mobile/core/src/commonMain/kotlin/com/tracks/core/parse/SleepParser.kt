// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.FitDataMessage
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.fit.decode.FitReader

/**
 * Port of `backend/app/parsers/sleep.py`: a night, preferring the watch's own
 * `sleep_summary` (message 411) over the raw stage stream, with the stream
 * clipped to the summary's window. The Python explains why at length.
 */
internal object SleepParser {

    private const val SLEEP_SUMMARY = "unknown_411"
    private const val GARMIN_EPOCH = 631_065_600L

    fun canParse(bytes: ByteArray): Boolean = claims(bytes) { messages ->
        val first = messages.firstOrNull { it.name == "file_id" }
        if (first == null) {
            false
        } else {
            val t = first.get("type")
            isNumericEqual(t, 49) || Py.asStr(t) == "sleep"
        }
    }

    private class Summary(
        val start: FitDateTime,
        val end: FitDateTime,
        val deep: Double?,
        val light: Double?,
        val rem: Double?,
        val awake: Double?,
        val score: Long?,
    ) {
        val lengthMicros: Long get() = end.epochMicros - start.epochMicros
    }

    private fun summaryField(frame: FitDataMessage, name: String, number: Int): Any? =
        frame.get(name) ?: frame.getByIter("unknown_$number")

    private fun readSummary(frame: FitDataMessage): Summary? {
        val start = Py.intOrNull(summaryField(frame, "sleep_start_timestamp_utc", 10))
        val end = Py.intOrNull(summaryField(frame, "sleep_end_timestamp_utc", 8))
        if (start == null || end == null || end <= start) return null

        fun minutes(name: String, number: Int): Double? {
            val raw = Py.intOrNull(summaryField(frame, name, number))
            return if (raw == null || raw >= 0xFFFF) null else raw / 60.0
        }

        return Summary(
            start = FitDateTime.ofEpochSeconds(start + GARMIN_EPOCH),
            end = FitDateTime.ofEpochSeconds(end + GARMIN_EPOCH),
            deep = minutes("deep_duration", 1),
            light = minutes("light_duration", 2),
            rem = minutes("rem_duration", 3),
            awake = minutes("awake_duration", 4),
            score = Py.intOrNull(summaryField(frame, "sleep_score", 0)),
        )
    }

    fun parse(bytes: ByteArray): Map<String, Any?> {
        var deviceInfo: Map<String, Any?> = linkedMapOf()
        val stream = ArrayList<Pair<Any, String>>()
        var assessment: Map<String, Double?> = emptyMap()
        var summary: Summary? = null
        var sleepDate: Any? = null

        for (frame in FitReader(bytes).messages()) {
            when (frame.name) {
                "file_id" -> {
                    deviceInfo = ParseUtils.parseFileId(frame)
                    val tc = frame.get("time_created")
                    if (tc != null) sleepDate = if (tc is FitDateTime) tc.utcDate else tc
                }
                "sleep_level" -> {
                    val ts = frame.get("timestamp")
                    val level = Py.asStr(frame.get("sleep_level"))
                    if (ts != null && level != null) stream.add(ts to level)
                }
                SLEEP_SUMMARY -> {
                    // The longest session wins; a nap must not rename the night.
                    val found = readSummary(frame)
                    if (found != null && (summary == null || found.lengthMicros > summary.lengthMicros)) {
                        summary = found
                    }
                }
                "sleep_assessment" -> {
                    val score = frame.get("overall_sleep_score")
                        ?: frame.get("combined_awake_score")
                        ?: frame.get("quality_score")
                    val hrv = frame.get("hrv_rmssd_5min") ?: frame.get("avg_hrv_score")
                    assessment = linkedMapOf(
                        "sleep_score" to Py.floatOrNull(score),
                        "resting_hr" to Py.floatOrNull(frame.get("resting_heart_rate")),
                        "hrv" to Py.floatOrNull(hrv),
                    )
                }
            }
        }

        val levels = summary?.let { clip(stream, it.start, it.end) } ?: stream

        if (levels.isNotEmpty()) {
            val last = levels.last().first
            if (last is FitDateTime) sleepDate = last.utcDate
        }

        val metrics = stageDurations(levels)
        summary?.let { metrics.putAll(summaryMetrics(it)) }
        for ((field, value) in assessment) if (value != null) metrics[field] = value

        return linkedMapOf(
            "type" to "daily",
            "device" to deviceInfo,
            "date" to sleepDate,
            "metrics" to metrics,
            "stages" to stageSegments(levels),
        )
    }

    private fun summaryMetrics(summary: Summary): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var slept = 0.0
        for ((hours, key) in listOf(
            summary.deep to "sleep_deep_hours",
            summary.light to "sleep_light_hours",
            summary.rem to "sleep_rem_hours",
        )) {
            if (hours == null) continue
            out[key] = PyMath.round(hours, 2)
            slept += hours
        }
        if (slept > 0) out["sleep_hours"] = PyMath.round(slept, 2)
        if (Py.truthy(summary.awake)) out["sleep_awake_hours"] = PyMath.round(summary.awake!!, 2)
        if (Py.truthy(summary.score)) out["sleep_score"] = summary.score!!.toDouble()
        return out
    }

    /** `_clip`: the stream cut to the window, the straddling record moved up to its opening. */
    private fun clip(levels: List<Pair<Any, String>>, start: FitDateTime, end: FitDateTime): List<Pair<Any, String>> {
        val kept = ArrayList<Pair<Any, String>>()
        for ((index, entry) in levels.withIndex()) {
            val (ts, level) = entry
            val following = if (index + 1 < levels.size) levels[index + 1].first else null
            if (instant(following ?: ts) <= start) continue
            if (instant(ts) >= end) continue
            kept.add(maxOf(instant(ts), start) to level)
        }
        if (kept.isEmpty()) return emptyList()
        if (instant(kept.last().first) < end) kept.add(end to kept.last().second)
        return kept
    }

    /** `_stage_segments`: the night as spans, for drawing. */
    private fun stageSegments(levels: List<Pair<Any, String>>): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for (i in 0 until levels.size - 1) {
            val (tsStart, level) = levels[i]
            val tsEnd = levels[i + 1].first
            val seconds = instant(tsEnd).secondsSince(instant(tsStart))
            if (seconds <= 0) continue
            out.add(
                linkedMapOf(
                    "start" to instant(tsStart).isoformat(),
                    "end" to instant(tsEnd).isoformat(),
                    "level" to level.ifEmpty { "unmeasurable" }.lowercase(),
                    "seconds" to PyMath.roundToLong(seconds),
                ),
            )
        }
        return out
    }

    /** `_compute_stage_durations`: hours per stage; awake recorded but never counted as sleep. */
    private fun stageDurations(levels: List<Pair<Any, String>>): MutableMap<String, Any?> {
        var deep = 0.0
        var light = 0.0
        var rem = 0.0
        var awake = 0.0
        var total = 0.0
        for (i in 0 until levels.size - 1) {
            val (tsStart, level) = levels[i]
            val hours = instant(levels[i + 1].first).secondsSince(instant(tsStart)) / 3600.0
            val lvl = level.lowercase()
            when {
                "deep" in lvl -> { deep += hours; total += hours }
                "rem" in lvl -> { rem += hours; total += hours }
                "light" in lvl || ("sleep" in lvl && "awake" !in lvl) -> { light += hours; total += hours }
                "awake" in lvl || "wake" in lvl -> awake += hours
            }
        }
        val result = LinkedHashMap<String, Any?>()
        if (total > 0) result["sleep_hours"] = PyMath.round(total, 2)
        if (deep > 0) result["sleep_deep_hours"] = PyMath.round(deep, 2)
        if (light > 0) result["sleep_light_hours"] = PyMath.round(light, 2)
        if (rem > 0) result["sleep_rem_hours"] = PyMath.round(rem, 2)
        if (awake > 0) result["sleep_awake_hours"] = PyMath.round(awake, 2)
        return result
    }

    /**
     * A stage timestamp as an instant. fitdecode leaves a `date_time` below
     * 0x10000000 as an integer, and the Python then fails comparing or
     * subtracting it — so this fails too, rather than inventing a meaning.
     */
    private fun instant(v: Any?): FitDateTime =
        v as? FitDateTime ?: throw PyError("TypeError: not a datetime: ${Py.str(v)}")

    internal fun isNumericEqual(v: Any?, target: Long): Boolean = when (v) {
        is Long -> v == target
        is Double -> v == target.toDouble()
        is Boolean -> (if (v) 1L else 0L) == target
        else -> false
    }
}
