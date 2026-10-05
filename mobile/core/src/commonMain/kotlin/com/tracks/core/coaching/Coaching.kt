// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.coaching

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.metrics.TrainingLoad
import com.tracks.core.parse.Py
import com.tracks.core.parse.PyMath
import com.tracks.core.parse.PyRandom
import com.tracks.core.plan.PlanBase
import com.tracks.core.strengthplan.Flexibility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The deterministic coaching engine — a port of
 * `backend/app/calculators/coaching/` (engine, signal, context, situations,
 * select, library). The LLM path is not here: it needs a provider and a
 * network, and a phone with neither still gets this.
 *
 * Only what the engine's two entry points reach is ported. `selection.py`'s
 * `_intensity_tier` / `_rank_sports`, `recommendation.py` and
 * `signal._apply_goal` belong to the recommender this one replaced and have no
 * callers on the server either.
 */

/** Load signal: CTL/ATL/TSB, ramp and the injury flag, plus the goal's phase. */
data class TrainingSignal(
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val ctlRamp: Double?,
    val injuryRiskWarning: Boolean,
    val phase: String? = null,
    val goalNote: String? = null,
)

/** One recommended session, in the server's `WorkoutRecommendation` shape. */
data class WorkoutRecommendation(
    val sport: String,
    val intensity: String,
    val durationMinutes: Long,
    val distanceKm: Double?,
    val hrMin: Long?,
    val hrMax: Long?,
    val description: String,
    val reasoning: String,
    val projectedTss: Double,
    val modality: String = "cardio",
    val title: String? = null,
    val focus: String? = null,
    val situation: String? = null,
)

/** What the engine needs of the active goal: its type and, for events, the date. */
data class CoachingGoal(val goalType: String?, val eventDate: CivilDate?, val ctlRampPerWeek: Double? = null)

/** One past session as the recommender reads it. */
data class CoachingActivity(
    val sport: String?,
    val startedAt: CivilDate?,
    val durationSeconds: Long?,
)

/** A computed day: the signal and the chosen recommendations. */
data class CoachingResult(val signal: TrainingSignal, val recommendations: List<WorkoutRecommendation>)

/** One projected day of the weekly plan. */
data class PlannedDay(
    val date: CivilDate,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val recommendation: WorkoutRecommendation?,
)

/** Everything the situation catalogue can look at. */
data class RecommenderContext(
    val today: CivilDate,
    val ctl: Double,
    val atl: Double,
    val tsb: Double,
    val ctlRamp: Double?,
    val injuryRisk: Boolean,
    val readiness: Double,
    val readinessConfidence: String,
    val dominantFamily: String?,
    val altFamily: String?,
    val cardioFamilies: List<String> = emptyList(),
    val consecutiveTrainingDays: Long = 0,
    val sameSportStreak: Long = 0,
    val lastFamily: String? = null,
    val lastModality: String? = null,
    val lastSessionLong: Boolean = false,
    val lastSessionPosterior: Boolean = false,
    val daysSinceCardio: Long? = null,
    val daysSinceStrength: Long? = null,
    val daysSinceMobility: Long? = null,
    val strengthSessions7d: Long = 0,
    val strengthSessions14d: Long = 0,
    val cardioSessions7d: Long = 0,
    val mobilitySessions14d: Long = 0,
    val totalSessions14d: Long = 0,
    val baseDuration: Long = 50,
    val thresholdHr: Double? = null,
    val hasHistory: Boolean = false,
) {
    /** Human muscle string for the dominant sport (mobility copy). */
    fun mobilityMuscles(): String =
        Coaching.humanizeMuscles(Flexibility.sportTargetMuscles(dominantFamily ?: lastFamily ?: "strength"))
}

/** A catalogue entry: when it fires, how strongly, and what it prescribes. */
class Situation(
    val key: String,
    val modality: String,
    val intensity: String,
    val title: String,
    val duration: (RecommenderContext) -> Long,
    val fires: (RecommenderContext) -> Boolean,
    val score: (RecommenderContext) -> Double,
    val focus: String? = null,
)

object Coaching {

    // ── Signal ───────────────────────────────────────────────────────────────

