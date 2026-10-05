// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.core.api.ActivityDetail
import com.tracks.core.api.Lap
import com.tracks.core.api.ClimbSplit
import com.tracks.core.api.StrengthSet
import com.tracks.core.api.TrackPoint
import com.tracks.core.spec.ZoneRange
import com.tracks.core.format.distance
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import com.tracks.core.format.pace
import com.tracks.core.format.speed
import kotlin.math.roundToInt

/**
 * Everything a layout can draw from.
 *
 * One object rather than a long parameter list, because the parts arrive
 * independently and can each fail on their own: [detail] needs only a token,
 * [track] needs an open vault, and [laps] may legitimately be empty. A layout
 * reads what it has and omits what it does not, which is why every field past
 * the first is nullable or empty rather than a loading flag.
 */
data class ActivityDetailData(
    val detail: ActivityDetail,
    val sportType: String,
    val laps: List<Lap> = emptyList(),
    val track: List<TrackPoint> = emptyList(),
    val climbs: List<ClimbSplit> = emptyList(),
    val sets: List<StrengthSet> = emptyList(),
    /**
     * The athlete's heart-rate zones, or empty when the server has no threshold
     * for them. Passed in rather than derived here so the zone model stays the
     * generated one from spec/zones.yaml.
     */
    val hrZones: List<ZoneRange> = emptyList(),
    /** Set when the GPS could not be fetched — a locked vault, or no signal. */
    val trackError: String? = null,
) {
    val hasGps: Boolean get() = track.any { it.lat != null && it.lng != null }
}

// ── The layouts ──────────────────────────────────────────────────────────────
//
// Each is a different *emphasis* on the same record, not a different set of
// facts: a runner reads pace first, a cyclist reads speed and power, someone
// lifting has no distance at all. Sharing StatGrid keeps them from drifting
// into four inconsistent ways of drawing the same number.

@Composable
fun RunningLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                // Pace before speed: a runner does not think in km/h.
                paceStat(d.distanceMeters, d.durationSeconds),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.avgCadence?.let { Stat("$it", "Cadence") },
                ascentStat(d.totalAscent),
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = true)
    }
}

@Composable
fun HikingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                // Ascent leads for hiking — it is the number that describes the day.
                ascentStat(d.totalAscent),
                d.totalDescent?.takeIf { it > 0 }?.let { Stat(elevation(it), "Descent") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                paceStat(d.distanceMeters, d.durationSeconds),
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = true)
    }
}

@Composable
fun CyclingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgSpeed?.let { Stat(speed(it), "Avg speed") },
                d.maxSpeed?.let { Stat(speed(it), "Max speed") },
                d.avgPower?.let { Stat("$it W", "Avg power") },
                d.normalizedPower?.let { Stat("$it W", "Normalised") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                ascentStat(d.totalAscent),
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

@Composable
fun StrengthLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        // No distance, no pace — the fields a lifting session actually has.
        StatGrid(
            listOfNotNull(
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
                d.lapCount?.let { Stat("$it", "Sets") },
            )
        )
        TrainingEffectCard(d)
    }
}

/**
 * Downhill skiing and snowboarding.
 *
 * Descent, not ascent — the lift did the climbing, and total ascent on a ski
 * day describes the chairlift rather than the skiing. Max speed leads for the
 * same reason it does on the web: it is the number people actually compare.
 */
@Composable
fun SkiingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                d.maxSpeed?.let { Stat(speed(it), "Max speed") },
                d.avgSpeed?.let { Stat(speed(it), "Avg speed") },
                d.totalDescent?.takeIf { it > 0 }?.let { Stat(elevation(it), "Vertical descent") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/** Cross-country and snowshoeing — climbing is back, so ascent returns. */
@Composable
fun NordicSkiingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgSpeed?.let { Stat(speed(it), "Avg speed") },
                ascentStat(d.totalAscent),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = true)
    }
}

/**
 * Swimming.
 *
 * Pace per 100 m rather than per kilometre — the only unit a swimmer uses —
 * and cadence is a stroke rate, so it is labelled as one. Distance can be zero
 * for an open-water swim the watch lost, which is why every field is optional.
 */
