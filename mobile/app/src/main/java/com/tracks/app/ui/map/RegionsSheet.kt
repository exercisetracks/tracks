// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tracks.app.map.OfflineEstimate
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLngBounds

/**
 * The offline downloads sheet: what is saved, and a way to save more.
 *
 * The area is not chosen here. Picking one needs the map visible, and a modal
 * sheet covering half the screen is the one place you cannot see it from — so
 * "Choose an area" closes this and hands over to [RegionSelector], which draws
 * a resizable box over the live map the way the browser does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionsSheet(
    vm: RegionsViewModel,
    state: RegionsUiState,
    onChooseArea: () -> Unit,
    onShowOnMap: (RegionRow) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                // The list of saved areas has its own bounded scroll below —
                // see [SavedAreas] — so this is what actually gets the notice
                // and error text back on screen once there are enough rows to
                // push them past the fold. Found the hard way: a "too big for
                // the phone" notice that renders correctly and is completely
                // unreachable is indistinguishable from a button that does
                // nothing.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Offline maps", style = MaterialTheme.typography.titleMedium)

            // What the two border colours on the map mean, said once, here.
            // Without it the orange boxes are a mystery the map has no room to
            // explain — and the distinction they draw is the whole point.
            Text(
                "Blue outlines are saved on this phone and work with no signal. " +
                    "Orange outlines are on the server: full detail while you " +
                    "have a connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PrimaryButton("Choose an area to download", onClick = onChooseArea, modifier = Modifier.fillMaxWidth())

            HorizontalDivider()
            SavedAreas(vm, state, onShowOnMap)

            state.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The bar under the selection box: how big it is, what to call it, and go.
 *
 * A plain surface pinned to the bottom rather than a sheet, so the map and the
 * box it is describing both stay visible and draggable while this is up. That
 * is the whole reason selection is not part of [RegionsSheet].
 */