    private val PHASE_WEEKS = listOf(4.0 to "taper", 8.0 to "peak", 12.0 to "build", 999.0 to "base")

    private fun eventPhase(eventDate: CivilDate, today: CivilDate): String {
        val weeksOut = maxOf((eventDate.epochDay - today.epochDay) / 7.0, 0.0)
        return PHASE_WEEKS.first { weeksOut <= it.first }.second
    }

    /** `_fitness_phase`: the direction a fitness goal asks CTL to go. */
    private fun fitnessPhase(ramp: Double) = when {
        ramp > 0 -> "build"
        ramp < 0 -> "recovery"
        else -> "maintain"
    }

    /** `_build_signal`. */
    fun buildSignal(ctl: Double, atl: Double, ctl7dAgo: Double?, goal: CoachingGoal?, today: CivilDate): TrainingSignal {
        val tsb = ctl - atl
        val ramp = ctl7dAgo?.let { PyMath.round(ctl - it, 1) }
        val warning = ramp != null && ramp > 8.0
        val phase = when {
            goal != null && goal.goalType == "event" && goal.eventDate != null -> eventPhase(goal.eventDate, today)
            goal != null && goal.goalType == "fitness" -> fitnessPhase(goal.ctlRampPerWeek ?: 0.0)
            else -> null
        }
        return TrainingSignal(PyMath.round(ctl, 1), PyMath.round(atl, 1), PyMath.round(tsb, 1), ramp, warning, phase)
    }

    /** `_compute_ctl_atl`: CTL/ATL as of [end] from zero, or null before any data. */
    fun computeCtlAtl(tssByDate: Map<CivilDate, Double>, end: CivilDate): Pair<Double, Double>? {
        if (tssByDate.isEmpty()) return null
        val start = tssByDate.keys.minBy { it.epochDay }
        if (end < start) return null
        var ctl = 0.0
        var atl = 0.0
        for (day in start.epochDay..end.epochDay) {
            val tss = tssByDate[CivilDate.fromEpochDay(day)] ?: 0.0
            ctl = ctl * TrainingLoad.CTL_DECAY + tss * (1 - TrainingLoad.CTL_DECAY)
            atl = atl * TrainingLoad.ATL_DECAY + tss * (1 - TrainingLoad.ATL_DECAY)
        }
        return ctl to atl
    }

    // ── Context ──────────────────────────────────────────────────────────────

    private val MOBILITY_FRAGMENTS = listOf(
        "yoga", "pilates", "stretch", "mobility", "flexibility", "breath", "meditation", "tai_chi", "tai chi", "barre",
    )
    private val SPORT_NOUN = mapOf(
        "running" to "run", "cycling" to "ride", "mountain_biking" to "ride", "swimming" to "swim",
        "rowing" to "row", "hiking" to "hike", "walking" to "walk", "paddling" to "paddle",
    )
    private val POSTERIOR_FAMILIES = setOf("running", "hiking", "rowing")
    private const val LONG_SESSION_MIN = 75

    fun activityModality(sport: String?): String {
        val s = (sport ?: "").lowercase().replace(" ", "_")
        if (PlanBase.sportFamily(s) == "strength") return "strength"
        if (MOBILITY_FRAGMENTS.any { it.replace(" ", "_") in s }) return "mobility"
        return "cardio"
    }

    fun sportNoun(family: String?): String =
        if (family.isNullOrEmpty()) "session" else SPORT_NOUN[family] ?: family.replace("_", " ")

    fun humanizeMuscles(muscles: List<String>, limit: Int = 3): String {
        val words = muscles.take(limit).map { it.replace("_", " ") }
        return when (words.size) {
            0 -> "the areas you train most"
            1 -> words[0]
            2 -> "${words[0]} and ${words[1]}"
            else -> "${words.dropLast(1).joinToString(", ")}, and ${words.last()}"
        }
    }

    private fun daysSince(latest: CivilDate?, today: CivilDate): Long? =
        latest?.let { maxOf(today.epochDay - it.epochDay, 0L) }