@Composable
fun SwimmingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                pacePer(d.distanceMeters, d.durationSeconds, metres = 100, label = "Pace /100m"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.avgCadence?.let { Stat("$it", "Stroke rate") },
                d.lapCount?.let { Stat("$it", "Lengths") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/** Rowing — pace per 500 m, the erg convention, plus power and stroke rate. */
@Composable
fun RowingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                pacePer(d.distanceMeters, d.durationSeconds, metres = 500, label = "Pace /500m"),
                d.avgPower?.let { Stat("$it W", "Avg power") },
                d.avgCadence?.let { Stat("$it", "Stroke rate") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/** Kayak, canoe, SUP, surfing — speed-led, with stroke rate where recorded. */
@Composable
fun PaddlingLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgSpeed?.let { Stat(speed(it), "Avg speed") },
                d.maxSpeed?.let { Stat(speed(it), "Max speed") },
                d.avgCadence?.let { Stat("$it", "Stroke rate") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/**
 * Elliptical, stair climber, and the rest of the gym machines.
 *
 * Duration first: these are time-based sessions, and the "distance" a machine
 * reports is a manufacturer's estimate rather than ground covered.
 */
@Composable
fun FitnessEquipmentLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.distanceMeters?.takeIf { it > 0 }?.let { Stat(distance(it), "Distance") },
                d.avgCadence?.let { Stat("$it", "Cadence") },
                d.avgPower?.let { Stat("$it W", "Avg power") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/**
 * Yoga, pilates, breathwork, meditation.
 *
 * Time and heart rate, and nothing else — there is no distance, no pace and no
 * power, and showing empty slots for them would imply the watch failed rather
 * than that the concepts do not apply.
 */
@Composable
fun MindBodyLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
    }
}

/** Ball and combat sports — duration and effort; distance only if tracked. */
@Composable
fun TeamSportsLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.distanceMeters?.takeIf { it > 0 }?.let { Stat(distance(it), "Distance") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/**
 * Golf, where each hole is a lap.
 *
 * Strokes and putts live on the lap rows rather than the activity, because
 * that is how the watch records them — so the totals are summed here rather
 * than read off a summary that does not carry them.
 */
@Composable
fun GolfLayout(data: ActivityDetailData) {
    val d = data.detail
    val holes = data.laps.count { it.totalStrokes != null }.takeIf { it > 0 }
    val strokes = data.laps.sumOf { it.totalStrokes ?: 0 }.takeIf { it > 0 }
    val putts = data.laps.sumOf { it.totalPutts ?: 0 }.takeIf { it > 0 }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                holes?.let { Stat("$it", "Holes") },
                strokes?.let { Stat("$it", "Strokes") },
                putts?.let { Stat("$it", "Putts") },
                Stat(elapsed(d.durationSeconds), "Time"),
                d.distanceMeters?.takeIf { it > 0 }?.let { Stat(distance(it), "Distance") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        GolfScorecard(data.laps)
        TrainingEffectCard(d)
    }
}

/**
 * Triathlon and other multisport.
 *
 * The summary is the whole event; the per-discipline breakdown lives in the
 * laps, which is why the lap table matters more here than anywhere else.
 */
@Composable
fun TriathlonLayout(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(distance(d.distanceMeters), "Distance"),
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                ascentStat(d.totalAscent),
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = true)
    }
}

/**
 * The layout for a sport nobody has written a layout for.
 *
 * ## Two shapes, not one
 *
 * A GPS activity and an indoor one are not the same screen with some numbers
 * missing. Distance, pace and a route are the frame an outdoor session is read
 * in; take them away and what is left — duration, heart rate, effort — is
 * buried under a grid of dashes. So this picks between the two, exactly as the
 * web app's `FallbackLayout` does: with a track, the numbers that describe
 * ground covered; without one, the numbers that describe effort spent.
 *
 * Nothing about this is a guess at the sport. It cannot be — the whole point of
 * being the fallback is that the sport is unrecognised — so it asks the
 * *record* what it has instead of asking the taxonomy what it is. An activity
 * type this app has never heard of still gets a sensible screen the first time
 * it is opened, rather than after someone adds a layout for it.
 *
 * The charts, splits, zones and recording details below come from the detail
 * screen itself and apply to every sport, so they are not repeated here.
 */
@Composable
fun FallbackLayout(data: ActivityDetailData) {
    if (data.hasGps || (data.detail.distanceMeters ?: 0.0) > 0) {
        TravelledFallback(data)
    } else {
        StationaryFallback(data)
    }
}

/**
 * Something that went somewhere.
 *
 * Speed rather than pace, deliberately: pace is a runner's unit and this is by
 * definition not a sport we can claim to know. Both would be a hedge, and a
 * screen that shows the same number twice in two units reads as a bug.
 */
@Composable
private fun TravelledFallback(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                d.distanceMeters?.takeIf { it > 0 }?.let { Stat(distance(it), "Distance") },
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgSpeed?.takeIf { it > 0 }?.let { Stat(speed(it), "Avg speed") },
                d.maxSpeed?.takeIf { it > 0 }?.let { Stat(speed(it), "Max speed") },
                ascentStat(d.totalAscent),
                d.totalDescent?.takeIf { it > 0 }?.let { Stat(elevation(it), "Descent") },
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.avgPower?.let { Stat("$it W", "Avg power") },
                d.totalCalories?.let { Stat("$it", "Calories") },
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)
    }
}

