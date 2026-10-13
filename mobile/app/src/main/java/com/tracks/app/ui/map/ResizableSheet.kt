// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A bottom panel that leaves the map usable, and that the person sizes.
 *
 * Not Material's ModalBottomSheet: that greys the map out and blocks it, and
 * the library is exactly where somebody wants to see the tracks it lists. The
 * panel takes no touches outside itself, so the map above stays live.
 *
 * Its handle resizes it. Dragging below [MIN_FRACTION] and letting go puts it
 * away, which is what the handle did before it could resize; anything above
 * that is kept, through [onFraction], so the panel opens at the size it was
 * left at.
 */
@Composable
fun ResizableSheet(
    fraction: Float,
    onFraction: (Float) -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var live by remember(fraction) { mutableFloatStateOf(fraction) }
    BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(LocalDensity.current) { (screenPx * live).toDp() }),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // Short: every row of the panel is a row less of list.
                        .height(14.dp)
                        .semantics { contentDescription = "Resize, or drag down to close" }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                live = (live - delta / screenPx).coerceIn(DISMISS_FRACTION, MAX_FRACTION)
                            },
                            onDragStopped = {
                                if (live < MIN_FRACTION) onDismiss()
                                else onFraction(live.coerceIn(MIN_FRACTION, MAX_FRACTION))
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                }
                content()
            }
        }
    }
}

/**
 * The short drag handle every sheet on the map wears — Material's own is a
 * band of padding the height of a list row, and on a screen that is mostly
 * map, every row of chrome is map hidden. For a Material sheet's `dragHandle`.
 */
@Composable
fun CompactDragHandle() {
    Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
    }
}

/** Where the panel opens the first time. */
const val DEFAULT_SHEET_FRACTION = 0.45f

/** The smallest it stays at: a header and a row or two. */
const val MIN_FRACTION = 0.2f

private const val MAX_FRACTION = 0.92f

/** How far it follows the finger below [MIN_FRACTION] before letting go puts it away. */
private const val DISMISS_FRACTION = 0.08f

/**
 * A bottom panel as tall as what it holds, for a single item's details.
 *
 * Non-modal for the same reason as [ResizableSheet]: the map behind it is the
 * thing being described, and greying it out hid exactly that. The handle is
 * the same short strip, and a downward drag past it puts the panel away.
 */
@Composable
fun ContentSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var dragged by remember { mutableFloatStateOf(0f) }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> dragged += delta },
                            onDragStarted = { dragged = 0f },
                            onDragStopped = { if (dragged > 60f) onDismiss() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                    )
                }
                content()
            }
        }
    }
}