    private fun baseDurationMinutes(ctl: Double): Long = when {
        ctl < 20 -> 35
        ctl < 40 -> 50
        ctl < 60 -> 70
        ctl < 80 -> 90
        else -> 110
    }

    private data class Row(val date: CivilDate, val family: String, val modality: String, val minutes: Double)

    /** `build_context`: load signal plus the shape of recent training. */
    fun buildContext(
        readinessScore: Double, readinessConfidence: String,
        ctl: Double, atl: Double, tsb: Double, ctlRamp: Double?, injuryRisk: Boolean,
        activities: List<CoachingActivity>, strengthSessionDates: List<CivilDate>?,
        thresholdHr: Double?, today: CivilDate,
    ): RecommenderContext {
        var rows = activities.mapNotNull { a ->
            val d = a.startedAt ?: return@mapNotNull null
            val sport = a.sport?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Row(d, PlanBase.sportFamily(sport), activityModality(sport), (a.durationSeconds ?: 0L) / 60.0)
        }.sortedByDescending { it.date.epochDay }
        // Both sorts stable, as Python's are: in-app sessions land after any
        // activity on the same day.
        rows = (rows + (strengthSessionDates ?: emptyList()).map { Row(it, "strength", "strength", 45.0) })
            .sortedByDescending { it.date.epochDay }

        val lastByModality = HashMap<String, CivilDate>()
        var s7 = 0L; var s14 = 0L; var c7 = 0L; var m14 = 0L; var t14 = 0L
        val d7 = today.epochDay - 7
        val d14 = today.epochDay - 14
        for (r in rows) {
            val prev = lastByModality[r.modality]
            if (prev == null || r.date > prev) lastByModality[r.modality] = r.date
            if (r.date.epochDay >= d14) {
                t14++
                if (r.modality == "strength") s14++
                if (r.modality == "mobility") m14++
            }
            if (r.date.epochDay >= d7) {
                if (r.modality == "strength") s7++
                if (r.modality == "cardio") c7++
            }
        }

        val cardioCounts = LinkedHashMap<String, Int>()
        for (r in rows) if (r.modality == "cardio") cardioCounts[r.family] = (cardioCounts[r.family] ?: 0) + 1
        val cardioFamilies = cardioCounts.keys.sortedByDescending { cardioCounts.getValue(it) }
        val dominant = cardioFamilies.firstOrNull()
        var alt = cardioFamilies.firstOrNull { it != dominant }
        if (alt == null && dominant != null) alt = if (dominant != "cycling") "cycling" else "running"

        val trainingDays = rows.map { it.date.epochDay }.toSortedSet().reversed()
        var streak = 0L
        if (trainingDays.isNotEmpty() && today.epochDay - trainingDays.first() <= 1) {
            var cursor = trainingDays.first()
            for (d in trainingDays) {
                if (d == cursor) { streak++; cursor-- } else if (d < cursor) break
            }
        }

        var sameSport = 0L
        if (rows.isNotEmpty()) {
            val top = rows[0].family
            for (r in rows) { if (r.family == top) sameSport++ else break }
        }

        val lastFamily = rows.firstOrNull()?.family
        val lastModality = rows.firstOrNull()?.modality
        val lastMinutes = rows.firstOrNull()?.minutes ?: 0.0
        val lastLong = lastMinutes >= LONG_SESSION_MIN

        return RecommenderContext(
            today = today, ctl = ctl, atl = atl, tsb = tsb, ctlRamp = ctlRamp, injuryRisk = injuryRisk,
            readiness = readinessScore, readinessConfidence = readinessConfidence,
            dominantFamily = dominant, altFamily = alt, cardioFamilies = cardioFamilies,
            consecutiveTrainingDays = streak, sameSportStreak = sameSport,
            lastFamily = lastFamily, lastModality = lastModality,
            lastSessionLong = lastLong, lastSessionPosterior = lastLong && lastFamily in POSTERIOR_FAMILIES,
            daysSinceCardio = daysSince(lastByModality["cardio"], today),
            daysSinceStrength = daysSince(lastByModality["strength"], today),
            daysSinceMobility = daysSince(lastByModality["mobility"], today),
            strengthSessions7d = s7, strengthSessions14d = s14, cardioSessions7d = c7,
            mobilitySessions14d = m14, totalSessions14d = t14,
            baseDuration = baseDurationMinutes(ctl), thresholdHr = thresholdHr, hasHistory = rows.isNotEmpty(),
        )
    }

