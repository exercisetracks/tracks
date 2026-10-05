// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.tracks.app.ui.components.fittedScrollState
import com.tracks.app.ui.components.fittedZoomState
import com.tracks.app.ui.dashboard.specColor
import com.tracks.core.api.ActivityDetail
import com.tracks.core.api.CLIMB_RESULT_SEND
import com.tracks.core.api.ClimbSplit
import com.tracks.core.api.StrengthSet
import com.tracks.core.api.TrackPoint
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import com.tracks.core.spec.ZoneRange
import kotlin.math.roundToInt

/**
 * The parts of an activity that are not one number.
 *
 * Climbs, strength sets, heart-rate zone distribution, and the recorded streams
 * — the four things the web detail page shows that the phone did not. Each is
 * absent rather than empty when the activity has no such data, which is the
 * common case: a run has no sets, a strength session has no climbs, and a
 * treadmill has no GPS.
 */

// ── Streams ──────────────────────────────────────────────────────────────────

/**
 * Heart rate, elevation, and speed over the course of the activity.
 *
 * Three separate charts rather than three series on one. They share no unit and
 * barely share a magnitude — bpm in the 100s, metres in the 1000s, m/s in the
 * single digits — so a shared axis would flatten two of them into the baseline.
 *
 * X is the sample index and carries no axis. It is *approximately* elapsed
 * time — LTTB keeps one point per equal-sized bucket — but only approximately,
 * and the earlier note here claimed the spacing was exactly even, which is what
 * the zone card was wrong to rely on. For the shape of a curve the difference
 * does not show; anywhere a real duration is needed, [sampleSeconds] reads the
 * timestamps instead.
 */
@Composable
fun StreamsCard(track: List<TrackPoint>, modifier: Modifier = Modifier) {
    val hr = track.mapNotNull { it.heartRate?.toDouble() }
    val altitude = track.mapNotNull { it.altitude }
    val speed = track.mapNotNull { it.speed }
    val power = track.mapNotNull { it.power?.toDouble() }
    val cadence = track.mapNotNull { it.cadence?.toDouble() }

    val streams = listOf(hr, altitude, speed, power, cadence)
    if (streams.none { it.size >= MIN_STREAM }) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Streams")
        StreamChart("Heart rate", hr, MaterialTheme.colorScheme.error, "bpm")
        StreamChart("Elevation", altitude, MaterialTheme.colorScheme.primary, "m", filled = true)
        StreamChart("Speed", speed, MaterialTheme.colorScheme.secondary, "m/s")
        StreamChart("Power", power, MaterialTheme.colorScheme.tertiary, "W")
        // Cadence was recorded and never drawn. It is the one stream that means
        // something different per sport — rpm on a bike, steps per minute
        // running, strokes swimming — so it carries no unit rather than a
        // wrong one.
        StreamChart("Cadence", cadence, MaterialTheme.colorScheme.secondary, "")
    }
}

private const val MIN_STREAM = 5

@Composable
private fun StreamChart(
    label: String,
    values: List<Double>,
    color: Color,
    unit: String,
    filled: Boolean = false,
) {
    if (values.size < MIN_STREAM) return

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        producer.runTransaction { lineSeries { series(values) } }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val shape = summarise(values)
                Text(
                    // Min as well as average and max. For half these channels
                    // the low is the interesting end — the lowest heart rate in
                    // a session is the recovery, the lowest altitude the valley
                    // floor — and the headline used to drop it.
                    "min ${shape?.min?.roundToInt()} · avg ${shape?.average?.roundToInt()} · " +
                        "max ${shape?.max?.roundToInt()}" +
                        if (unit.isEmpty()) "" else " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProvideVicoTheme(
                rememberM3VicoTheme(
                    // The axis furniture the rest of the app uses. See
                    // `tracksVicoTheme` on the dashboard, which this mirrors —
                    // an activity's own charts are read in the same sitting as
                    // the ones that led to them.
                    lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            ) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(
                            lineProvider = LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(fill(color)),
                                    areaFill = if (filled) {
                                        LineCartesianLayer.AreaFill.single(
                                            fill(color.copy(alpha = 0.20f)),
                                        )
                                    } else {
                                        null
                                    },
                                ),
                            ),
                        ),
                        startAxis = VerticalAxis.rememberStart(),
                        // No bottom axis: see the note on StreamsCard.
                    ),
                    modelProducer = producer,
                scrollState = fittedScrollState(),
                zoomState = fittedZoomState(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                )
            }
        }
    }
}

// ── Heart-rate zones ─────────────────────────────────────────────────────────

