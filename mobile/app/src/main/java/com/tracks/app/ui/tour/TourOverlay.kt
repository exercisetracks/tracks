// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.tour

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.theme.Tokens
import kotlinx.coroutines.delay

/**
 * Where every [tourAnchor] on screen is, by name.
 *
 * The web finds a tip's target with `document.querySelector`; Compose has no
 * DOM to query, so the targets report themselves instead. One instance, at the
 * shell, provided through [LocalTourAnchors].
 */
@OptIn(ExperimentalFoundationApi::class)
class TourAnchors {
    internal val bounds = mutableStateMapOf<String, Rect>()
    internal val requesters = HashMap<String, BringIntoViewRequester>()
}

/** Null outside the shell — previews, tests — where an anchor does nothing. */
val LocalTourAnchors = staticCompositionLocalOf<TourAnchors?> { null }

/**
 * Mark this element as something a tip can point at — the phone's
 * `data-tour="…"`.
 *
 * Bounds are taken in root coordinates, clipped by whatever scrolls the
 * element, so an anchor scrolled out of sight reports as empty rather than as
 * a ring drawn over the app bar. It also carries a [BringIntoViewRequester],
 * which is how the overlay scrolls a target below the fold into view, as the
 * web's `scrollIntoView` does.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tourAnchor(id: String): Modifier = composed {
    val anchors = LocalTourAnchors.current ?: return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(anchors, id) {
        anchors.requesters[id] = requester
        onDispose {
            // Only if it is still ours: during a page transition the incoming
            // page can register the same name before the outgoing one leaves.
            if (anchors.requesters[id] === requester) {
                anchors.requesters.remove(id)
                anchors.bounds.remove(id)
            }
        }
    }
    Modifier
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { anchors.bounds[id] = it.boundsInRoot() }
}

/** [tourAnchor] around content that has no modifier of its own to take one. */
@Composable
fun TourAnchor(id: String, content: @Composable () -> Unit) {
    Box(Modifier.tourAnchor(id)) { content() }
}

