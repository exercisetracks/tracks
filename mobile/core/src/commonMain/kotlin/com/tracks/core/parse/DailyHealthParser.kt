// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.fit.decode.FitReader

/**
 * Port of `backend/app/parsers/daily_health.py`: monitoring, HRV-status and
 * Body Battery files, filed by the **local** day each reading belongs to.
 *
 * The Python documents every rule here — why days are local, why a record at
 * exactly midnight closes the old day, why steps are summed per activity type
 * and resting calories pro-rated. The one thing worth repeating: the UTC
 * offset comes from the file and falls back to zero, never to the phone's own
 * zone, precisely so that this port and the server file a reading under the
 * same date.
 */
internal object DailyHealthParser {

    private val monitoringTypes = setOf("monitoring_b", "monitoring")
    private const val HRV_TYPE = 68L
    private val byContent = setOf("spo2_data", "hsa_body_battery_data")
    private val stepActivities = setOf("walking", "running")
    private const val MINUTES_PER_DAY = 1440L
    private const val GARMIN_EPOCH = 631_065_600L
    private const val SECONDS_PER_DAY = 86_400L

    private fun isParseable(t: Any?): Boolean =
        SleepParser.isNumericEqual(t, HRV_TYPE) || Py.asStr(t) in monitoringTypes

    /**
     * Unlike the other two, this keeps reading past a `file_id` it does not
     * recognise: a file whose declared type is something else can still carry
     * readings worth having.
     */
    fun canParse(bytes: ByteArray): Boolean = claims(bytes) { messages ->
        messages.any { frame ->
            (frame.name == "file_id" && isParseable(frame.get("type"))) || frame.name in byContent
        }
    }

    // ── Roll-ups (public in the Python, tested on their own there) ──────────

    fun rollUpBodyBattery(levels: List<Long>, charged: List<Long>, drained: List<Long>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (levels.isNotEmpty()) {
            out["body_battery_high"] = levels.max()
            out["body_battery_low"] = levels.min()
            out["body_battery_last"] = levels.last()
        }
        if (charged.isEmpty() && drained.isEmpty() && levels.size > 1) {
            val steps = levels.zipWithNext { a, b -> b - a }
            val gained = steps.filter { it > 0 }.sum()
            val spent = -steps.filter { it < 0 }.sum()
            if (gained != 0L) out["body_battery_charged"] = gained
            if (spent != 0L) out["body_battery_drained"] = spent
            return out
        }
        if (charged.isNotEmpty()) out["body_battery_charged"] = charged.sum()
        if (drained.isNotEmpty()) out["body_battery_drained"] = drained.sum()
        return out
    }

    fun rollUpSteps(totals: Map<String, Long>): Long? {
        val counted = totals.filter { (k, v) -> k in stepActivities && v >= 0 }.values
        return if (counted.isEmpty()) null else counted.sum()
    }

    fun rollUpRestingCalories(ratePerDay: Long?, minutes: Long?): Long? {
        if (ratePerDay == null || ratePerDay <= 0) return null
        val elapsed = minutes ?: MINUTES_PER_DAY
        val share = minOf(maxOf(elapsed, 0L), MINUTES_PER_DAY).toDouble() / MINUTES_PER_DAY
        return PyMath.roundToLong(ratePerDay * share).takeIf { it != 0L }
    }

    fun rollUpActiveCalories(totals: Map<String, Long>): Long? {
        val counted = totals.values.filter { it >= 0 }
        return if (counted.isEmpty()) null else counted.sum()
    }

    // ── Parse ────────────────────────────────────────────────────────────────

    private class Day {
        val metrics = LinkedHashMap<String, Any?>()
        val stress = ArrayList<Double>()
        val resp = ArrayList<Double>()
        val spo2 = ArrayList<Double>()
        val battery = ArrayList<Long>()
        val charged = ArrayList<Long>()
        val drained = ArrayList<Long>()
        val stressAt = HashMap<Long, Long>()
        val steps = LinkedHashMap<String, Long>()
        val calories = LinkedHashMap<String, Long>()
        var elapsed: Long? = null
        var lastAt: Long? = null
    }