/**
 * Time in each heart-rate zone, as a stacked bar.
 *
 * Computed on the phone from the recorded samples rather than fetched, because
 * the server has no per-activity zone endpoint — and the arithmetic is a
 * bucket-sum, which is worth doing locally rather than adding a round trip.
 *
 * The boundaries come from `spec/zones.yaml` through the generated Kotlin, so
 * this cannot drift from the browser. That matters here more than most places:
 * the web app's own HRHistogram hard-codes zone percentages that **disagree**
 * with the server's model, which is a divergence this port was explicitly meant
 * not to reproduce.
 *
 * ## Time, not samples
 *
 * This used to count samples and call the result a share of the session, on the
 * stated grounds that the track is evenly resampled. It is not — the server
 * downsamples with LTTB, which is even in *bucket count* rather than in time,
 * and below the resolution cap the spacing is whatever the watch recorded. So
 * the samples are weighted by the intervals they actually cover (see
 * [sampleSeconds]), which also makes the one number anybody wants from this card
 * — twenty minutes at threshold — sayable at all.
 *
 * Counting is kept as the fallback for a track whose timestamps are unusable. A
 * rough percentage still beats a missing card; a fabricated duration does not,
 * so the times are simply absent in that case.
 */
@Composable
fun HeartRateZonesCard(
    track: List<TrackPoint>,
    zones: List<ZoneRange>,
    modifier: Modifier = Modifier,
) {
    if (zones.isEmpty() || track.count { it.heartRate != null } < MIN_STREAM) return

    val breakdown = remember(track, zones) { zoneBreakdown(track, zones) } ?: return
    val total = breakdown.weights.sum().takeIf { it > 0 } ?: return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Heart-rate zones")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // One bar, segmented — the shape of an effort at a glance,
                // which a list of five percentages does not give.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(Tokens.Radius.base)),
                ) {
                    breakdown.weights.forEachIndexed { index, weight ->
                        if (weight <= 0) return@forEachIndexed
                        Box(
                            Modifier
                                .weight(weight.toFloat())
                                .fillMaxHeight()
                                .background(zoneColor(zones[index], index)),
                        )
                    }
                }
                breakdown.weights.forEachIndexed { index, weight ->
                    if (weight <= 0) return@forEachIndexed
                    val zone = zones[index]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .width(10.dp)
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(zoneColor(zone, index)),
                        )
                        Text(
                            "${zone.name} · ${zone.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (breakdown.timed) {
                            Text(
                                elapsed(weight),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            "${(weight * 100.0 / total).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Per-zone weights, and whether they are seconds or merely sample counts. */
private data class ZoneBreakdown(val weights: List<Double>, val timed: Boolean)

private fun zoneBreakdown(track: List<TrackPoint>, zones: List<ZoneRange>): ZoneBreakdown? {
    zoneSeconds(track, zones.map { it.min })?.let { return ZoneBreakdown(it, timed = true) }

    val counts = DoubleArray(zones.size)
    track.forEach { point ->
        val bpm = point.heartRate ?: return@forEach
        val index = zones.indexOfLast { bpm >= it.min }
        if (index >= 0) counts[index]++
    }
    return if (counts.sum() > 0) ZoneBreakdown(counts.toList(), timed = false) else null
}

/**
 * Where the heart actually spent the session, at finer resolution than zones.
 *
 * Zones are five buckets chosen from a threshold, and two very different
 * sessions can land identically in them — an hour pinned at 155 and an hour
 * oscillating between 140 and 170 are both "zone 3". The histogram separates
 * them: a steady effort is a spike, an interval session is two humps, and a hard
 * climb finish is a long right tail.
 *
 * Bars are tinted by the zone their heart rate falls in, so the two cards read
 * as one picture rather than as two unrelated breakdowns of the same numbers.
 */
@Composable
fun HeartRateDistributionCard(
    track: List<TrackPoint>,
    zones: List<ZoneRange>,
    modifier: Modifier = Modifier,
) {
    val bins = remember(track) { heartRateBins(track, binWidth = HR_BIN_BPM) }
    // Two bars is not a distribution; it is a number with a decoration.
    if (bins.size < 3) return
    val peak = bins.maxOf { it.seconds }.takeIf { it > 0 } ?: return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Heart-rate distribution")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    bins.forEach { bin ->
                        Box(
                            Modifier
                                .weight(1f)
                                // Every bin keeps a sliver of height, so an
                                // empty one reads as a gap in the distribution
                                // rather than as the axis running out.
                                .fillMaxHeight((bin.seconds / peak).toFloat().coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(binColor(bin.bpm, zones)),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${bins.first().bpm} bpm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${bins.last().bpm + HR_BIN_BPM} bpm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Five bpm: fine enough to separate two efforts, coarse enough to fit a phone. */
private const val HR_BIN_BPM = 5

private fun binColor(bpm: Int, zones: List<ZoneRange>): Color {
    val index = zones.indexOfLast { bpm >= it.min }
    return if (index >= 0) zoneColor(zones[index], index) else ZONE_FALLBACK.first()
}

/** Zone colours come from the spec where present; the fallback is a ramp. */
private fun zoneColor(zone: ZoneRange, index: Int): Color =
    zone.color?.let(::specColor) ?: ZONE_FALLBACK[index % ZONE_FALLBACK.size]

private val ZONE_FALLBACK = listOf(
    Color(0xFF60A5FA), Color(0xFF4ADE80), Color(0xFFFACC15),
    Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFA855F7),
    Color(0xFFEC4899),
)

// ── Climbs ───────────────────────────────────────────────────────────────────

/**
 * Climbs, as the watch segmented them.
 *
 * Only the ascents. A climbing day records descents and rests as splits too,
 * and listing all three triples the rows while burying the ones anyone opens
 * this card to read.
 */
@Composable
fun ClimbsCard(climbs: List<ClimbSplit>, modifier: Modifier = Modifier) {
    // `isActive`, not a substring match on "climb": the rest splits are named
    // `climb_rest`, so the obvious `contains("climb")` kept every one of them
    // and doubled the list with rows that are the athlete standing still.
    val ascents = climbs.filter { it.isActive }
    if (ascents.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Climbs (${ascents.size})")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                ascents.take(MAX_ROWS).forEachIndexed { index, climb ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${climb.splitNumber ?: index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(
                            climb.gradeDisplay ?: "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // Sent or not is the first thing anyone reads off a
                        // climb log, and it is only meaningful when the watch
                        // recorded a result at all.
                        Text(
                            when (climb.climbResult) {
                                null -> "—"
                                CLIMB_RESULT_SEND -> "Sent"
                                else -> "Attempt"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (climb.isSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            elapsed(climb.durationSeconds?.toInt()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ── Strength sets ────────────────────────────────────────────────────────────

/**
 * Sets, grouped by exercise.
 *
 * A session is 40+ rows flat and perhaps eight exercises grouped, and the
 * grouped form is how anyone thinks about a workout — "three sets of bench",
 * not "set 7, set 8, set 9". Rest sets are dropped: the watch records them, and
 * nobody reads them.
 */
@Composable
fun StrengthSetsCard(sets: List<StrengthSet>, modifier: Modifier = Modifier) {
    val active = sets.filter { !it.setType.equals("rest", ignoreCase = true) }
    if (active.isEmpty()) return

    val grouped = remember(active) {
        active.groupBy {
            it.exerciseName
                ?: it.exerciseCategory?.replace('_', ' ')?.replaceFirstChar { c -> c.uppercase() }
                ?: "Exercise"
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Sets (${active.size})")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                grouped.entries.take(MAX_ROWS).forEach { (exercise, exerciseSets) ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            exercise,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            exerciseSets.joinToString("   ") { set ->
                                buildString {
                                    append(set.repetitions ?: 0)
                                    set.weightKg?.takeIf { it > 0 }?.let {
                                        append(" × ${trimTrailingZero(it)} kg")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_ROWS = 40

private fun trimTrailingZero(value: Double): String {
    val rounded = kotlin.math.round(value * 10) / 10
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}" else "$rounded"
}

// ── What the recording says that the summary does not ────────────────────────

/**
 * Moving time and the shape of the terrain.
 *
 * Two facts a summary row cannot carry. Elapsed time is the only duration
 * stored, and on anything with a café stop or a long descent queue the gap
 * between elapsed and moving is the difference between a hard day and a social
 * one. Total ascent is stored; the high and low points of the day are not, and
 * on a hike they are most of what someone remembers.
 *
 * Every row is conditional, and the card disappears when none of them survives
 * — an indoor session has no altitude, and a track with no speed channel cannot
 * say anything about moving time. Absent beats a row of em dashes.
 */
@Composable
fun RecordingCard(
    track: List<TrackPoint>,
    detail: ActivityDetail,
    modifier: Modifier = Modifier,
) {
    val elapsedSeconds = detail.durationSeconds
    val profile = remember(track) { elevationProfile(track) }
    val moving = remember(track) { movingSeconds(track) }

    val stats = buildList {
        // Only worth a row when it actually differs from elapsed. A minute of
        // rounding either way is not a stop, and a "moving time" identical to
        // the time above it is a row that costs space and says nothing.
        if (moving != null && elapsedSeconds != null &&
            differsMeaningfully(moving, elapsedSeconds.toDouble(), MOVING_TOLERANCE_SECONDS)
        ) {
            add(Stat(elapsed(moving), "Moving"))
            add(Stat(elapsed(elapsedSeconds - moving), "Stopped"))
        }
        profile?.let {
            // Gain and loss only when the summary carries neither. The watch
            // computes those from the barometer at full rate; this track has
            // been LTTB-downsampled, so re-deriving them undershoots — 205 m
            // against the recorded 295 m on one hike here. Both numbers are
            // defensible and showing them on the same screen is not: it reads
            // as one of them being broken. The summary's wins, and this fills
            // in only for an activity that has none.
            if (detail.totalAscent == null && detail.totalDescent == null) {
                if (it.gain >= 1.0) add(Stat(elevation(it.gain), "Gain"))
                if (it.loss >= 1.0) add(Stat(elevation(it.loss), "Loss"))
            }
            // The high and low points are never in the summary at all, which is
            // the reason this card exists.
            add(Stat(elevation(it.highest), "High point"))
            add(Stat(elevation(it.lowest), "Low point"))
        }
    }
    if (stats.isEmpty()) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("From the recording")
        StatGrid(stats)
    }
}

/** Under a minute apart, elapsed and moving are the same day. */
private const val MOVING_TOLERANCE_SECONDS = 60.0