    // ── The situation catalogue ──────────────────────────────────────────────

    private fun factor(f: Double, lo: Long = 20): (RecommenderContext) -> Long =
        { c -> maxOf(lo, PyMath.roundToLong(c.baseDuration * f)) }

    private fun fixed(n: Long): (RecommenderContext) -> Long = { n }
    private fun hasCardio(c: RecommenderContext) = c.dominantFamily != null
    private fun strengthDue(c: RecommenderContext) = c.daysSinceStrength == null || c.daysSinceStrength >= 2

    /** `SITUATIONS`, in the server's order — ties in score break by key, not position. */
    val SITUATIONS: List<Situation> = listOf(
        Situation("rest.injury_risk_ramp", "rest", "rest", "Rest day", fixed(0),
            { it.injuryRisk }, { 100.0 }),
        Situation("rest.overdue", "rest", "rest", "Rest day", fixed(0),
            { it.consecutiveTrainingDays >= 6 || (it.tsb < -30 && it.readiness < 50) },
            { 94.0 + minOf(it.consecutiveTrainingDays, 12L) * 0.3 }),

        Situation("mobility.active_recovery", "mobility", "mobility", "Recovery mobility", fixed(15),
            { it.tsb < -20 || it.readiness < 50 },
            { 88.0 + maxOf(0.0, (50 - it.readiness) * 0.2) }, focus = "recovery"),
        Situation("mobility.post_long_flush", "mobility", "mobility", "Targeted mobility", fixed(15),
            { it.lastSessionLong && (it.daysSinceCardio != null && it.daysSinceCardio <= 1) },
            { 58.0 }, focus = "sport"),
        Situation("mobility.neglected", "mobility", "mobility", "Mobility", fixed(12),
            { (it.daysSinceMobility != null && it.daysSinceMobility >= 14) || (it.daysSinceMobility == null && it.totalSessions14d >= 4) },
            { if (it.daysSinceMobility != null && it.daysSinceMobility >= 14) 55.0 else 42.0 }, focus = "sport"),
        Situation("mobility.sport_specific_tightness", "mobility", "mobility", "Mobility", fixed(15),
            { it.hasHistory }, { 38.0 }, focus = "sport"),
        Situation("mobility.rest_day_gentle", "mobility", "mobility", "Gentle mobility", fixed(10),
            { it.consecutiveTrainingDays >= 6 || it.injuryRisk || it.tsb < -30 }, { 20.0 }, focus = "sport"),

        Situation("strength.posterior_caution", "strength", "strength", "Upper-body strength", fixed(45),
            { it.lastSessionPosterior && strengthDue(it) && it.readiness >= 55 }, { 67.0 }, focus = "upper body"),
        Situation("strength.overdue", "strength", "strength", "Strength", fixed(45),
            { it.daysSinceStrength != null && it.daysSinceStrength >= 7 && it.readiness >= 55 },
            { 66.0 + minOf(it.daysSinceStrength?.takeIf { d -> d != 0L } ?: 7L, 21L) * 0.2 }, focus = "full body"),
        Situation("strength.heavy_fresh", "strength", "heavy", "Heavy strength", fixed(50),
            { it.tsb >= 5 && it.readiness >= 75 && strengthDue(it) }, { 64.0 }, focus = "lower body"),
        Situation("strength.return_after_layoff", "strength", "moderate", "Strength restart", fixed(35),
            { it.daysSinceStrength != null && it.daysSinceStrength >= 21 }, { 62.0 }, focus = "full body"),
        Situation("strength.maintenance", "strength", "strength", "Strength", fixed(45),
            { it.strengthSessions14d >= 2 && strengthDue(it) && it.readiness >= 55 }, { 57.0 }, focus = "full body"),
        Situation("strength.sport_support", "strength", "strength", "Support strength", fixed(40),
            { hasCardio(it) && it.strengthSessions14d == 0L && it.daysSinceStrength == null && it.readiness >= 55 },
            { 54.0 }, focus = "lower body"),

        Situation("cardio.recovery_flush", "cardio", "recovery", "Recovery", factor(0.5),
            { hasCardio(it) && it.lastSessionLong && it.tsb < -5 && it.readiness >= 50 }, { 70.0 }),
        Situation("cardio.quality_intervals", "cardio", "quality", "Intervals", factor(0.7),
            { hasCardio(it) && it.tsb >= 5 && it.readiness >= 78 && it.totalSessions14d >= 3 }, { 63.0 }),
        Situation("cardio.tempo_productive", "cardio", "tempo", "Tempo", factor(0.8),
            { hasCardio(it) && -30 <= it.tsb && it.tsb <= -8 && it.readiness >= 70 }, { 61.0 }),
        Situation("cardio.big_aerobic_base", "cardio", "aerobic", "Aerobic base", factor(1.3),
            { hasCardio(it) && it.tsb > 5 && it.readiness >= 65 },
            { 60.0 + minOf(maxOf(it.tsb, 0.0), 20.0) * 0.1 }),
        Situation("cardio.rebuild_base", "cardio", "aerobic", "Rebuild base", factor(1.0),
            { hasCardio(it) && it.tsb > 25 }, { 56.0 }),
        Situation("cardio.cross_train_swap", "cardio", "easy", "Cross-train", factor(0.9),
            { hasCardio(it) && it.sameSportStreak >= 4 && it.readiness >= 55 }, { 52.0 }),
        Situation("cardio.consistency_nudge", "cardio", "easy", "Easy start", factor(0.7, lo = 25),
            { it.hasHistory && it.totalSessions14d <= 2 && it.tsb > -5 }, { 46.0 }),
        Situation("cardio.easy_conversational", "cardio", "easy", "Easy session", factor(1.0),
            { hasCardio(it) && it.readiness >= 50 }, { 44.0 }),

        Situation("onboarding.no_history", "cardio", "easy", "Getting started", fixed(30),
            { !it.hasHistory }, { 15.0 }),
    )