@Composable
fun SelectionBar(
    vm: RegionsViewModel,
    state: RegionsUiState,
    bounds: LatLngBounds?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    LaunchedEffect(state.suggestedName) {
        if (name.isBlank()) name = state.suggestedName.orEmpty()
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(Tokens.Radius.xl2),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when {
                    state.tooLarge ->
                        "That is more ground than one download can cover — several " +
                            "states is the limit. Drag the corners in."
                    state.estimating -> "Working out how big that is…"
                    state.estimateBytes != null -> "About ${formatBytes(state.estimateBytes)} to download"
                    else -> "Drag the corners to choose an area"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.tooLarge) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )

            // The second line is the phone's own answer, and it is the one that
            // decides. The server's byte count says what the *server* must cut;
            // the phone fetches every tile individually, and the gap between
            // those two numbers is what used to turn a reasonable-looking box
            // into a download that ran for an afternoon and got deleted.
            phoneCostLine(state)?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.phoneCost?.level == OfflineEstimate.Level.BeyondPhone) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ButtonRow {
                NeutralButton("Cancel", onClick = onDone)
                PrimaryButton(
                    // Named for what will actually happen. Past the phone's
                    // ceiling the area is still worth building — the server
                    // will serve it at full detail — but nothing is coming down
                    // to this phone, and a button that says "Download" there is
                    // a promise the app cannot keep.
                    if (state.phoneCost?.level == OfflineEstimate.Level.BeyondPhone) "Build on server" else "Download",
                    onClick = {
                        bounds?.let {
                            vm.download(it, name.ifBlank { state.suggestedName ?: "Saved area" })
                        }
                        name = ""
                        onDone()
                    },
                    // Only the box being sane gates this, not the estimate. The
                    // size and the name are advisory — the server geocodes its
                    // own name when none is given — and waiting on two network
                    // calls before the button lights up is what made starting a
                    // download feel broken.
                    enabled = bounds != null && !state.tooLarge,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SavedAreas(
    vm: RegionsViewModel,
    state: RegionsUiState,
    onShowOnMap: (RegionRow) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Saved areas", style = MaterialTheme.typography.labelLarge)
        if (state.storedBytes > 0) {
            Text(
                "${formatBytes(state.storedBytes + state.routingBytes)} on this phone",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Its own line rather than a row phase: routing segments are 5° cells
    // shared by every area that touches them, so this belongs to the sheet.
    // Shown only while it runs and once it has landed — a user who never plans
    // a route does not need to know the mechanism exists.
    state.routingProgress?.let { fraction ->
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                "Downloading trail routing data…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    } ?: if (state.routingBytes > 0) {
        Text(
            "Routing on trails works offline here (${formatBytes(state.routingBytes)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else Unit

    when {
        state.loading -> CircularProgressIndicator(Modifier.padding(8.dp))
        state.rows.isEmpty() -> Text(
            "Nothing saved yet. Choose an area above to use the map with no signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> LazyColumn(
            // Bounded rather than left to wrap its content: nested inside the
            // sheet's own scroll now (see [RegionsSheet]), an unbounded
            // LazyColumn there is a crash, not just a layout quirk — a
            // vertically scrolling child needs a height its parent's scroll
            // is not also providing.
            modifier = Modifier.heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.rows, key = { it.serverId ?: it.storedId ?: it.name }) { row ->
                RegionRowItem(
                    row = row,
                    onDelete = { vm.delete(row) },
                    onSaveToPhone = { vm.storeExisting(row) },
                    onRemoveFromPhone = { vm.removeFromPhone(row) },
                    onShowOnMap = { onShowOnMap(row) },
                )
            }
        }
    }
}

@Composable
private fun RegionRowItem(
    row: RegionRow,
    onDelete: () -> Unit,
    onSaveToPhone: () -> Unit,
    onRemoveFromPhone: () -> Unit,
    onShowOnMap: () -> Unit,
) {
    // The whole row, not a button on it. Names like "Oregon" and "Hood River"
    // tell you very little about which rectangle is which — and nothing about
    // whether they overlap — so tapping to go and look is the answer to the
    // question the list raises.
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.bounds != null, onClick = onShowOnMap)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The same dot the map draws its border in, so a row and an outline
            // can be matched up without reading either.
            CoverageDot(row.coverage)
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            when {
                // Built on the server, absent here. The offer, not an alarm:
                // this area already works over the network.
                row.serverId != null && row.storedId == null &&
                    (row.phase is DownloadPhase.ServerOnly) ->
                    TonalButton("Save to phone", onClick = onSaveToPhone, small = true)

                // Started here and stopped. Resuming picks up exactly where it
                // left off — everything already fetched is found in the store
                // and counted without a request.
                row.phase is DownloadPhase.Paused ->
                    TonalButton("Resume", onClick = onSaveToPhone, small = true)

                // Stored here and on the server. Freeing the phone's copy is a
                // different decision from giving the area up altogether, now
                // that the server serves it at full detail either way.
                row.phase is DownloadPhase.Ready && row.serverId != null ->
                    TonalButton("Free up space", onClick = onRemoveFromPhone, small = true)

                else -> Unit
            }
            // One word for both halves, because to the user they are one
            // thing: an area they no longer want anywhere.
            DangerButton(if (row.phase is DownloadPhase.Ready) "Delete" else "Cancel", onClick = onDelete, small = true)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                describe(row.phase),
                style = MaterialTheme.typography.bodySmall,
                color = if (row.phase is DownloadPhase.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // The number next to the bar, not instead of it. A bar answers
            // "roughly how far"; on a download measured in minutes the digits
            // are what tell you it is still moving at all.
            progressOf(row.phase)?.let { fraction ->
                Text(
                    "${(fraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Only while something is moving. A completed area with a full bar
        // reads as still working.
        progressOf(row.phase)?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The border colour this area is drawn in, as a dot beside its name. */
@Composable
private fun CoverageDot(coverage: Coverage) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
                when (coverage) {
                    Coverage.Phone -> Color(0xFF2563EB)
                    Coverage.Server -> Color(0xFFF97316)
                    Coverage.Building -> Color(0xFF94A3B8)
                }
            )
    )
}

/**
 * One sentence per state, naming which half is running.
 *
 * The distinction is not pedantry: an area the server has built but the phone
 * has not stored works perfectly until the signal goes, which is the failure
 * this whole feature exists to prevent. It gets its own line rather than being
 * rounded up to ready — and, since the server now serves that ground at full
 * detail, its own line says what it *does* give you rather than only what it
 * does not.
 */
private fun describe(phase: DownloadPhase): String = when (phase) {
    is DownloadPhase.Extracting ->
        phase.detail?.takeIf { it.isNotBlank() }?.let { "Preparing on the server — $it" }
            ?: "Preparing on the server…"

    is DownloadPhase.ServerOnly ->
        "Full detail from the server · not saved for offline use"

    is DownloadPhase.Storing -> when {
        // What MapLibre is retrying, when it is retrying something. A download
        // that has gone quiet because the phone walked into a tunnel should say
        // so rather than look stuck.
        phase.waiting != null -> "${phase.waiting} · ${formatBytes(phase.bytes)} so far"
        phase.bytes > 0 -> "Saving to this phone — ${formatBytes(phase.bytes)}"
        else -> "Saving to this phone…"
    }

    is DownloadPhase.Paused ->
        "Paused at ${formatBytes(phase.bytes)} — tap Resume to finish it"

    is DownloadPhase.Ready -> "Ready offline · ${formatBytes(phase.bytes)}"
    is DownloadPhase.Failed -> phase.reason
}

private fun progressOf(phase: DownloadPhase): Float? = when (phase) {
    is DownloadPhase.Extracting -> phase.fraction.coerceIn(0f, 1f)
    is DownloadPhase.Storing -> phase.fraction.coerceIn(0f, 1f)
    is DownloadPhase.Paused -> phase.fraction.coerceIn(0f, 1f)
    else -> null
}

/**
 * What this box would cost the phone, in the terms that matter.
 *
 * Tiles rather than bytes, because the count is what the wait is made of: the
 * phone fetches them one request at a time and the size of each is the server's
 * business. Null while there is nothing to say — a box small enough to finish
 * in a few minutes needs no warning label.
 */
private fun phoneCostLine(state: RegionsUiState): String? {
    val cost = state.phoneCost ?: return null
    val thousands = cost.resources / 1000
    return when (cost.level) {
        OfflineEstimate.Level.Fine -> null
        OfflineEstimate.Level.Long ->
            "About ${thousands}k tiles for this phone — leave it on wifi and " +
                "give it a while."
        OfflineEstimate.Level.BeyondPhone ->
            "Too big to store on the phone (about ${thousands}k tiles). The " +
                "server will still build it and serve full detail whenever you " +
                "have signal."
    }
}

/**
 * Bytes, rounded the way someone deciding whether to wait for wifi reads them.
 *
 * No decimal below a gigabyte: "480 MB" is the answer to the question being
 * asked, and "479.7 MB" is the same answer with false precision on an estimate
 * that is not accurate to a megabyte anyway.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
