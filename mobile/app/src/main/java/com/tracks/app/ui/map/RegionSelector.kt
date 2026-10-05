// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rectangle you drag to choose an area to download.
 *
 * This started as "download whatever is on screen", which needs no new gesture
 * and no new affordance — and is the wrong call. Framing an area by panning
 * couples the selection to the camera, so you cannot look at the edges of what
 * you are about to download without changing it, and the thing you are choosing
 * has no edges you can see. A rectangle you can see and resize is what the
 * browser has, and it is worth the gesture handling.
 *
 * ## Screen space, not map space
 *
 * The rectangle is held in pixels and converted to coordinates only when asked.
 * Holding it as a [LatLngBounds] instead would mean re-projecting on every frame
 * of a drag, and — more to the point — would make the handles drift away from
 * the finger as the projection changes with latitude. Pixels are what the finger
 * is in.
 *
 * The consequence is that panning or zooming the map underneath moves the *area*
 * while the rectangle stays put on screen. That matches the browser and is the
 * behaviour people expect from a crop box: the box is a viewport onto the map,
 * not a thing pinned to the ground.
 */
@Composable
fun RegionSelector(
    map: MapLibreMap?,
    onBoundsChange: (LatLngBounds?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var canvas by remember { mutableStateOf(Size.Zero) }
    var rect by remember { mutableStateOf<SelectionRect?>(null) }

    /*
     * The camera, actually observed.
     *
     * This used to read `map.cameraPosition` during composition and use it as an
     * effect key, with a comment claiming that a pan under a stationary box
     * would re-report the area. It does not: a plain getter is not Compose
     * state, so reading it subscribes to nothing, and panning the map recomposed
     * nothing. The bounds went stale the moment the camera moved.
     *
     * That was the bug where a download covered "wherever the map was when I
     * opened the selector" — you would frame the box, pan the map to the valley
     * you actually wanted, and download the valley you started in. The box on
     * screen was right, the coordinates behind it were not, and nothing on
     * screen could tell you.
     *
     * A counter rather than the CameraPosition itself: what matters is that
     * *something changed*, and a monotonically increasing Int cannot be
     * accidentally equal to its predecessor the way two CameraPositions a
     * millimetre apart might be.
     */
    var cameraMoves by remember { mutableIntStateOf(0) }
    DisposableEffect(map) {
        val ready = map ?: return@DisposableEffect onDispose { }
        val onMove = MapLibreMap.OnCameraMoveListener { cameraMoves++ }
        // Move *and* idle: a fling settles after the last move callback, and the
        // last few degrees of a decelerating pan are exactly the part that
        // decides which side of a ridge the box lands on.
        val onIdle = MapLibreMap.OnCameraIdleListener { cameraMoves++ }
        ready.addOnCameraMoveListener(onMove)
        ready.addOnCameraIdleListener(onIdle)
        onDispose {
            ready.removeOnCameraMoveListener(onMove)
            ready.removeOnCameraIdleListener(onIdle)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                canvas = Size(size.width.toFloat(), size.height.toFloat())
                if (rect == null && size.width > 0) {
                    // Biased toward the top of the screen rather than centred.
                    // The selection bar sits over the bottom third, and a box
                    // centred in the canvas puts its two lower handles behind
                    // it — draggable in principle and invisible in practice.
                    rect = SelectionRect(
                        left = size.width * 0.15f,
                        top = size.height * 0.16f,
                        right = size.width * 0.85f,
                        bottom = size.height * 0.56f,
                    )
                }
            }
    ) {
        val current = rect ?: return@Box

        // The outline, and nothing else.
        //
        // There used to be a 38% black scrim over everything outside the box, so
        // the selection read as a hole cut in a mask. It looked right and worked
        // badly: choosing an area to take into the field is a decision about
        // terrain — is the whole ridge in, does it reach the trailhead — and
        // dimming everything outside the box darkens exactly the ground you are
        // deciding against. The map you are choosing from should look like the
        // map.
        //
        // Two strokes instead. A dark halo under a light line survives both a
        // snow field and a forest, which one stroke of either colour does not.
        Canvas(Modifier.fillMaxSize()) {
            val topLeft = Offset(current.left, current.top)
            val boxSize = Size(current.width, current.height)
            drawRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = OUTLINE_HALO.toPx()),
            )
            drawRect(
                color = Color.White,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = OUTLINE.toPx()),
            )
        }

        // The body, for moving the whole box. Placed before the handles so the
        // handles win any overlap at the corners.
        Box(
            Modifier
                .offset {
                    IntOffset(current.left.roundToInt(), current.top.roundToInt())
                }
                .size(
                    with(density) { current.width.toDp() },
                    with(density) { current.height.toDp() },
                )
                .pointerInput(canvas) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        rect = rect?.translated(drag, canvas)
                    }
                }
        )

        Corner.entries.forEach { corner ->
            val position = corner.positionIn(current)
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (position.x - HANDLE_PX / 2).roundToInt(),
                            (position.y - HANDLE_PX / 2).roundToInt(),
                        )
                    }
                    .size(HANDLE_DP)
                    .clip(CircleShape)
                    .background(Color.White)
                    .pointerInput(canvas, corner) {
                        detectDragGestures { change, drag ->
                            // Consumed so the map underneath does not also pan.
                            // Without this every resize drags the camera too and
                            // the box appears to fight the finger.
                            change.consume()
                            rect = rect?.resized(corner, drag, canvas)
                        }
                    }
            )
        }

        // Conversion happens here rather than inside the drag handlers so it
        // runs once per settled state instead of once per pointer event — and
        // on every camera move, because the box standing still over a map that
        // moved is a different area.
        androidx.compose.runtime.LaunchedEffect(current, cameraMoves) {
            onBoundsChange(current.toBounds(map))
        }
    }
}