    // ── Rendering ────────────────────────────────────────────────────────────

    /** The paragraph library, merged across files in name order as the server loads it. */
    val PARAGRAPHS: Map<String, Map<String, List<String>>> by lazy {
        val out = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
        for ((_, text) in COACHING_PARAGRAPH_FILES.sortedBy { it.first }) {
            val data = Json.parseToJsonElement(text) as JsonObject
            for ((key, forms) in data) {
                val bucket = out.getOrPut(key) { LinkedHashMap() }
                for ((form, variants) in forms as JsonObject) {
                    bucket.getOrPut(form) { mutableListOf() } += (variants as JsonArray).map { (it as JsonPrimitive).content }
                }
            }
        }
        out
    }

    private fun variants(key: String, form: String): List<String> {
        val bucket = PARAGRAPHS[key] ?: emptyMap()
        return bucket[form]?.takeIf { it.isNotEmpty() }
            ?: bucket["full"]?.takeIf { it.isNotEmpty() }
            ?: bucket["compact"]?.takeIf { it.isNotEmpty() }
            ?: emptyList()
    }

    /** `render`: a variant chosen stably per day, slots filled as `str.format_map` fills them. */
    fun render(key: String, form: String, slots: Map<String, Any?>, seed: String = ""): String {
        val vs = variants(key, form)
        if (vs.isEmpty()) return Py.str(slots["_fallback"] ?: "")
        val template = vs[PyRandom("$seed:$key:$form").randrange(vs.size)]
        return try {
            formatMap(template, slots).trim()
        } catch (e: IllegalArgumentException) {
            Py.str(if (slots.containsKey("_fallback")) slots["_fallback"] else template)
        }
    }

