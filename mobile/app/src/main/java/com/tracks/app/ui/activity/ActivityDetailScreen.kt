// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tracks.app.ui.tour.TourAnchor
import com.tracks.app.ui.tour.tourAnchor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.body.MuscleActivationCard
import com.tracks.app.ui.components.EmptyState
import com.tracks.app.ui.map.ActivityTrackMap
import com.tracks.app.ui.theme.Tokens

/**
 * One activity, rendered by whichever layout claims its sport.
 *
 * This file deliberately knows nothing about sports. It fetches, handles the
 * three states every screen has, draws the map when there is GPS, and hands
 * the rest to [layoutFor] — so adding a sport never touches it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    vm: ActivityDetailViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editingNotes by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val detail = state.data?.detail

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Tappable, as on the web, and typed into where it stands —
                    // no dialog. An activity arrives named by the watch —
                    // "Running", "Cycling", the same word every time — and
                    // renaming it is how a log becomes navigable. Behind a
                    // menu it is a thing people mean to do later; on the title
                    // itself it is where the wrong name is.
                    val shown = state.data?.let { it.detail.name ?: sportLabel(
                        it.detail.sport, it.detail.subSport, it.sportType,
                    ) } ?: "Activity"
                    if (renaming && detail != null) {
                        InlineName(
                            initial = shown,
                            onCommit = { name -> renaming = false; vm.rename(name) },
                            onCancel = { renaming = false },
                        )
                    } else {
                        Text(
                            shown,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Tokens.Radius.lg))
                                .clickable(enabled = state.data != null) { renaming = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (detail != null && !renaming) {
                        // The note's first words beside the pencil: enough to
                        // say there is one and which, without a section of
                        // its own pushing the map down. Tapping it opens the
                        // note, as the pencil does.
                        noteSnippet(detail.notes)?.let { snippet ->
                            Text(
                                snippet,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = 120.dp)
                                    .clip(RoundedCornerShape(Tokens.Radius.lg))
                                    .clickable { editingNotes = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                        IconButton(onClick = { editingNotes = true }, modifier = Modifier.tourAnchor("activity-edit")) {
                            Icon(Icons.Filled.Edit, contentDescription = "Notes")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> EmptyState(
                    title = "Could not load this activity",
                    body = state.error ?: "",
                    modifier = Modifier.fillMaxSize(),
                )

                state.data != null -> {
                    // The app's theme, not the system's: the user can pick one
                    // in Settings, and the map should match the screen it is on.
                    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    DetailBody(state.data!!, if (dark) state.backdropDark else state.backdropLight)
                }
            }
        }
    }

    if (editingNotes && detail != null) {
        ActivityNotesSheet(
            detail = detail,
            onDismiss = { editingNotes = false },
            onSave = { notes ->
                vm.saveNotes(notes)
                editingNotes = false
            },
            onHide = { editingNotes = false; vm.hide(onBack) },
            onDelete = { editingNotes = false; vm.delete(onBack) },
        )
    }

}

/**
 * The title, as a field. Opens with the whole name selected, so typing
 * replaces it and a tap places the cursor; Done or tapping away saves, and
 * back cancels — the web's Enter, blur and Escape.
 */
@Composable
private fun InlineName(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    // Focus is gained once before it can be lost; without this the first
    // focus event (unfocused, on composition) would commit at once.
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun commit() {
        if (done) return
        done = true
        // Unchanged is a cancel, not a rename: committing a watch-named
        // activity's shown label would write the sport's name as its title,
        // and stamp a field this phone never meant to change.
        if (value.text.trim() == initial) onCancel() else onCommit(value.text)
    }
    BackHandler { done = true; onCancel() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true
                else if (hadFocus) commit()
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        decorationBox = { inner ->
            Column {
                inner()
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun DetailBody(data: ActivityDetailData, mapStyleJson: String?) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Before the map, because it says *which* activity this is: the app bar
        // shows a name that for most imports is the watch's own "Hike".
        TourAnchor("activity-body") { ActivityHeaderCard(data) }

        // The map leads when there is one — it is the fastest way to recognise
        // which ride this was, ahead of any number on the screen.
        if (data.hasGps) {
            ActivityTrackMap(
                track = data.track,
                styleJson = mapStyleJson,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (data.trackError != null) {
            Text(
                data.trackError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        layoutFor(data.sportType).Render(data)

        // Everything below is absent unless the activity actually carries it,
        // so a treadmill run and a bouldering session show different screens
        // without either needing to know the other exists.
        RecordingCard(data.track, data.detail)
        HeartRateZonesCard(data.track, data.hrZones)
        HeartRateDistributionCard(data.track, data.hrZones)
        StreamsCard(data.track, hrZones = data.hrZones)
        ClimbsCard(data.climbs)
        StrengthSetsCard(data.sets)
        MuscleActivationCard(data.sets)

        // The tables last, under the charts. They used to sit directly under
        // the summary, and a long run's forty splits and auto-laps pushed every
        // chart a dozen screens down — the graphs are read on every visit, the
        // rows only when comparing. Drawn here rather than by each layout so
        // the order is one decision instead of fourteen copies of it.
        SplitsCard(
            track = data.track,
            // Falls back to what the track measures, so an activity whose
            // summary lost its distance still gets splits.
            recordedDistance = distanceFor(data.detail, data.track),
            showPace = paceReadingSport(data.sportType),
        )
        if (data.sportType !in LAPLESS_SPORTS) {
            LapsCard(data.laps, showPace = paceReadingSport(data.sportType))
        }
    }
}

/**
 * Sports whose laps are not a lap table. A strength session's laps are its
 * sets, golf's are holes (drawn as the scorecard), climbing's are routes, and
 * a yoga session's are nothing anyone reads — each layout shows those its own
 * way, or not at all.
 */
private val LAPLESS_SPORTS = setOf("strength", "golf", "climbing", "bouldering", "mind_body")

/**
 * Whether this sport's splits read as a pace or as a speed.
 *
 * The same split shown the wrong way round is unreadable to its audience — a
 * runner does not think in km/h and a cyclist does not think in min/km. Used for
 * both the splits and the laps, so the two tables read in the same unit.
 */
private fun paceReadingSport(sportType: String): Boolean =
    sportType in setOf("running", "hiking", "nordic_skiing", "triathlon")