/** Which corner a handle drags. */
private enum class Corner {
    TopLeft, TopRight, BottomLeft, BottomRight;

    fun positionIn(rect: SelectionRect): Offset = when (this) {
        TopLeft -> Offset(rect.left, rect.top)
        TopRight -> Offset(rect.right, rect.top)
        BottomLeft -> Offset(rect.left, rect.bottom)
        BottomRight -> Offset(rect.right, rect.bottom)
    }
}

/**
 * The selection in screen pixels.
 *
 * Kept normalised — left below right, top below bottom — by [resized] rather
 * than by the caller, so dragging a corner past its opposite flips the box
 * instead of inverting it into a rectangle with negative width.
 */
private data class SelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun translated(drag: Offset, canvas: Size): SelectionRect {
        // Clamped as a whole so a drag toward an edge stops rather than
        // squashing the box against it.
        val dx = drag.x.coerceIn(-left, canvas.width - right)
        val dy = drag.y.coerceIn(-top, canvas.height - bottom)
        return copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)
    }

    fun resized(corner: Corner, drag: Offset, canvas: Size): SelectionRect {
        val moved = when (corner) {
            Corner.TopLeft -> copy(left = left + drag.x, top = top + drag.y)
            Corner.TopRight -> copy(right = right + drag.x, top = top + drag.y)
            Corner.BottomLeft -> copy(left = left + drag.x, bottom = bottom + drag.y)
            Corner.BottomRight -> copy(right = right + drag.x, bottom = bottom + drag.y)
        }
        return SelectionRect(
            left = min(moved.left, moved.right).coerceIn(0f, canvas.width),
            right = max(moved.left, moved.right).coerceIn(0f, canvas.width),
            top = min(moved.top, moved.bottom).coerceIn(0f, canvas.height),
            bottom = max(moved.top, moved.bottom).coerceIn(0f, canvas.height),
        ).atLeastMinimum(canvas)
    }

    /** A box dragged to nothing has no handles left to grab. */
    private fun atLeastMinimum(canvas: Size): SelectionRect {
        if (width >= MIN_PX && height >= MIN_PX) return this
        return SelectionRect(
            left = left,
            top = top,
            right = min(left + max(width, MIN_PX), canvas.width),
            bottom = min(top + max(height, MIN_PX), canvas.height),
        )
    }

    fun toBounds(map: MapLibreMap?): LatLngBounds? {
        val projection = map?.projection ?: return null
        val a = projection.fromScreenLocation(PointF(left, top))
        val b = projection.fromScreenLocation(PointF(right, bottom))
        // Built from explicit corners rather than include(): the two screen
        // points are opposite corners of the box but not necessarily
        // north-east and south-west, and LatLngBounds cares which is which.
        return LatLngBounds.Builder()
            .include(LatLng(max(a.latitude, b.latitude), max(a.longitude, b.longitude)))
            .include(LatLng(min(a.latitude, b.latitude), min(a.longitude, b.longitude)))
            .build()
    }
}

/** Thick enough to read over aerial-looking terrain, thin enough not to hide
 *  what is just inside the edge. */
private val OUTLINE = 2.dp
private val OUTLINE_HALO = 5.dp

private const val MIN_PX = 120f
private const val HANDLE_PX = 76f
private val HANDLE_DP = 26.dp