    /**
     * `str.format_map` with the server's `_SafeSlots` (a missing name renders
     * empty), for the specs the library uses: none, `.Nf` and `+.Nf`.
     */
    internal fun formatMap(template: String, slots: Map<String, Any?>): String {
        val out = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> { out.append('{'); i += 2 }
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> { out.append('}'); i += 2 }
                c == '{' -> {
                    val end = template.indexOf('}', i)
                    require(end > i) { "Single '{' encountered in format string" }
                    val field = template.substring(i + 1, end)
                    val name = field.substringBefore(':')
                    val spec = if (':' in field) field.substringAfter(':') else ""
                    out.append(formatValue(if (slots.containsKey(name)) slots[name] else "", spec))
                    i = end + 1
                }
                c == '}' -> throw IllegalArgumentException("Single '}' encountered in format string")
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun formatValue(v: Any?, spec: String): String {
        if (spec.isEmpty()) return Py.str(v)
        val m = Regex("""(\+)?\.(\d+)f""").matchEntire(spec) ?: throw IllegalArgumentException("unsupported spec $spec")
        val x = when (v) { is Long -> v.toDouble(); is Int -> v.toDouble(); is Double -> v; else -> throw IllegalArgumentException("not a number") }
        val body = PyMath.fixed(x, m.groupValues[2].toInt())
        return if (m.groupValues[1] == "+" && !body.startsWith("-")) "+$body" else body
    }

    // ── Selection ────────────────────────────────────────────────────────────

    private data class Zone(val lo: Double, val hi: Double, val name: String)

    private val ZONE = mapOf(
        "recovery" to Zone(0.60, 0.70, "Zone 1, very easy"),
        "easy" to Zone(0.70, 0.80, "Zone 2, easy"),
        "aerobic" to Zone(0.75, 0.83, "Zone 2 aerobic"),
        "tempo" to Zone(0.83, 0.91, "Zone 3 tempo"),
        "quality" to Zone(0.92, 1.02, "Zone 4-5, hard"),
    )

    private val TSS_PER_HOUR = mapOf(
        "recovery" to 30L, "easy" to 45L, "aerobic" to 60L, "tempo" to 80L, "quality" to 100L,
        "heavy" to 50L, "strength" to 45L, "moderate" to 40L, "mobility" to 15L, "rest" to 0L,
    )

    private fun present(lthr: Double?) = lthr != null && lthr != 0.0

    private fun zoneDesc(intensity: String, lthr: Double?): String {
        val z = ZONE[intensity] ?: return "an easy pace"
        return if (present(lthr)) "${z.name} (${(lthr!! * z.lo).toLong()} to ${(lthr * z.hi).toLong()} bpm)" else z.name
    }

    private fun hrRange(intensity: String, lthr: Double?): Pair<Long?, Long?> {
        val z = ZONE[intensity]
        if (z == null || !present(lthr)) return null to null
        return (lthr!! * z.lo).toLong() to (lthr * z.hi).toLong()
    }

    private fun daysSinceFor(sit: Situation, c: RecommenderContext): Long = when (sit.modality) {
        "strength" -> c.daysSinceStrength ?: 0L
        "mobility" -> c.daysSinceMobility ?: 0L
        else -> 0L
    }

    private fun slots(sit: Situation, c: RecommenderContext, duration: Long): Map<String, Any?> = mapOf(
        "tsb" to c.tsb,
        "readiness" to PyMath.roundToLong(c.readiness),
        "ramp" to PyMath.round(c.ctlRamp?.takeIf { it != 0.0 } ?: 0.0, 1),
        "streak" to c.consecutiveTrainingDays,
        "recent_count" to c.totalSessions14d,
        "duration" to duration,
        "sport" to (if (c.dominantFamily != null) sportNoun(c.dominantFamily) else "cardio session"),
        "alt_sport" to (if (c.altFamily != null) sportNoun(c.altFamily) else "something different"),
        "zone_desc" to zoneDesc(sit.intensity, c.thresholdHr),
        "focus" to (if (sit.modality == "strength") sit.focus else "the work you do"),
        "muscle_targets" to c.mobilityMuscles(),
        "days_since" to daysSinceFor(sit, c),
        "_fallback" to "${sit.title}: about $duration min.",
    )

    private fun toRecommendation(sit: Situation, c: RecommenderContext, form: String, seed: String): WorkoutRecommendation {
        val duration = sit.duration(c)
        val text = render(sit.key, form, slots(sit, c, duration), seed)
        val (hrMin, hrMax) = if (sit.modality == "cardio") hrRange(sit.intensity, c.thresholdHr) else (null to null)
        val tss = PyMath.round((TSS_PER_HOUR[sit.intensity] ?: 45L) * duration / 60.0, 1)
        return WorkoutRecommendation(
            sport = if (sit.modality == "cardio") c.dominantFamily ?: "cardio" else sit.modality,
            intensity = sit.intensity, durationMinutes = duration, distanceKm = null,
            hrMin = hrMin, hrMax = hrMax, description = text, reasoning = "", projectedTss = tss,
            modality = sit.modality, title = sit.title,
            focus = when {
                sit.modality == "strength" -> sit.focus
                sit.modality == "mobility" && sit.focus == "sport" -> c.mobilityMuscles()
                else -> null
            },
            situation = sit.key,
        )
    }

    /** `select_recommendations`: a hero, then alternates of other modalities. */
    fun selectRecommendations(c: RecommenderContext, n: Int = 3, seed: String = ""): List<WorkoutRecommendation> {
        var fired = SITUATIONS.filter { runCatching { it.fires(c) }.getOrDefault(false) }
            .sortedWith(compareBy<Situation>({ -(runCatching { it.score(c) }.getOrDefault(0.0)) }, { it.key }))
        if (fired.isEmpty()) fired = listOf(SITUATIONS.last())

        val hero = fired[0]
        val recs = mutableListOf(toRecommendation(hero, c, "full", seed))
        val allowed = if (hero.modality == "rest") setOf("mobility") else null
        val used = mutableSetOf(hero.modality)
        for (sit in fired.drop(1)) {
            if (recs.size >= n) break
            if (sit.modality in used) continue
            if (allowed != null && sit.modality !in allowed) continue
            recs += toRecommendation(sit, c, "compact", seed)
            used += sit.modality
        }
        return recs
    }

    // ── Entry points ─────────────────────────────────────────────────────────

    /** `compute_recommendations`: today's hero plus alternates. */
    fun computeRecommendations(
        readinessScore: Double, readinessConfidence: String,
        ctl: Double, atl: Double, ctl7dAgo: Double?,
        activities: List<CoachingActivity>, goal: CoachingGoal?, thresholdHr: Double?, today: CivilDate,
        n: Int = 3, strengthSessionDates: List<CivilDate>? = null,
    ): CoachingResult {
        val signal = buildSignal(ctl, atl, ctl7dAgo, goal, today)
        val ctx = buildContext(
            readinessScore, readinessConfidence, ctl, atl, signal.tsb, signal.ctlRamp, signal.injuryRiskWarning,
            activities, strengthSessionDates, thresholdHr, today,
        )
        return CoachingResult(signal, selectRecommendations(ctx, n, today.isoformat()))
    }

    /** `compute_weekly_plan`: seven days forward, each day's TSS fed into the next. */
    fun computeWeeklyPlan(
        readinessScore: Double, readinessConfidence: String,
        tssByDate: Map<CivilDate, Double>, activities: List<CoachingActivity>,
        goal: CoachingGoal?, thresholdHr: Double?, today: CivilDate,
    ): List<PlannedDay> {
        val projected = LinkedHashMap(tssByDate)
        val plan = mutableListOf<PlannedDay>()
        for (i in 0 until 7) {
            val day = CivilDate.fromEpochDay(today.epochDay + i)
            val (ctl, atl) = computeCtlAtl(projected, day) ?: (0.0 to 0.0)
            val ctl7 = computeCtlAtl(projected, CivilDate.fromEpochDay(day.epochDay - 7))?.first
            val rec = computeRecommendations(
                readinessScore, readinessConfidence, ctl, atl, ctl7, activities, goal, thresholdHr, day, n = 1,
            ).recommendations.firstOrNull()
            plan += PlannedDay(day, PyMath.round(ctl, 1), PyMath.round(atl, 1), PyMath.round(ctl - atl, 1), rec)
            if (rec != null) projected[day] = (projected[day] ?: 0.0) + rec.projectedTss
        }
        return plan
    }
}
