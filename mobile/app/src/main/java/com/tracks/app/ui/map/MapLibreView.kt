// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * A MapLibre [MapView], wrapped so Compose can hold one safely.
 *
 * ## Why this is more than an AndroidView call
 *
 * `MapView` predates Compose and owns a GL surface, a render thread, and a
 * tile cache. It expects the full Android view lifecycle — `onStart`,
 * `onResume`, `onPause`, `onStop`, `onDestroy`, `onLowMemory` — and skipping
 * those does not fail loudly. It leaks the surface, keeps the GPU awake behind
 * a backgrounded app, and on some devices renders a black rectangle after the
 * screen is locked and unlocked. On a tool whose entire premise is battery
 * discipline, a map that keeps rendering while the phone is in a pocket is not
 * a small bug.
 *
 * So the lifecycle is forwarded explicitly, and the view is destroyed when the
 * composable leaves.
 *
 * ## Style
 *
 * [styleJson] is the document Tracks' own server builds — the same one the web
 * app renders, with the same 124 layers. Neither client builds a style, which
 * is the whole point of the server-served style seam: a new map layer is a
 * server-side registry entry that both clients pick up, not two ports of the
 * same layer code that drift.
 */
@Composable
fun MapLibreView(
    styleJson: String,
    modifier: Modifier = Modifier,
    tunedGestures: Boolean = false,
    /**
     * Raise the ground by the style's DEM. Off for the small maps — an activity
     * track is read from directly above, where terrain costs a second set of
     * tiles and changes nothing about the line.
     */
    terrain: Boolean = false,
    onMapReady: (MapLibreMap, Style) -> Unit = { _, _ -> },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Must precede any MapView construction; idempotent, so calling it on each
    // composition is fine and cheaper than tracking whether we already have.
    remember { MapLibre.getInstance(context) }
    // Only on the map that is navigated by: see [MapGestures].
    val gestures = remember(tunedGestures) { if (tunedGestures) MapGestures(context) else null }
    // Held across style loads, so its camera listeners are registered once for
    // the life of the view rather than once per document.
    val terrain3D = remember(terrain) { if (terrain) Terrain3D() else null }

    val mapView = remember { MapView(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // onCreate exactly once, here rather than in the observer below.
    //
    // `addObserver` replays the owner's current state, so a composable created
    // while the Activity is already RESUMED receives ON_CREATE — and calling
    // MapView.onCreate twice does not throw. It leaves the view in a state
    // where the style loads and the sprite is fetched but **no tile is ever
    // requested**, which on screen is an empty map that looks like a network
    // failure and is not one. Found exactly that way.
    remember(mapView) { mapView.onCreate(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Destroyed here, not on ON_DESTROY: a composable can leave the
            // tree while the Activity lives on — switching tabs — and the GL
            // surface must not outlive the thing that was showing it.
            mapView.onDestroy()
        }
    }

    // The document currently on the map, so a recomposition that changes
    // nothing does not tear the style down and rebuild it.
    val applied = remember { mutableStateOf<String?>(null) }

    AndroidView(
        factory = {
            mapView.apply {
                claimGesturesFromScrollingParents(gestures)
                getMapAsync { map ->
                    gestures?.attach(map)
                    terrain3D?.attach(map)
                }
            }
        },
        // The style is applied here rather than in the factory, because the
        // factory runs exactly once and the style document genuinely changes:
        // the server narrows it to the archives on disk, so downloading a
        // region adds whole sources and layers — contours and the OSM overlay
        // are simply absent until the first one lands. Setting it only in the
        // factory left the map rendering the document it booted with, so a
        // finished download looked like a failed one.
        update = {
            if (applied.value != styleJson) {
                applied.value = styleJson
                it.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                        // Before the callback, so anything the caller adds on
                        // top of the style is added to a map that already knows
                        // it has a third dimension.
                        terrain3D?.onStyle(style, styleJson)
                        onMapReady(map, style)
                    }
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Keep the drags that land on the map.
 *
 * A `MapView` inside a `Column(verticalScroll(…))` loses: the user drags to pan,
 * the column passes touch-slop first, and the page scrolls out from under a map
 * that never moved. The map appears to pan "unreliably", which is really it
 * winning only the gestures that happened to stay under the slop threshold.
 *
 * The resolution is the standard Android one, and it does work across the
 * Compose interop seam. `AndroidView` wraps this view in an `AndroidViewHolder`,
 * whose `requestDisallowInterceptTouchEvent` is wired to the
 * `PointerInteropFilter` that Compose put in front of it — so telling the parent
 * to keep its hands off reaches the Compose gesture system, not just the View
 * hierarchy, and the scrollable ancestor stops competing for the pointer.
 *
 * The listener never consumes: it returns false so `MapView.onTouchEvent` still
 * does all the real panning and zooming. It claims the gesture; it does not
 * handle it.
 *
 * It is also the only `OnTouchListener` this view may have — a second one would
 * silently replace this one and hand every drag back to the scrolling parent —
 * so [gestures], which needs to see raw events too, is passed through rather than
 * registered separately.
 */
@SuppressLint("ClickableViewAccessibility")
private fun MapView.claimGesturesFromScrollingParents(gestures: MapGestures?) {
    setOnTouchListener { view, event ->
        gestures?.onTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
            // Released on the way out, so the page scrolls normally again the
            // moment the finger leaves the map.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.parent?.requestDisallowInterceptTouchEvent(false)
        }
        false
    }
}
