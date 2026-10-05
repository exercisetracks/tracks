// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.core.api.ClimbSplit
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import kotlin.math.roundToInt

/**
 * Climbing, on the wall and on the mat.
 *
 * Route climbing and bouldering share every derived number and differ only in
 * what the numbers are called — a rope session counts routes and vertical
 * metres, a bouldering session counts problems and has no meaningful ascent.
 * So the arithmetic lives once in [ClimbingStats] and the two layouts are
 * mostly a choice of vocabulary.
 *
 * These read the *splits*, not the activity summary, because a climbing
 * activity's summary is nearly empty: the watch records the session as a long
 * span of alternating `climb_active` and `climb_rest` splits, and every fact
 * anyone wants — how many routes, how many sent, how hard, how long actually
 * climbing — exists only as an aggregate over those.
 */

/**
 * Which ladder a session's grades are on.
 *
 * The watch already makes this distinction and the phone was ignoring it: every
 * grade was rendered "V8" regardless, so an indoor rope session — where the
 * wall, the app and the climber all say 5.11a — came back reading like a
 * bouldering circuit. The number the watch records is the same either way; it
 * is an index into whichever ladder the activity is on, and only the sport
 * knows which one that is.
 */
enum class GradeScale { V, YDS }

/**
 * A recorded grade, on the right ladder.
 *
 * V is a plain count, so the index *is* the grade. YDS is not: it runs 5.0
 * through 5.9 a whole number at a time and then splits every number into a, b,
 * c and d, which is why 5.10b is one step above 5.10a rather than a tenth of
 * one. Index 10 is where the letters start, and each step past it advances a
 * letter and rolls over into the next number every fourth.
 *
 * Deliberately not the web app's table, which stops at 5.14 and has no letters
 * — it maps index 10 to a bare "5.10", so four different grades render
 * identically. Worth diverging for: the letter is most of what a gym grade
 * says.
 */
internal fun climbGradeLabel(level: Int, scale: GradeScale): String = when {
    scale == GradeScale.V -> "V$level"
    level < YDS_LETTERS_FROM -> "5.$level"
    else -> {
        val steps = level - YDS_LETTERS_FROM
        "5.${YDS_LETTERS_FROM + steps / YDS_LETTERS.length}${YDS_LETTERS[steps % YDS_LETTERS.length]}"
    }
}

/** Below this a YDS grade is a whole number; at and above it, it takes a letter. */
private const val YDS_LETTERS_FROM = 10
private const val YDS_LETTERS = "abcd"

/** Everything both climbing layouts show, derived once from the splits. */
private data class ClimbingStats(
    val active: List<ClimbSplit>,
    val rest: List<ClimbSplit>,
    val sends: Int,
    val attempts: Int,
    val sendRate: Int?,
    val hardestSend: String?,
    val hardestTried: String?,
    val totalAscent: Double,
    val activeSeconds: Int,
    val avgRestSeconds: Int?,
    val onWallPercent: Int?,
    val peakHr: Int?,
    val avgClimbHr: Int?,
    val calories: Int?,
) {
    val hasGrades: Boolean get() = hardestTried != null
    val hasResults: Boolean get() = active.any { it.climbResult != null }
}

@Composable
private fun climbingStats(
    data: ActivityDetailData,
    scale: GradeScale,
): ClimbingStats = remember(data.climbs, data.detail, scale) {
    val active = data.climbs.filter { it.isActive }
    val rest = data.climbs.filter { it.isRest }
    val sends = active.count { it.isSend }

    val activeSeconds = active.sumOf { (it.durationSeconds ?: 0.0) }.roundToInt()
    val restSeconds = rest.sumOf { (it.durationSeconds ?: 0.0) }
    val total = data.detail.durationSeconds ?: 0

    // The hardest send and the hardest attempt are different questions, and the
    // gap between them is the interesting one — it is the grade being worked.
    val sentGrades = active.filter { it.isSend }.mapNotNull { it.gradeLevel }
    val triedGrades = active.mapNotNull { it.gradeLevel }
    // A grade the climber typed in wins over one the watch inferred — it is
    // the only one of the two that knows what the wall said.
    val gradeLabel = { level: Int ->
        active.firstOrNull { it.gradeLevel == level && it.userGrade != null }?.userGrade
            ?: climbGradeLabel(level, scale)
    }

    val climbHrs = active.mapNotNull { it.maxHeartRate }.filter { it > 0 }

    ClimbingStats(
        active = active,
        rest = rest,
        sends = sends,
        attempts = active.size - sends,
        sendRate = if (active.isNotEmpty() && active.any { it.climbResult != null }) {
            (sends * 100.0 / active.size).roundToInt()
        } else {
            null
        },
        hardestSend = sentGrades.maxOrNull()?.let(gradeLabel),
        hardestTried = triedGrades.maxOrNull()?.let(gradeLabel),
        totalAscent = active.sumOf { it.totalAscent ?: 0.0 },
        activeSeconds = activeSeconds,
        avgRestSeconds = if (rest.isNotEmpty()) (restSeconds / rest.size).roundToInt() else null,
        onWallPercent = if (total > 0) (activeSeconds * 100.0 / total).roundToInt() else null,
        peakHr = active.mapNotNull { it.maxHeartRate }.maxOrNull()?.takeIf { it > 0 },
        avgClimbHr = climbHrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
        calories = active.sumOf { it.totalCalories ?: 0 }.takeIf { it > 0 },
    )
}

