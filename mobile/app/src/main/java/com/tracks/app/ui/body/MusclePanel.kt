// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.SlideOver
import com.tracks.app.ui.components.SlideOverHost
import com.tracks.app.ui.components.rememberSlideOverState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.core.spec.muscleLabel
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import kotlin.math.max

/**
 * A library page with a muscle filter over it.
 *
 * ## Half the screen, figures stacked
 *
 * The filter was once a drawer with two bodies side by side in about three
 * hundred points, which made each figure a thumbnail; then a full-screen
 * overlay, which hid the list it filters. Now it covers the right half, with
 * the two figures stacked: stacked, their width is about half the screen's
 * anyway, so the other half is free to keep the list in view (user's call,
 * 2026-09-28). The figures fill their slot ([StackedMusclePicker]).
 *
 * Tapping a highlighted muscle again deselects it, and Clear stays as a
 * backstop for anyone who does not guess that.
 *
 * ## Opening it
 *
 * A leftward drag anywhere on the page pulls it in from the right, under the
 * finger, and a rightward one on it pushes it back — the navigation drawer's
 * motion mirrored, because the user asked for exactly that ([SlideOver]). A
 * rightward drag on the page is left to the drawer. It covers the app's top
 * bar as the drawer does, by being drawn in the app's [SlideOverHost].
 */
@Composable
fun MusclePanel(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    /** What the panel filters. */
    content: @Composable () -> Unit,
) {
    val state = rememberSlideOverState(open)
    // The caller's flag drives the slide (a Filter button); the slide reports back.
    LaunchedEffect(open) { if (state.isOpen != open) state.animateTo(open) }
    SlideOver(
        state = state,
        onSettled = { if (it != open) onOpenChange(it) },
        // Half the width: with the figures stacked that is all they need, and
        // the list stays in view beside them (the user asked for this).
        panelFraction = 0.5f,
        overlay = {
            MuscleFilterSide(selected, onSelectedChange, onDone = { onOpenChange(false) })
        },
        content = content,
    )
}

/**
 * The filter as a half-width side page: a header saying what the figures are
 * for, the two figures stacked as large as the height allows, then what is
 * picked as removable chips, and Clear / Done.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MuscleFilterSide(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .padding(vertical = 8.dp),
    ) {
        Text(
            "FILTER BY MUSCLE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        StackedMusclePicker(selected, onSelectedChange, Modifier.weight(1f).fillMaxWidth())
        if (selected.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Sorted by name so a chip does not jump when another is added.
                selected.sortedBy { muscleLabel(it) }.forEach { m ->
                    InputChip(
                        selected = true,
                        onClick = { onSelectedChange(selected - m) },
                        label = { Text(muscleLabel(m)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        ButtonRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (selected.isNotEmpty()) NeutralButton("Clear", onClick = { onSelectedChange(emptySet()) }, small = true)
            PrimaryButton("Done", onClick = onDone, small = true)
        }
    }
}

/** A title set up the left edge, so it costs a column of width rather than a row of height. */
@Composable
internal fun SideLabel(text: String) {
    Box(Modifier.fillMaxHeight().width(22.dp), contentAlignment = Alignment.Center) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            // Measured lying down, then stood up: rotate() alone draws the
            // text turned but lays it out flat, so a narrow column clipped it
            // to its first few letters.
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = 0, maxWidth = constraints.maxHeight, minHeight = 0),
                    )
                    layout(placeable.height, placeable.width) {
                        placeable.place(
                            (placeable.height - placeable.width) / 2,
                            (placeable.width - placeable.height) / 2,
                        )
                    }
                }
                .rotate(-90f),
        )
    }
}

/**
 * How far a drag must travel before it is treated as meant: above the
 * platform's touch slop, so a diagonal scroll opens nothing.
 */
private val OPEN_THRESHOLD = 40.dp
