// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.plan

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.parse.PyMath
import com.tracks.core.strengthplan.Loads

/**
 * Activity → planned-workout matching, the pure parts of
 * backend/app/api/training_plan/matching.py, held to it by
 * spec/fixtures/matching.json. Completion % and the endurance fingerprint are
 * [PlanAssembly.completionPct] / [PlanAssembly.advanceFingerprint].
 *
 * The result is written to synced fields (is_complete, completed activity,
 * completion %) by whichever device matches first. That is safe only because
 * every device picks the same workout from the same rows, so [pickWorkout]
 * mirrors the server's `pick_workout` exactly, including its explicit
 * (scheduled date, uid) order.
 */
object Matching {

    /** A planned workout as far as matching looks at it. */
    data class Candidate(
        val uid: String,
        /** Null for a workout added by hand; those match like generated ones. */
        val planUid: String?,
        val scheduledDate: String,
        val workoutType: String,
        val completedActivityUid: String?,
        /** The workout's sport; null reads as "" like the server's `w.sport or ""`. */
        val sport: String? = null,
    )

    /**
     * The server's `pick_workout`: same day, same sport family (a run never
     * ticks off a strength session), not rest or race, not yet matched —
     * hand-added workouts included. Candidates are sorted here by
     * (scheduled date, uid) so the caller's order cannot change the answer.
     */
    fun pickWorkout(activityDate: String, activitySport: String?, candidates: List<Candidate>): Candidate? {
        val family = PlanBase.sportFamily(activitySport ?: "")
        return candidates
            .sortedWith(compareBy<Candidate>({ it.scheduledDate }, { it.uid }))
            .firstOrNull {
                it.scheduledDate == activityDate &&
                    it.workoutType != "rest" && it.workoutType != "race" &&
                    it.completedActivityUid == null &&
                    PlanBase.sportFamily(it.sport ?: "") == family
            }
    }

    /**
     * The server's `activity_local_date`: the day [startedAt] fell on in the
     * account's zone, as `YYYY-MM-DD`, or null if it cannot be read.
     *
     * Not `startedAt.take(10)`, which is the UTC day — an evening run in
     * California matched tomorrow's workout (2026-09-30). The zone is the
     * caller's, as [offsetSecondsAt]: commonMain has no zone database, and
     * the JVM's comes in through [com.tracks.core.time.ZoneOffsets]. A start
     * time with no offset is UTC, as the server reads a naive one.
     */
    fun localDate(startedAt: String, offsetSecondsAt: (epochSeconds: Long) -> Int): String? {
        val instant = epochSeconds(startedAt) ?: return null
        val local = instant + offsetSecondsAt(instant)
        return CivilDate.fromEpochDay(local.floorDiv(86_400L)).isoformat()
    }

    private val STARTED_AT = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""",
    )

    /** Seconds since the epoch for a stored start time, in either shape the phone holds. */
    fun epochSeconds(startedAt: String): Long? {
        val m = STARTED_AT.matchEntire(startedAt.trim()) ?: return null
        val (y, mo, d, h, mi, sec, zone) = m.destructured
        val day = CivilDate(y.toInt(), mo.toInt(), d.toInt()).epochDay
        val offset = when {
            zone.isEmpty() || zone == "Z" -> 0
            else -> {
                val digits = zone.substring(1).replace(":", "")
                val seconds = digits.substring(0, 2).toInt() * 3600 + digits.substring(2, 4).toInt() * 60
                if (zone[0] == '-') -seconds else seconds
            }
        }
        return day * 86_400L + h.toInt() * 3600 + mi.toInt() * 60 + sec.toInt() - offset
    }

    /** A matched workout counts as done from 80% of its planned volume. */
    fun isComplete(completionPct: Double): Boolean = completionPct >= 0.8

    data class FieldTestUpdate(val ftpAuto: Int? = null, val thresholdHrAuto: Int? = null)

    /**
     * What a completed field test sets (`field_test_updates`). Only the 20-min
     * FTP test updates anything; manual modes are never overridden.
     */
    fun fieldTestUpdates(
        testType: String,
        best20minWatts: Double?,
        maxHr: Int?,
        ftpMode: String?,
        thresholdHrMode: String?,
    ): FieldTestUpdate {
        if (testType != "ftp20") return FieldTestUpdate()
        var ftp: Int? = null
        var lthr: Int? = null
        if (best20minWatts != null && best20minWatts != 0.0 && ftpMode == "auto") {
            val v = PyMath.roundToLong(best20minWatts * 0.95).toInt()
            if (v in 80..600) ftp = v
        }
        if (maxHr != null && maxHr != 0 && thresholdHrMode == "auto") {
            val v = PyMath.roundToLong(maxHr * 0.93).toInt()
            if (v in 110..195) lthr = v
        }
        return FieldTestUpdate(ftp, lthr)
    }

    data class SetIn(val exerciseName: String?, val weightKg: Double?, val repetitions: Int?)

    data class StrengthState(
        val estimated1rmKg: Double?,
        val lastWeightKg: Double?,
        val lastReps: Int?,
        val lastSessionVolumeKg: Double,
        val sessionsCompleted: Int,
        val progressionStage: String,
    )

    /**
     * Per-exercise state after one session's active sets
     * (`strength_fingerprint_updates`). [existing] maps an exercise to its
     * current (estimated 1RM, sessions completed); absent means never trained.
     * Result keeps first-seen exercise order, as the Python dict does.
     */
    fun strengthFingerprintUpdates(
        sets: List<SetIn>,
        existing: Map<String, Pair<Double?, Int?>>,
    ): LinkedHashMap<String, StrengthState> {
        val byExercise = LinkedHashMap<String, MutableList<SetIn>>()
        for (s in sets) {
            val name = s.exerciseName
            if (name.isNullOrEmpty()) continue
            byExercise.getOrPut(name) { mutableListOf() } += s
        }
        val out = LinkedHashMap<String, StrengthState>()
        for ((name, exSets) in byExercise) {
            val last = exSets.last()
            // Python's sum() over a generator of floats is compensated (3.12).
            val volume = PyMath.sum(exSets.map { (it.weightKg ?: 0.0) * (it.repetitions ?: 0) })
            var best: Double? = null
            for (s in exSets) {
                val w = s.weightKg
                val r = s.repetitions
                if (w != null && w != 0.0 && r != null && r in 1..10) {
                    val e = Loads.estimate1rm(w, r)
                    if (e != null && e != 0.0 && (best == null || e > best)) best = e
                }
            }
            val (prevEst, prevSessions) = existing[name] ?: (null to null)
            var est = prevEst
            if (best != null && best != 0.0 && (est == null || best > est)) est = PyMath.round(best, 1)
            val total = (prevSessions ?: 0) + 1
            val stage = when {
                total >= 100 -> "dup"
                total >= 20 -> "weekly_undulating"
                else -> "linear"
            }
            out[name] = StrengthState(est, last.weightKg, last.repetitions, PyMath.round(volume, 1), total, stage)
        }
        return out
    }
}
