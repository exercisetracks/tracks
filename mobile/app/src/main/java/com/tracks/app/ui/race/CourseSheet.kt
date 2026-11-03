// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens

/**
 * Every way to get a race course, behind the Course section's one button
 * ("Add course", or "Replace course" once one is loaded): a GPX file, one
 * drawn on the map, or a saved track. The three used to be buttons of their
 * own under the course, crowding it for a choice made once per race. The
 * web's race plan does the same with a popover (CourseMenu.jsx).
 *
 * Drawing needs the map, and basemaps need a server, so it is offered only
 * when [linked]; a track already saved can be used either way.
 *
 * A triathlon's course comes whole, by leg, or both, so for one the sheet
 * first asks what the course is for — unless a leg's own row opened it
 * ([fixedLeg]). Every action is handed the leg, null for the whole course.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseSheet(
    tracks: List<Pair<String, String>>,
    linked: Boolean,
    onDismiss: () -> Unit,
    onImport: (String?) -> Unit,
    onDraw: (String?) -> Unit,
    onTrack: (String, String?) -> Unit,
    triathlon: Boolean = false,
    fixedLeg: String? = null,
) {
    var target by remember { mutableStateOf<String?>(null) }
    val leg = fixedLeg ?: target.takeIf { triathlon }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Space.s4).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1),
        ) {
            Text(fixedLeg?.let { "${LEG_NAMES.getValue(it)} course" } ?: "Course", style = MaterialTheme.typography.titleMedium)
            if (triathlon && fixedLeg == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
                    (listOf<String?>(null) + com.tracks.core.race.TriCourse.LEGS).forEach { l ->
                        FilterChip(selected = target == l, onClick = { target = l },
                            label = { Text(l?.let { LEG_NAMES.getValue(it) } ?: "Whole") })
                    }
                }
            }
            SheetRow("Upload GPX file") { onImport(leg) }
            if (linked) SheetRow("Draw on map") { onDraw(leg) }
            if (tracks.isNotEmpty()) {
                Text(
                    "SAVED COURSES", style = Tokens.SectionHeader.style, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Tokens.Space.s3),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(tracks, key = { it.first }) { (uid, name) -> SheetRow(name) { onTrack(uid, leg) } }
                }
            }
        }
    }
}

internal val LEG_NAMES = mapOf("swim" to "Swim", "bike" to "Bike", "run" to "Run")

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) = Text(
    label,
    style = MaterialTheme.typography.bodyLarge,
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Tokens.Space.s3),
)