    fun parse(bytes: ByteArray): Map<String, Any?> {
        var deviceInfo: Map<String, Any?> = linkedMapOf()
        val offset = utcOffset(bytes)
        val days = HashMap<CivilDate, Day>()
        var fallbackDate: CivilDate? = null

        fun dayAt(whenValue: Any?): Day? {
            val date = localDate(whenValue, offset) ?: fallbackDate ?: return null
            val bucket = days.getOrPut(date) { Day() }
            val whenUtc = epoch(whenValue)
            if (whenUtc != null && (bucket.lastAt == null || whenUtc > bucket.lastAt!!)) bucket.lastAt = whenUtc
            return bucket
        }

        var restingRate: Long? = null
        var lastMonitoring: Any? = null

        for (frame in FitReader(bytes).messages()) {
            when (frame.name) {
                "file_id" -> {
                    deviceInfo = ParseUtils.parseFileId(frame)
                    fallbackDate = localDate(frame.get("time_created"), offset)
                }

                "monitoring_hr_data" -> {
                    val day = dayAt(frame.get("timestamp"))
                    val rhr = frame.get("current_day_resting_heart_rate")
                    if (day != null && rhr != null) day.metrics["resting_hr"] = Py.float(rhr)
                }

                "monitoring_info" -> {
                    val rmr = frame.get("resting_metabolic_rate")
                    if (rmr != null && restingRate == null) restingRate = Py.intOrNull(rmr)
                    val spo2 = frame.get("avg_spo2")
                    val day = dayAt(frame.get("timestamp"))
                    if (day != null && spo2 != null) {
                        Py.floatOrNull(spo2)?.let { v ->
                            if (v in 50.0..100.0) day.metrics["spo2"] = PyMath.round(v, 1)
                        }
                    }
                }

                "monitoring" -> {
                    var whenValue = frame.get("timestamp")
                    if (whenValue == null) whenValue = resolveTimestamp(frame.get("timestamp_16"), lastMonitoring)
                    if (whenValue != null) lastMonitoring = whenValue
                    whenValue = closesTheDay(whenValue, offset)
                    val day = dayAt(whenValue) ?: continue
                    val activity = Py.asStr(frame.get("activity_type")).takeIf { Py.truthy(it) } ?: "walking"
                    var steps = frame.get("steps")
                    if (steps == null && activity in stepActivities) steps = frame.get("cycles")
                    if (steps != null && activity in stepActivities) {
                        Py.intOrNull(steps)?.let { n ->
                            if (n > (day.steps[activity] ?: -1L)) day.steps[activity] = n
                        }
                    }
                    frame.get("active_calories")?.let { raw ->
                        Py.intOrNull(raw)?.let { n ->
                            if (n > (day.calories[activity] ?: -1L)) day.calories[activity] = n
                        }
                    }
                    frame.get("duration_min")?.let { raw ->
                        Py.intOrNull(raw)?.let { n -> day.elapsed = maxOf(day.elapsed ?: 0L, n) }
                    }
                    minuteOfDay(whenValue, offset)?.let { m -> day.elapsed = maxOf(day.elapsed ?: 0L, m) }
                }

                "stress_level" -> {
                    val at = Py.or(frame.get("stress_level_time"), frame.get("timestamp"))
                    val day = dayAt(at) ?: continue
                    frame.get("stress_level_value")?.let { raw ->
                        Py.floatOrNull(raw)?.let { v ->
                            if (v in 0.0..100.0) {
                                day.stress.add(v)
                                minuteOfDay(at, offset)?.let { m -> day.stressAt[m] = PyMath.roundToLong(v) }
                            }
                        }
                    }
                    val energy = frame.get("body_energy") ?: frame.getByIter("unknown_3")
                    if (energy != null) {
                        Py.intOrNull(energy)?.let { v -> if (v in 0L..100L) day.battery.add(v) }
                    }
                }

                "respiration_rate" -> {
                    val day = dayAt(frame.get("timestamp"))
                    val resp = frame.get("respiration_rate")
                    if (day != null && resp != null) {
                        Py.floatOrNull(resp)?.let { v -> if (v > 0 && v <= 60) day.resp.add(v) }
                    }
                }

                "spo2_data" -> {
                    val day = dayAt(frame.get("timestamp"))
                    val reading = frame.get("reading_spo2")
                    if (day != null && reading != null) {
                        Py.floatOrNull(reading)?.let { v -> if (v in 50.0..100.0) day.spo2.add(v) }
                    }
                }

                "hsa_body_battery_data" -> {
                    val day = dayAt(frame.get("timestamp")) ?: continue
                    frame.get("level")?.let { raw ->
                        Py.intOrNull(raw)?.let { v -> if (v in 0L..100L) day.battery.add(v) }
                    }
                    for ((field, bucket) in listOf("charged" to day.charged, "uncharged" to day.drained)) {
                        val raw = frame.get(field) ?: continue
                        Py.intOrNull(raw)?.let { n -> if (n > 0) bucket.add(n) }
                    }
                }

                "hrv_status_summary" -> {
                    val day = dayAt(frame.get("timestamp")) ?: continue
                    val hrv = frame.get("last_night_average") ?: frame.get("weekly_average")
                    if (hrv != null && "hrv" !in day.metrics) day.metrics["hrv"] = Py.float(hrv)
                }
            }
        }

        return linkedMapOf(
            "type" to "daily",
            "device" to deviceInfo,
            "days" to days.keys.sorted().map { finishDay(it, days.getValue(it), restingRate, offset) },
        )
    }