@Composable
fun ClimbingLayout(data: ActivityDetailData) {
    // Rope climbing, indoors or out: YDS. See [GradeScale].
    val stats = climbingStats(data, GradeScale.YDS)
    // Nothing to aggregate — fall back rather than render a card of dashes.
    if (stats.active.isEmpty()) {
        FallbackLayout(data)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        StatGrid(
            listOfNotNull(
                Stat("${stats.active.size}", "Routes"),
                sendStat(stats),
                stats.hardestSend?.let { Stat(it, "Hardest send") },
                // Only when it differs — otherwise it is the same number twice.
                stats.hardestTried
                    ?.takeIf { it != stats.hardestSend }
                    ?.let { Stat(it, "Hardest tried") },
                stats.totalAscent.takeIf { it > 0 }?.let { Stat(elevation(it), "Vertical") },
                Stat(elapsed(stats.activeSeconds), "On the wall"),
                stats.onWallPercent?.let { Stat("$it%", "Active") },
                stats.avgRestSeconds?.let { Stat(elapsed(it), "Avg rest") },
                stats.avgClimbHr?.let { Stat("$it", "Avg climb HR") },
                stats.peakHr?.let { Stat("$it", "Peak HR") },
                stats.calories?.let { Stat("$it", "Calories") },
            )
        )
        ClimbGradeCard(stats.active, GradeScale.YDS)
        TrainingEffectCard(data.detail)
    }
}

@Composable
fun BoulderingLayout(data: ActivityDetailData) {
    val stats = climbingStats(data, GradeScale.V)
    if (stats.active.isEmpty()) {
        FallbackLayout(data)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        // Problems, not routes; no vertical, because a boulder's ascent is
        // noise at this scale and the watch rarely records it anyway.
        StatGrid(
            listOfNotNull(
                Stat("${stats.active.size}", "Problems"),
                sendStat(stats),
                stats.hardestSend?.let { Stat(it, "Hardest send") },
                stats.hardestTried
                    ?.takeIf { it != stats.hardestSend }
                    ?.let { Stat(it, "Hardest tried") },
                Stat(elapsed(stats.activeSeconds), "Climbing time"),
                stats.onWallPercent?.let { Stat("$it%", "Active") },
                stats.avgRestSeconds?.let { Stat(elapsed(it), "Avg rest") },
                stats.avgClimbHr?.let { Stat("$it", "Avg climb HR") },
                stats.peakHr?.let { Stat("$it", "Peak HR") },
                stats.calories?.let { Stat("$it", "Calories") },
            )
        )
        ClimbGradeCard(stats.active, GradeScale.V)
        TrainingEffectCard(data.detail)
    }
}

/** Sends, with the rate underneath — absent when the watch recorded no result. */
private fun sendStat(stats: ClimbingStats): Stat? {
    if (!stats.hasResults) return null
    val rate = stats.sendRate?.let { " · $it%" } ?: ""
    return Stat("${stats.sends}$rate", "Sends")
}

/** One grade's tally, as [ClimbGradeCard] draws it. */
private data class GradeRow(val label: String, val sends: Int, val attempts: Int)

/**
 * Sends and attempts per grade.
 *
 * The web app draws this as a bar chart; here it is a stacked row per grade,
 * which survives a phone width that a grouped bar chart with axis labels does
 * not. Sorted hardest-first: the top of the list is what the session was about.
 *
 * Absent entirely when nothing is graded — a session logged without grades is
 * common, and an empty axis is worse than no card.
 */
@Composable
private fun ClimbGradeCard(
    active: List<ClimbSplit>,
    scale: GradeScale,
    modifier: Modifier = Modifier,
) {
    // Sorted by the numeric level, not the label: "V9" sorts above "V10" as
    // text — and "5.9" above "5.10a" — which would put the hardest climb of the
    // session in the middle of the list.
    val rows = remember(active, scale) {
        active.filter { it.gradeLevel != null }
            .groupBy { it.gradeLevel!! }
            .toSortedMap(compareByDescending { it })
            .map { (level, climbs) ->
                GradeRow(
                    label = climbs.firstOrNull { it.userGrade != null }?.userGrade
                        ?: climbGradeLabel(level, scale),
                    sends = climbs.count { it.isSend },
                    attempts = climbs.count { !it.isSend },
                )
            }
    }
    if (rows.isEmpty()) return

    val maxCount = rows.maxOf { it.sends + it.attempts }.coerceAtLeast(1)
    val sendColor = MaterialTheme.colorScheme.primary
    val attemptColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Grades")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rows.forEach { (label, sends, attempts) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(44.dp),
                        )
                        // The bar is scaled against the busiest grade so the
                        // shape of the session reads at a glance.
                        Row(
                            Modifier
                                .weight(1f)
                                .height(12.dp),
                        ) {
                            if (sends > 0) {
                                Box(
                                    Modifier
                                        .weight(sends.toFloat())
                                        .height(12.dp)
                                        .background(sendColor),
                                )
                            }
                            if (attempts > 0) {
                                Box(
                                    Modifier
                                        .weight(attempts.toFloat())
                                        .height(12.dp)
                                        .background(attemptColor),
                                )
                            }
                            // Padding so rows are comparable rather than each
                            // stretching to full width regardless of count.
                            val empty = maxCount - sends - attempts
                            if (empty > 0) Box(Modifier.weight(empty.toFloat()))
                        }
                        Text(
                            if (attempts > 0) "$sends/${sends + attempts}" else "$sends",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(44.dp),
                        )
                    }
                }
            }
        }
    }
}
