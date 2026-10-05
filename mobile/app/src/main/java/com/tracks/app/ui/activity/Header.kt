// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

/**
 * When this was, and what it was.
 *
 * The detail screen used to answer neither. The app bar carries the activity's
 * name — which for most imports is the watch's own "Hike" or "MTB" — and every
 * card below it is a number about the effort, so the screen could not say
 * whether it was showing this morning's ride or one from March. On a list you
 * scroll by date that is the first thing you want confirmed after tapping.
 *
 * The sport label is here rather than in the app bar because it is a fact about
 * the activity, not a title: `sub_sport` is what separates a treadmill run from
 * a trail run and a snowboard day from a ski day, and the app bar has one line
 * for a name the user may have written themselves.
 */
@Composable
fun ActivityHeaderCard(data: ActivityDetailData, modifier: Modifier = Modifier) {
    val detail = data.detail
    val label = remember(detail.sport, detail.subSport, data.sportType) {
        sportLabel(detail.sport, detail.subSport, data.sportType)
    }
    val started = remember(detail.startedAt) { startedAtLabel(detail.startedAt) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(Tokens.Radius.md),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (started != null) {
                Text(
                    started,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Merged trips are stitched from several recordings, so their totals are
        // sums rather than anything one device measured. Worth saying, because
        // it explains a duration that no single file could account for.
        if (detail.isMerged) {
            Text(
                "Merged from more than one recording",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        detail.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Text(
                notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The start, in the phone's own zone.
 *
 * `started_at` is a real UTC instant — the column is `timestamptz` and the
 * import writes it as one — so it has to be converted rather than printed. A
 * ride at 22:04 UTC is a late-afternoon ride in Edmonton, and showing the stored
 * hour would make every evening activity look like a night one.
 */
private fun startedAtLabel(startedAt: String?): String? {
    val local = com.tracks.app.ui.components.startedLocal(startedAt ?: return null) ?: return null
    return STARTED_FORMAT.format(local)
}

private val STARTED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

/**
 * A name for the sport, refined by `sub_sport` where it changes the answer.
 *
 * The sport *type* from the taxonomy is the right key for choosing a layout and
 * the wrong thing to show a person: it collapses snowboarding into `skiing` and
 * a treadmill into `running`, because those genuinely want the same charts. The
 * distinctions dropped there are exactly the ones a title should keep.
 *
 * A port of the web app's `getSportLabel` (`ActivityHeader.jsx`) rather than a
 * shared table, and deliberately so — it is presentation, it has no fixtures
 * behind it, and the two ends disagreeing about whether to write "Trail Running"
 * costs nothing. The taxonomy underneath, which decides what is *shown*, is the
 * generated one both clients share.
 */
internal fun sportLabel(sport: String?, subSport: String?, sportType: String): String {
    val both = "${sport.orEmpty()} ${subSport.orEmpty()}".lowercase()
    val sub = subSport.orEmpty().lowercase()

    fun has(vararg needles: String) = needles.any { it in both }

    return when (sportType) {
        "running" -> when {
            has("trail") -> "Trail Running"
            "treadmill" in sub -> "Treadmill"
            "track" in sub -> "Track Running"
            has("virtual") -> "Virtual Running"
            else -> "Running"
        }
        "hiking" -> if (has("walk")) "Walking" else "Hiking"
        "cycling" -> when {
            has("gravel") -> "Gravel Cycling"
            "road" in sub -> "Road Cycling"
            has("ebike", "e_bike") -> "E-Biking"
            else -> "Cycling"
        }
        "mtb" -> "Mountain Biking"
        "indoor_cycling" -> "Indoor Cycling"
        "swimming" -> if (has("open_water", "open water")) "Open Water Swim" else "Swimming"
        "rowing" -> if (has("indoor")) "Indoor Rowing" else "Rowing"
        "skiing" -> if (has("snowboard")) "Snowboarding" else "Alpine Skiing"
        "nordic_skiing" -> if (has("snowshoe")) "Snowshoeing" else "Nordic Skiing"
        "paddling" -> when {
            has("kayak") -> "Kayaking"
            has("canoe") -> "Canoeing"
            has("surf") -> "Surfing"
            has("sail") -> "Sailing"
            has("stand_up", "sup") -> "Stand Up Paddling"
            else -> "Paddling"
        }
        "fitness_equipment" -> when {
            has("elliptical") -> "Elliptical"
            has("stair") -> "Stair Climbing"
            has("treadmill") -> "Treadmill"
            else -> "Fitness Equipment"
        }
        "mind_body" -> when {
            has("yoga") -> "Yoga"
            has("pilates") -> "Pilates"
            has("breath") -> "Breathwork"
            has("meditat") -> "Meditation"
            else -> "Wellness"
        }
        "team_sports" -> TEAM_SPORTS.firstOrNull { it.lowercase() in both } ?: "Team Sport"
        "strength" -> "Strength Training"
        "bouldering" -> "Bouldering"
        "climbing" -> "Climbing"
        "golf" -> "Golf"
        "triathlon" -> "Triathlon"
        // An unclaimed sport still has a name; the raw one beats "Activity".
        else -> sport?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Activity"
    }
}

private val TEAM_SPORTS = listOf(
    "Basketball", "Soccer", "Football", "Tennis", "Volleyball", "Baseball",
    "Softball", "Hockey", "Lacrosse", "Rugby", "Handball", "Squash",
    "Racquetball", "Pickleball", "Badminton", "Boxing", "Wrestling",
)