/**
 * The tip card for the active step, and the ring around what it points at.
 *
 * Non-blocking, as on the web: there is no scrim, so the page underneath stays
 * usable — the ring is drawn and never touched, and only the card itself takes
 * taps. A tutorial that froze the app would teach people to dismiss it before
 * reading it.
 *
 * System back dismisses the tour, the way Escape does in the browser, and it
 * counts as seen. Hidden — not dismissed — while [visible] is false, which is
 * while the drawer is open: the first tip points at the menu button, and
 * tapping it to look should not leave a card floating over the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TourOverlay(
    state: TourState,
    anchors: TourAnchors,
    visible: Boolean,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    val step = state.current ?: return
    BackHandler(enabled = visible, onBack = onClose)
    if (!visible) return

    val anchorId = step.anchor
    // Not drawn until the target has had a chance to appear and scroll into
    // view, so the card does not open centred and then jump across the screen.
    // A target that never appears (no upcoming workouts, an empty list) falls
    // back to centred, as on the web.
    var settled by remember(state.active, state.step) { mutableStateOf(anchorId == null) }
    LaunchedEffect(state.active, state.step) {
        if (anchorId == null) return@LaunchedEffect
        repeat(ANCHOR_WAIT_TRIES) {
            val requester = anchors.requesters[anchorId]
            if (requester != null) {
                runCatching { requester.bringIntoView() }
                settled = true
                return@LaunchedEffect
            }
            delay(ANCHOR_WAIT_MS)
        }
        settled = true
    }
    if (!settled) return

    val rect = anchorId?.let { anchors.bounds[it] }?.takeIf { it.width > 0f && it.height > 0f }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val accent = MaterialTheme.colorScheme.primary
    val cardColor = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val local = rect?.translate(-origin)

        // The ring. A Canvas has no pointer input, so taps go straight through
        // to the element it is drawn around.
        if (local != null) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = RING_PAD.toPx()
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(local.left - pad, local.top - pad),
                    size = Size(local.width + 2 * pad, local.height + 2 * pad),
                    cornerRadius = CornerRadius(Tokens.Radius.lg.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        val cardWidth = minOf(maxWidth - MARGIN * 2, MAX_CARD_WIDTH)
        Layout(
            content = {
                TipCard(
                    step = step,
                    index = state.step,
                    count = state.steps.size,
                    onNext = onNext,
                    onPrev = onPrev,
                    onClose = onClose,
                    modifier = Modifier.width(cardWidth),
                )
                // The arrow: drawn pointing up, turned over when the card sits
                // above its target.
                Canvas(Modifier.size(ARROW_W, ARROW_H)) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(size.width / 2, 0f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(path, cardColor)
                    drawPath(path, outline, style = Stroke(width = 1.dp.toPx()))
                }
            },
        ) { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            val card = measurables[0].measure(loose)
            val arrow = measurables[1].measure(loose)
            val target = local?.let {
                IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
            }
            val place = placeCard(
                anchor = target,
                cardWidth = card.width,
                cardHeight = card.height,
                viewWidth = constraints.maxWidth,
                viewHeight = constraints.maxHeight,
                margin = MARGIN.roundToPx(),
                gap = (RING_PAD + GAP).roundToPx(),
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                card.place(place.x, place.y)
                if (target != null && place.below != null) {
                    val inset = Tokens.Radius.lg.roundToPx() + arrow.width / 2
                    val cx = target.center.x.coerceIn(place.x + inset, place.x + card.width - inset)
                    val y = if (place.below) place.y - arrow.height + 1 else place.y + card.height - 1
                    arrow.placeWithLayer(IntOffset(cx - arrow.width / 2, y)) {
                        rotationZ = if (place.below) 0f else 180f
                    }
                }
            }
        }
    }
}

@Composable
private fun TipCard(
    step: TourStep,
    index: Int,
    count: Int,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A Material 3 Surface swallows touches, which is what keeps a tap on the
    // card's text from landing on the page underneath it.
    Surface(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Tokens.Radius.xl),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row {
                Text(
                    step.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(top = 10.dp),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss tips", Modifier.size(18.dp))
                }
            }
            Text(
                step.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp, top = 2.dp),
            )
            Row(
                Modifier.padding(top = 12.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1} / $count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (count > 1) NeutralButton("Skip", onClick = onClose, small = true)
                if (index > 0) NeutralButton("Back", onClick = onPrev, small = true)
                PrimaryButton(if (index >= count - 1) "Done" else "Next", onClick = onNext, small = true)
            }
        }
    }
}

/**
 * Where the card goes. [below] is true under the target, false above it, and
 * null when it points at nothing — centred, or laid over a target too tall to
 * sit beside.
 */
internal data class CardPlacement(val x: Int, val y: Int, val below: Boolean?)

/**
 * The web's computePosition, for a screen too narrow for left and right: the
 * card goes under its target when it fits, over it when that fits instead,
 * and otherwise — a list, a card taller than half the screen — along the
 * bottom edge, over the target, where the ring still shows what it means.
 * Horizontally it centres on the target and is clamped to the screen.
 */
internal fun placeCard(
    anchor: IntRect?,
    cardWidth: Int,
    cardHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    margin: Int,
    gap: Int,
): CardPlacement {
    val maxX = (viewWidth - cardWidth - margin).coerceAtLeast(margin)
    if (anchor == null) {
        return CardPlacement(
            x = ((viewWidth - cardWidth) / 2).coerceIn(margin, maxX),
            y = ((viewHeight - cardHeight) / 2).coerceAtLeast(margin),
            below = null,
        )
    }
    val x = (anchor.center.x - cardWidth / 2).coerceIn(margin, maxX)
    val roomBelow = viewHeight - margin - (anchor.bottom + gap)
    val roomAbove = anchor.top - gap - margin
    return when {
        cardHeight <= roomBelow -> CardPlacement(x, anchor.bottom + gap, below = true)
        cardHeight <= roomAbove -> CardPlacement(x, anchor.top - gap - cardHeight, below = false)
        else -> CardPlacement(x, (viewHeight - margin - cardHeight).coerceAtLeast(margin), below = null)
    }
}

private val MARGIN = 16.dp
private val GAP = 10.dp
private val RING_PAD = 4.dp
private val MAX_CARD_WIDTH = 360.dp
private val ARROW_W = 16.dp
private val ARROW_H = 8.dp

/** How long to wait for a target to compose: 20 × 80 ms, the web's retry. */
private const val ANCHOR_WAIT_TRIES = 20
private const val ANCHOR_WAIT_MS = 80L