    private fun finishDay(date: CivilDate, day: Day, restingRate: Long?, offset: Long): Map<String, Any?> {
        val metrics = LinkedHashMap(day.metrics)
        if (day.stress.isNotEmpty()) {
            metrics["avg_stress_level"] = PyMath.round(PyMath.sum(day.stress) / day.stress.size, 1)
        }
        if (day.resp.isNotEmpty()) {
            metrics["avg_respiration_rate"] = PyMath.round(PyMath.sum(day.resp) / day.resp.size, 2)
        }
        metrics.putAll(rollUpBodyBattery(day.battery, day.charged, day.drained))
        rollUpSteps(day.steps)?.let { metrics["steps"] = it }
        rollUpActiveCalories(day.calories)?.let { metrics["active_calories"] = it }

        var elapsed = day.elapsed
        if (elapsed == null && day.lastAt != null) {
            elapsed = (day.lastAt!! + offset).mod(SECONDS_PER_DAY) / 60
        }
        rollUpRestingCalories(restingRate, elapsed)?.let { metrics["resting_calories"] = it }

        if (day.spo2.isNotEmpty() && "spo2" !in metrics) {
            metrics["spo2"] = PyMath.round(PyMath.sum(day.spo2) / day.spo2.size, 1)
        }

        return linkedMapOf(
            "date" to date,
            "metrics" to metrics,
            "stress_series" to day.stressAt.keys.sorted().map { listOf(it, day.stressAt.getValue(it)) },
        )
    }

    // ── Time ─────────────────────────────────────────────────────────────────

    /**
     * `_utc_offset`, in whole seconds: local minus UTC from the first usable
     * `monitoring_info`. Its own pass over the file, and — like the Python,
     * whose `try` wraps the whole loop — any failure along the way, including
     * a timestamp too early to have become an instant, means zero.
     */
    private fun utcOffset(bytes: ByteArray): Long = try {
        var found = 0L
        for (frame in FitReader(bytes).messages()) {
            if (frame.name != "monitoring_info") continue
            val utc = frame.get("timestamp") ?: continue
            val local = frame.get("local_timestamp") ?: continue
            val delta = (epoch(local) ?: throw PyError("TypeError")) - (epoch(utc) ?: throw PyError("TypeError"))
            if (delta > -SECONDS_PER_DAY && delta < SECONDS_PER_DAY) {
                found = delta
                break
            }
        }
        found
    } catch (e: Exception) {
        0L
    }

    /**
     * `_epoch`: `int(when.timestamp())` — the float seconds truncated toward
     * zero — for an instant, None for anything else.
     */
    private fun epoch(whenValue: Any?): Long? =
        (whenValue as? FitDateTime)?.let { it.epochMicros / 1_000_000L }

    private fun closesTheDay(whenValue: Any?, offset: Long): Any? {
        val seconds = epoch(whenValue) ?: return whenValue
        if ((seconds + offset).mod(SECONDS_PER_DAY) != 0L) return whenValue
        return FitDateTime.ofEpochSeconds(seconds - 1)
    }

    private fun localDate(whenValue: Any?, offset: Long): CivilDate? {
        val seconds = epoch(whenValue) ?: return null
        return CivilDate.fromEpochDay((seconds + offset).floorDiv(SECONDS_PER_DAY))
    }

    private fun minuteOfDay(whenValue: Any?, offset: Long): Long? {
        val seconds = epoch(whenValue) ?: return null
        return (seconds + offset).mod(SECONDS_PER_DAY) / 60
    }

    /** `_resolve_timestamp`: a 16-bit monitoring timestamp against the last full one. */
    private fun resolveTimestamp(ts16: Any?, last: Any?): Any? {
        val reference = epoch(last)
        if (ts16 == null || reference == null) return last
        val garminReference = reference - GARMIN_EPOCH
        var diff = (Py.int(ts16) and 0xFFFF) - (garminReference and 0xFFFF)
        if (diff < -32768) diff += 65536 else if (diff > 32768) diff -= 65536
        return FitDateTime.ofEpochSeconds(reference + diff)
    }
}
