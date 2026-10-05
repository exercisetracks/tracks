// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A full-screen page that slides in from the right, moving exactly as the
 * navigation drawer does from the left.
 *
 * The user asked for the muscle filter and the exercise picker to open "exactly
 * the same as the right swipe to open the navigation menu". So the numbers are
 * Material 3's drawer's (1.3): the page follows the finger 1:1, a release
 * settles it with a 256 ms tween, a fling over 400 dp/s decides the side, and
 * otherwise the nearer side wins; a scrim darkens what is being covered in
 * proportion. The old filter instead appeared at a 48 dp threshold as a
 * dialog, with nothing under the finger — a cut, not a slide.
 *
 * Only a leftward drag opens and only a rightward one closes, so the drawer's
 * own rightward swipe is left alone on a closed page. The detector sits on the
 * container and sees events after the children, so lists keep their scroll and
 * rows their drag handles; it never claims a gesture a child consumed.
 *
 * The page is drawn in the nearest [SlideOverHost] when there is one in the
 * same window — above the app's top bar, as the drawer is — and in place
 * otherwise. The window check matters: a page inside a Dialog (the workout
 * builder is one) that handed its layer to the app's host had it drawn in the
 * main window, underneath the dialog, so the picker opened and nobody saw it.
 */
@Composable
fun SlideOver(
    state: SlideOverState,
    overlay: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How much of the width the page covers, from the right. Under 1 the rest
     * of the screen stays in view behind the scrim, and tapping it closes the
     * page, as tapping beside an open drawer does.
     */
    panelFraction: Float = 1f,
    /** Told where a gesture, the scrim or back left the page. */
    onSettled: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settled by rememberUpdatedState(onSettled)
    val layer: @Composable () -> Unit = {
        if (state.progress.value > 0f) {
            BackHandler(enabled = state.isOpen) { scope.launch { state.close(); settled(false) } }
            Box(Modifier.fillMaxSize().swipeBetween(state, scope, panelFraction) { settled(it) }) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = state.progress.value }
                        .background(DrawerDefaults.scrimColor)
                        .pointerInput(Unit) {
                            detectTapGestures { scope.launch { state.close(); settled(false) } }
                        },
                )
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(panelFraction)
                        .align(Alignment.CenterEnd)
                        .graphicsLayer { translationX = (1f - state.progress.value) * size.width },
                ) {
                    overlay()
                }
            }
        }
    }
    val view = LocalView.current
    val host = LocalSlideOverHost.current?.takeIf { it.view === view }
    Box(modifier.fillMaxSize().swipeBetween(state, scope, panelFraction) { settled(it) }) {
        content()
        if (host == null) layer()
    }
    if (host != null) {
        val current by rememberUpdatedState(layer)
        DisposableEffect(host) {
            val entry: @Composable () -> Unit = { current() }
            host.layers += entry
            onDispose { host.layers -= entry }
        }
    }
}

/** Where [SlideOver] pages are drawn: wrap the app's scaffold in it. */
@Composable
fun SlideOverHost(content: @Composable () -> Unit) {
    val view = LocalView.current
    val host = remember(view) { SlideOverHostState(view) }
    CompositionLocalProvider(LocalSlideOverHost provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            host.layers.forEach { it() }
        }
    }
}

@Stable
class SlideOverHostState(internal val view: android.view.View) {
    var layers by mutableStateOf(listOf<@Composable () -> Unit>())
}

private val LocalSlideOverHost = staticCompositionLocalOf<SlideOverHostState?> { null }

/** 0 = closed, 1 = open. */
@Stable
class SlideOverState(open: Boolean) {
    val progress = Animatable(if (open) 1f else 0f)
    /** Where it is going, so a page mid-slide answers as the side it will land on. */
    val isOpen: Boolean get() = progress.targetValue > 0.5f
    suspend fun open() = progress.animateTo(1f, SETTLE)
    suspend fun close() = progress.animateTo(0f, SETTLE)
    suspend fun animateTo(open: Boolean) = if (open) open() else close()
}

@Composable
fun rememberSlideOverState(open: Boolean = false) = remember { SlideOverState(open) }

private fun Modifier.swipeBetween(
    state: SlideOverState,
    scope: CoroutineScope,
    panelFraction: Float,
    onSettled: (Boolean) -> Unit,
): Modifier =
    pointerInput(state) {
        val slop = viewConfiguration.touchSlop
        val fling = FLING_VELOCITY.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f
            var dy = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val d = change.positionChange()
                dx += d.x
                dy += d.y
                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                if (abs(dx) > slop && abs(dx) > 2 * abs(dy)) {
                    // Only towards the side that is not showing.
                    if ((dx < 0) == state.isOpen) return@awaitEachGesture
                    change.consume()
                    break
                }
            }
            // The panel's width, so the page stays under the finger however wide it is.
            val width = (size.width * panelFraction).coerceAtLeast(1f)
            val from = state.progress.value
            val tracker = VelocityTracker()
            var moved = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) break
                moved += change.positionChange().x
                change.consume()
                val to = (from - moved / width).coerceIn(0f, 1f)
                scope.launch { state.progress.snapTo(to) }
            }
            val v = tracker.calculateVelocity().x
            val open = when {
                v < -fling -> true
                v > fling -> false
                else -> state.progress.value > 0.5f
            }
            scope.launch { state.animateTo(open); onSettled(open) }
        }
    }

/** Material 3's DrawerVelocityThreshold. */
private val FLING_VELOCITY = 400.dp
/** Material 3's drawer AnimationSpec. */
private val SETTLE = TweenSpec<Float>(durationMillis = 256)
