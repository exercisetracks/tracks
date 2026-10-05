// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Tappable, as on the web. An activity arrives named by the
                    // watch — "Running", "Cycling", the same word every time —
                    // and renaming it is how a log becomes navigable. Putting
                    // that behind a menu makes it a thing people mean to do
                    // later; on the title itself it is where the wrong name is.
                    Text(
                        state.data?.let { it.detail.name ?: sportLabel(
                            it.detail.sport, it.detail.subSport, it.sportType,
                        ) } ?: "Activity",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Tokens.Radius.lg))
                            .clickable(enabled = state.data != null) { editing = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.data != null) {
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit activity")
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

                state.data != null -> DetailBody(state.data!!, state.mapStyleJson)
            }
        }
    }

    val detail = state.data?.detail
    if (editing && detail != null) {
        ActivityEditSheet(
            detail = detail,
            onDismiss = { editing = false },
            onSave = { name, notes, choice ->
                vm.save(name, notes, choice)
                editing = false
            },
            onHide = { editing = false; vm.hide(onBack) },
            onDelete = { editing = false; vm.delete(onBack) },
        )
    }

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
        ActivityHeaderCard(data)

        data.detail.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Notes")
                Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
        }

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
        // Splits sit directly under the sport layout, above the charts: they
        // are the part of a session people actually compare, and burying them
        // under five stream graphs is how a watch screen loses to a spreadsheet.
        SplitsCard(
            track = data.track,
            // Falls back to what the track measures, so an activity whose
            // summary lost its distance still gets splits.
            recordedDistance = distanceFor(data.detail, data.track),
            showPace = paceReadingSport(data.sportType),
        )
        RecordingCard(data.track, data.detail)
        HeartRateZonesCard(data.track, data.hrZones)
        HeartRateDistributionCard(data.track, data.hrZones)
        StreamsCard(data.track)
        ClimbsCard(data.climbs)
        StrengthSetsCard(data.sets)
        MuscleActivationCard(data.sets)
    }
}

/**
 * Whether this sport's splits read as a pace or as a speed.
 *
 * The same split shown the wrong way round is unreadable to its audience — a
 * runner does not think in km/h and a cyclist does not think in min/km. Mirrors
 * the `showPace` flag each layout already passes to [LapsCard].
 */
private fun paceReadingSport(sportType: String): Boolean =
    sportType in setOf("running", "hiking", "nordic_skiing", "triathlon")