/**
 * Something that happened in one place.
 *
 * Time leads, because it is the only dimension the session has. Then the heart
 * rate, which for an indoor sport is the whole of what was measured, and then
 * whatever else the watch happened to record — ascent is here on purpose, since
 * a climbing wall and a stair machine both produce it without going anywhere.
 */
@Composable
private fun StationaryFallback(data: ActivityDetailData) {
    val d = data.detail
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat(elapsed(d.durationSeconds), "Time"),
                d.avgHeartRate?.let { Stat("$it", "Avg HR") },
                d.maxHeartRate?.let { Stat("$it", "Max HR") },
                d.totalCalories?.let { Stat("$it", "Calories") },
                ascentStat(d.totalAscent),
                d.avgCadence?.let { Stat("$it", "Cadence") },
                d.avgPower?.let { Stat("$it W", "Avg power") },
                effortStat(d),
            )
        )
        TrainingEffectCard(d)
        LapsCard(data.laps, showPace = false)

        // Said plainly, and only when it is true. Every card below this one
        // draws from the track or the laps, so with neither the screen ends
        // here — and a screen that simply stops looks like one that failed.
        if (data.track.isEmpty() && data.laps.isEmpty()) {
            Text(
                if (data.trackError != null) {
                    "No recorded detail could be loaded for this activity."
                } else {
                    "This activity has no recorded detail beyond the summary above."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How hard it was, by whichever of the two the server worked out.
 *
 * `effective_tss` is the one to prefer where both exist: it is what the
 * training load actually used, after the server's own adjustments.
 */
private fun effortStat(detail: ActivityDetail): Stat? =
    (detail.effectiveTss ?: detail.trainingStressScore)
        ?.takeIf { it > 0 }
        ?.let { Stat("${it.roundToInt()}", "Load") }

// ── Shared stat helpers ──────────────────────────────────────────────────────

/**
 * Pace from the summary, not from a stored average.
 *
 * `pace()` takes metres per second, so the average has to be derived —
 * deliberately from distance/duration rather than from `avg_speed`, because
 * the two disagree on any activity with auto-pause: `avg_speed` is a moving
 * average, while the pace a runner recognises from their watch is over
 * elapsed time. Returns null rather than "0:00" when either half is missing.
 */
private fun paceStat(distanceMeters: Double?, durationSeconds: Int?): Stat? {
    if (distanceMeters == null || durationSeconds == null) return null
    if (distanceMeters <= 0 || durationSeconds <= 0) return null
    return Stat(pace(distanceMeters / durationSeconds), "Pace")
}

private fun ascentStat(totalAscent: Double?): Stat? =
    totalAscent?.takeIf { it > 0 }?.let { Stat(elevation(it), "Ascent") }

/**
 * Pace over an arbitrary distance, for the sports that do not count in
 * kilometres.
 *
 * Swimmers read seconds per 100 m and rowers seconds per 500 m; neither
 * recognises min/km, and converting for them would be showing the right number
 * in a unit nobody uses. Formatted here rather than through `core.format.pace`
 * because that one is fixed to a kilometre or a mile.
 */
private fun pacePer(
    distanceMeters: Double?,
    durationSeconds: Int?,
    metres: Int,
    label: String,
): Stat? {
    if (distanceMeters == null || durationSeconds == null) return null
    if (distanceMeters <= 0 || durationSeconds <= 0) return null
    val secondsPerUnit = (durationSeconds / (distanceMeters / metres)).roundToInt()
    val minutes = secondsPerUnit / 60
    val seconds = secondsPerUnit % 60
    return Stat("$minutes:${seconds.toString().padStart(2, '0')}", label)
}
