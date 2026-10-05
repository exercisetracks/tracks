// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import com.tracks.app.ui.theme.Tokens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Create
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.composed
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import com.tracks.core.format.distance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.MapDrawerButton
import com.tracks.app.ui.components.EmptyState
import com.tracks.core.api.Waypoint
import com.tracks.core.api.heatmapPoints
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * The map, with the user's whole GPS history burned into it.
 *
 * The heatmap is added as a layer here rather than being one of the 124 the
 * server's style already defines, because the data is per-user and encrypted:
 * the style document is public cartography that any client can hold, while
 * this is the one thing on the map that is nobody else's business. Keeping the
 * two apart is also what lets the style be cached on disk in the clear.
 *
 * MapLibre's built-in heatmap layer, not a hand-written GL shader. The web app
 * carries 666 lines of GLSL for this because the browser's MapLibre build
 * predates the native heatmap type being usable there; on Android it is a
 * layer type, and the plan's instruction was explicit — feed it the points and
 * delete the problem.
 */
@Composable
fun MapScreen(
    vm: MapViewModel,
    regionsVm: RegionsViewModel,
    toolsVm: MapToolsViewModel,
    /**
     * Opens the navigation sidebar. The map has no app bar to put the button in
     * — it is the one screen whose content is meant to reach every edge — so it
     * carries its own, in the same top-left corner as everywhere else.
     */
    onOpenDrawer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val regions by regionsVm.state.collectAsStateWithLifecycle()
    val tools by toolsVm.state.collectAsStateWithLifecycle()

    var map by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var style by remember { mutableStateOf<org.maplibre.android.maps.Style?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // Selection mode is its own thing rather than part of the sheet: the whole
    // point of a drag box is seeing the map under it, which a modal sheet
    // covering half the screen would defeat.
    var choosingArea by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(FollowMode.Off) }

    val context = androidx.compose.ui.platform.LocalContext.current
    // OpenDocument rather than GetContent: GPX is not a type Android's picker
    // reliably knows, and a MIME filter that excludes it leaves the file the
    // user is looking at greyed out with no explanation.
    val pickTrackFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@rememberLauncherForActivityResult
        toolsVm.importTrack(uri.fileName(context) ?: "track.gpx", bytes)
    }
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // Advance only on success. Cycling the mode anyway would leave the
        // button showing "following" over a map that cannot follow anything.
        if (granted.values.any { it }) follow = FollowMode.Follow
    }
    var selection by remember { mutableStateOf<org.maplibre.android.geometry.LatLngBounds?>(null) }
    // Where the floating controls are, in screen pixels.
    //
    // The map is an AndroidView under Compose's own overlay, and a tap on a
    // button was reaching both: the sheet opened *and* the map reported a click
    // at the same spot, so pressing "menu" also popped the point inspector, as
    // if it had been double-tapped. Consuming the gesture in Compose does not
    // help — the embedded view gets its own dispatch. Knowing where the chrome
    // is and declining map clicks underneath it does.
    val chrome = remember { mutableStateListOf<android.graphics.RectF>() }

    // Which handle a little menu is open against, and where to draw it.
    var handleMenu by remember { mutableStateOf<Int?>(null) }
    var handleMenuAt by remember { mutableStateOf(IntOffset.Zero) }
    var menuSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val screenWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.toInt()
    // Set while a finger is on a handle. The map must not also act on that
    // gesture — dragging a point would otherwise pan the map under it, and
    // lifting would drop a second point on top of the first.
    var grabbedHandle by remember { mutableStateOf(false) }

    // Which groups the user has switched off. Hidden rather than shown, so a
    // style reloading (a region download changes it) leaves everything visible
    // by default and only the explicit refusals survive.
    var hiddenGroups by rememberSaveable { mutableStateOf(defaultHiddenGroups()) }

    // Tell the downloads poller whether anyone is looking. It runs in a
    // ViewModel, which outlives this screen going away — a download started
    // before the phone went into a pocket would otherwise keep waking the radio
    // every three seconds to move a progress bar nobody can see. It cannot stop
    // outright: finishing the server's half is what starts the phone's.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, regionsVm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> regionsVm.setOnScreen(true)
                Lifecycle.Event.ON_STOP -> regionsVm.setOnScreen(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Leaving the tab is the same as leaving the screen, and this
            // composable is destroyed for both.
            regionsVm.setOnScreen(false)
        }
    }

    val haptics = LocalHapticFeedback.current

    // Back, mid-draw, means "not this" rather than "not this screen". Left to
    // the system default it popped the whole tab off the back stack to
    // whatever opened it — usually the dashboard — discarding a half-drawn
    // route along the way with no more warning than leaving any other screen
    // gets. Enabled only while there is a draft to lose; back does its
    // ordinary thing the rest of the time.
    androidx.activity.compose.BackHandler(enabled = tools.tool == MapTool.Route) {
        toolsVm.cancelRoute()
    }

    Box(modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> EmptyState(
                title = "No map yet",
                body = state.error ?: "",
                modifier = Modifier.fillMaxSize(),
            )

            state.styleJson != null || state.blankBasemap -> {
                // Just the basemap. The heatmap lives on the dashboard, as it
                // does on the web: this tab is for reading terrain and finding
                // your way, and a year of overlaid GPS obscures exactly the
                // contours and paths someone opens a map to look at.
                val canvas = MaterialTheme.colorScheme.surfaceVariant
                val styleJson = state.styleJson ?: remember(canvas) {
                    blankStyleJson(String.format("#%06X", 0xFFFFFF and canvas.toArgb()))
                }
                MapLibreView(
                    styleJson = styleJson,
                    modifier = Modifier.fillMaxSize(),
                    // Tilt and rotation are first-class gestures on this map, and
                    // each is tuned so it cannot be triggered by accident: see [MapGestures].
                    tunedGestures = true,
                    // And what it tilts into is real ground: see [Terrain3D].
                    terrain = true,
                    onMapReady = { ready, loaded ->
                        map = ready
                        style = loaded
                        // Wherever this phone last left the map, before
                        // anything else — a saved area's outline or a
                        // search result moving the camera afterwards is
                        // expected to win, but the default open should not
                        // be the world view every time the tab is reopened.
                        // No animation: a visible fly-in from the default
                        // position to the real one on every open would be
                        // its own kind of wrong.
                        MapViewport.restore(context)?.let {
                            ready.moveCamera(CameraUpdateFactory.newCameraPosition(it))
                        }
                        // Capped below a full 80 degrees, matching the browser's
                        // MAX_PITCH: past that the terrain tile fan explodes and
                        // the horizon costs more to draw than it is worth.
                        ready.setMaxPitchPreference(MAX_PITCH)
                        // The same zoom window the browser uses (useMapInit).
                        // Not cosmetic: past z16 there is no data left to show
                        // — the detail archives stop at 15 and MapLibre would
                        // simply magnify the last tile — and with terrain on,
                        // a DEM stretched that far shatters the line work into
                        // dashes. Below z1 the world repeats.
                        ready.setMinZoomPreference(MIN_ZOOM)
                        ready.setMaxZoomPreference(MAX_ZOOM)
                        // Two fingers dragged up and down tilts into 3D. Set
                        // explicitly rather than left to MapLibre's default,
                        // because the dashboard's map turns this *off* — the
                        // difference between the two is deliberate and should
                        // be readable in both places.
                        ready.uiSettings.isTiltGesturesEnabled = true
                        // Rotation is what makes heading-up worth having, and
                        // the compass is how you get back to north afterwards.
                        // MapLibre only shows it while the map is turned.
                        ready.uiSettings.isRotateGesturesEnabled = true
                        ready.uiSettings.isCompassEnabled = true
                        ready.uiSettings.setCompassMargins(0, COMPASS_TOP_MARGIN, COMPASS_RIGHT_MARGIN, 0)
                    },
                )

                // Re-applied on every style load as well as every mode change:
                // the dot lives in the style's own layers, so a region download
                // — which changes the served style — would otherwise take it
                // away silently.
                LaunchedEffect(map, style, follow) {
                    val ready = map ?: return@LaunchedEffect
                    val loaded = style ?: return@LaunchedEffect
                    MyLocation.apply(context, ready, loaded, follow)
                }

                // Taps mean different things per tool, and are ignored entirely
                // while the download box is up — a tap there is aimed at the
                // box, not the map under it.
                //
                // Registered once per map and *only* once. MapLibre accumulates
                // click listeners and has no "replace"; keying this effect on
                // the tool as well would add a second listener on every switch,
                // each holding the tool it was created with. Three switches and
                // one tap inspected a point, dropped a waypoint, and inspected
                // again. `rememberUpdatedState` is what lets the single
                // long-lived listener read today's values instead.
                //
                // (There is a `removeOnMapClickListener`, so add-and-remove would
                // also work. One listener that reads current state is simpler
                // than a pair of effects that have to stay balanced.)
                val currentTool = rememberUpdatedState(tools.tool)
                val boxUp = rememberUpdatedState(choosingArea)
                val chromeSnapshot = rememberUpdatedState(chrome.toList())
                // The map hears a tap on a handle as well as the overlay does —
                // an embedded view gets its own dispatch — so it has to know
                // which taps are not its business. Without this, adjusting a
                // point also added one.
                val handles = rememberUpdatedState(tools.route.waypoints)
                val dragging = rememberUpdatedState(grabbedHandle)
                // A long press starts drawing, from wherever it landed.
                //
                // Reaching for a tool in the rail first is the step people skip
                // — the intent is "a track starts *here*", and having to arm a
                // mode before expressing it loses the point you were pointing
                // at. Pressing again while already drawing simply adds a
                // waypoint, so the gesture means one thing throughout.
                LaunchedEffect(map) {
                    val ready = map ?: return@LaunchedEffect
                    ready.addOnMapLongClickListener { point ->
                        if (!boxUp.value && !dragging.value &&
                            !chromeSnapshot.value.covers(ready, point) &&
                            ready.handleNear(point, handles.value) == null
                        ) {
                            if (currentTool.value != MapTool.Route) {
                                toolsVm.selectTool(MapTool.Route)
                            }
                            toolsVm.addWaypoint(point)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        true
                    }
                }

                LaunchedEffect(map) {
                    val ready = map ?: return@LaunchedEffect
                    ready.addOnMapClickListener { point ->
                        if (!boxUp.value && !dragging.value &&
                            !chromeSnapshot.value.covers(ready, point)
                        ) {
                            when (currentTool.value) {
                                // A saved thing under the finger wins over the
                                // ground under it. Tapping your own track used
                                // to fall through to the point inspector, which
                                // reported the land cover and said nothing at
                                // all about the route being pointed at.
                                MapTool.Inspect -> {
                                    val hit = ready.savedItemAt(point)
                                    if (!toolsVm.selectAt(hit.courseId, hit.waypointId)) {
                                        toolsVm.inspect(point, ready.surroundingsAt(point))
                                    }
                                }

                                // A tap on a handle is the handle's, and the
                                // overlay has already opened its menu.
                                MapTool.Route ->
                                    if (ready.handleNear(point, handles.value) == null) {
                                        toolsVm.addWaypoint(point)
                                    }
                            }
                        }
                        true
                    }
                }

                // Remember where this leaves the map — see [MapViewport] for
                // why idle is the only save point that cannot be missed by a
                // plain close.
                DisposableEffect(map) {
                    val ready = map
                    if (ready == null) {
                        onDispose {}
                    } else {
                        val idle = MapLibreMap.OnCameraIdleListener {
                            MapViewport.save(context, ready.cameraPosition)
                        }
                        ready.addOnCameraIdleListener(idle)
                        onDispose { ready.removeOnCameraIdleListener(idle) }
                    }
                }

                // Grants public-land area names (BLM, wilderness, national
                // forest/park) their turn to print — see [PublicLandLabelRank]
                // for why the layer shows nothing on its own until this runs
                // at least once.
                val labelRankScope = rememberCoroutineScope()
                val labelRank = remember { PublicLandLabelRank(labelRankScope) }
                DisposableEffect(map, style) {
                    val ready = map
                    val loaded = style
                    if (ready == null || loaded == null) {
                        onDispose {}
                    } else {
                        labelRank.schedule(ready, loaded)
                        val idle = MapLibreMap.OnCameraIdleListener { labelRank.schedule(ready, loaded) }
                        ready.addOnCameraIdleListener(idle)
                        onDispose { ready.removeOnCameraIdleListener(idle) }
                    }
                }

                // Redrawn on every change to the list, so a region appears the
                // moment its download starts and firms up when it finishes.
                LaunchedEffect(style, regions.rows) {
                    style?.let { drawRegions(it, regions.rows) }
                }

                // Fetched when the map opens, not when the library sheet does.
                // A saved track exists to be looked at; keeping it invisible
                // until somebody opened a list would mean the map never showed
                // the routes planned on it.
                LaunchedEffect(Unit) { toolsVm.loadLibrary() }

                LaunchedEffect(map, tools.focus) {
                    val ready = map ?: return@LaunchedEffect
                    when (val target = tools.focus) {
                        null -> Unit
                        is Focus.Point -> ready.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(target.lat, target.lng), SEARCH_RESULT_ZOOM,
                            )
                        )
                        // Padded, because a line drawn hard against the screen
                        // edge is a line you cannot see the end of — and capped,
                        // because a fit is only as sensible as the box it is
                        // given. A hundred-metre track framed exactly would
                        // arrive at maximum zoom showing the track and nothing
                        // whatsoever around it, which answers "what does it look
                        // like" while hiding "where is it".
                        is Focus.Area -> frameCamera(
                            ready,
                            listOf(
                                LatLng(target.bounds[3], target.bounds[2]),
                                LatLng(target.bounds[1], target.bounds[0]),
                            ),
                            paddingPx = FOCUS_PADDING_PX,
                            maxZoom = CONTEXT_ZOOM,
                        )
                    }
                    if (tools.focus != null) toolsVm.focusHandled()
                }

                LaunchedEffect(style, tools.courseGeoJson, tools.waypoints) {
                    val loaded = style ?: return@LaunchedEffect
                    drawLibrary(context, loaded, tools.courseGeoJson, tools.waypoints)
                }

                LaunchedEffect(style, tools.route.geoJson, tools.route.waypoints) {
                    val loaded = style ?: return@LaunchedEffect
                    drawRoute(loaded, tools.route)
                }

                LaunchedEffect(style, tools.point) {
                    val loaded = style ?: return@LaunchedEffect
                    drawTappedPoint(
                        loaded,
                        tools.point?.let { LatLng(it.lat, it.lon) },
                    )
                }

                LaunchedEffect(style, tools.route.segments) {
                    val loaded = style ?: return@LaunchedEffect
                    drawSegmentLabels(loaded, tools.route.segments)
                }

                if (tools.tool == MapTool.Route) {
                    // Handles first in the stack so the bar, which is chrome,
                    // draws over them rather than under.
                    RouteHandles(
                        map = map,
                        waypoints = tools.route.waypoints,
                        grabbed = { grabbedHandle = it },
                        onMove = toolsVm::moveWaypoint,
                        onTap = { index, at ->
                            handleMenu = index
                            handleMenuAt = at
                        },
                    )
                    RouteBar(
                        route = tools.route,
                        onSave = { name, color -> toolsVm.saveTrack(name, color) },
                        onSnapToTrails = toolsVm::setSnapToTrails,
                        onCancel = toolsVm::cancelRoute,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .reportBoundsTo(chrome),
                    )

                    // Anchored to the handle it is about. Dismissed by anything
                    // that could make the index mean a different point.
                    handleMenu?.let { index ->
                        if (index !in tools.route.waypoints.indices) {
                            handleMenu = null
                            return@let
                        }
                        HandleMenu(
                            onDelete = {
                                toolsVm.removeWaypointAt(index)
                                handleMenu = null
                            },
                            onDismiss = { handleMenu = null },
                            modifier = Modifier
                                // Above the handle and centred on it, rather
                                // than under the finger that opened it —
                                // otherwise the one thing it is about is the one
                                // thing it covers. Kept inside the screen, since
                                // a handle near an edge would push it off.
                                .onSizeChanged { menuSize = it }
                                .offset {
                                    IntOffset(
                                        (handleMenuAt.x - menuSize.width / 2).coerceIn(
                                            0,
                                            (screenWidthPx - menuSize.width).coerceAtLeast(0),
                                        ),
                                        (handleMenuAt.y - menuSize.height - HANDLE_MENU_GAP_PX)
                                            .coerceAtLeast(0),
                                    )
                                }
                                .reportBoundsTo(chrome),
                        )
                    }
                } else if (handleMenu != null) {
                    handleMenu = null
                }

                // Re-applied whenever either side changes: switching a group off
                // is remembered across a style reload, which a region download
                // causes.
                LaunchedEffect(style, hiddenGroups) {
                    val loaded = style ?: return@LaunchedEffect
                    LAYER_GROUPS.forEach { group ->
                        applyGroupVisibility(loaded, group, group.id !in hiddenGroups)
                    }
                }

                if (choosingArea) {
                    RegionSelector(
                        map = map,
                        onBoundsChange = { bounds ->
                            selection = bounds
                            bounds?.let(regionsVm::estimate)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    SelectionBar(
                        vm = regionsVm,
                        state = regions,
                        bounds = selection,
                        onDone = {
                            choosingArea = false
                            selection = null
                            regionsVm.clearEstimate()
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // Follows the user out of the downloads sheet. An area takes
                // minutes to cut on the server, and the sheet is the first
                // thing anyone dismisses to go and watch the map while they
                // wait — leaving no evidence the work was still running.
                DownloadBanner(
                    rows = regions.rows,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )

                MapControls(
                    onSearch = { showSearch = true },
                    onMenu = { showMenu = true },
                    follow = follow,
                    onToggleFollow = {
                        if (MyLocation.permitted(context)) {
                            follow = follow.next()
                        } else {
                            askLocation.launch(MyLocation.permissions)
                        }
                    },
                    onLibrary = {
                        showLibrary = true
                        toolsVm.loadLibrary()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .reportBoundsTo(chrome),
                )

                // Top-left, where the app bar's button sits on every
                // other screen. Hidden while framing a download, because
                // the selection box reaches into that corner and a
                // button over a draggable handle is a fight.
                if (!choosingArea) {
                    MapDrawerButton(
                        onOpen = onOpenDrawer,
                        modifier = Modifier.align(Alignment.TopStart).reportBoundsTo(chrome),
                    )
                }
            }
        }

        val actions = libraryActions(toolsVm) { pickTrackFile.launch(TRACK_FILE_TYPES) }

        TrackLibraryHost(toolsVm.container, tools, actions, showLibrary) { showLibrary = false }

        tools.selected?.let { selection ->
            SelectionSheet(
                container = toolsVm.container,
                selection = selection,
                notice = tools.notice,
                actions = actions,
                onDismiss = toolsVm::clearSelection,
            )
        }

        tools.point?.let { point ->
            PointSheet(
                container = toolsVm.container,
                point = point,
                surroundings = tools.surroundings,
                weatherLoading = tools.weatherLoading,
                onSaveWaypoint = { spot ->
                    toolsVm.saveWaypoint(spot, point.lat, point.lon, point.elevationMetres)
                },
                onDismiss = toolsVm::dismissPoint,
            )
        }

        if (showSearch) {
            SearchSheet(
                vm = toolsVm,
                state = tools,
                near = map?.cameraPosition?.target,
                onPick = { hit ->
                    map?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            org.maplibre.android.geometry.LatLng(hit.lat, hit.lng),
                            SEARCH_RESULT_ZOOM,
                        )
                    )
                    showSearch = false
                    toolsVm.clearSearch()
                },
                onDismiss = {
                    showSearch = false
                    toolsVm.clearSearch()
                },
            )
        }

        if (showMenu) {
            MapMenuSheet(
                container = toolsVm.container,
                style = style,
                hidden = hiddenGroups,
                onToggleLayer = { group, on ->
                    hiddenGroups = if (on) hiddenGroups - group.id else hiddenGroups + group.id
                },
                onOfflineMaps = {
                    showMenu = false
                    showDownloads = true
                },
                onDismiss = { showMenu = false },
            )
        }

        if (showDownloads) {
            RegionsSheet(
                vm = regionsVm,
                state = regions,
                onChooseArea = {
                    showDownloads = false
                    choosingArea = true
                },
                onShowOnMap = { row ->
                    row.bounds?.let { bounds ->
                        // Closes the sheet, because the sheet is what is in the
                        // way of seeing the answer.
                        showDownloads = false
                        map?.let { frameCamera(it, bounds.corners(), paddingPx = 64) }
                    }
                },
                onDismiss = {
                    showDownloads = false
                    regionsVm.clearEstimate()
                },
            )
        }

        // Over the map rather than replacing it: the basemap is genuinely
        // usable without the user's tracks, so losing the overlay should not
        // cost the map.
        (state.overlayNotice ?: NO_SERVER_BASEMAP_NOTICE.takeIf { state.blankBasemap })?.let { notice ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(Tokens.Radius.lg),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 3.dp,
            ) {
                Text(
                    notice,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val HEATMAP_SOURCE = "tracks_heatmap"
private const val HEATMAP_LAYER = "tracks_heatmap_layer"
private const val ROUTES_SOURCE = "tracks_routes"
private const val ROUTES_LAYER = "tracks_routes_layer"

/**
 * The user's activities as the routes they actually are.
 *
 * A density heatmap was the first attempt and it read as red blobs with hard
 * cyan edges — a year of GPS collapsed into saturated discs that showed which
 * *towns* someone had trained in and nothing about where. That is what a
 * heatmap layer does with tightly clustered points: MapLibre's `heatmap` type
 * is built for scattered observations, not for traces that already have shape.
 *
 * `/activities/tracks-geojson` sends the real LineStrings, correctly ordered as
 * `[lng, lat]` GeoJSON, so they can go straight to a source. Drawn thin and
 * semi-transparent, overlapping routes accumulate into exactly the
 * "where do I actually go" picture the heatmap was reaching for, while a single
 * ride stays legible as a line.
 */
internal fun addRoutes(style: org.maplibre.android.maps.Style, geoJson: String) {
    if (!style.isFullyLoaded) return

    // Re-fed, not skipped, when the source already exists. This used to return
    // early on the reasoning that the layer was already installed — which meant
    // the sport cross-filter redrew every card on the dashboard except the one
    // showing where the sport is actually done. The source is the data, and the
    // data changes; only the layer is set up once.
    val existing = style.getSource(ROUTES_SOURCE) as? GeoJsonSource
    if (existing != null) {
        existing.setGeoJson(geoJson)
        return
    }
    style.addSource(GeoJsonSource(ROUTES_SOURCE, geoJson))
    style.addLayer(
        LineLayer(ROUTES_LAYER, ROUTES_SOURCE).withProperties(
            PropertyFactory.lineColor("#f97316"),
            // Thin and translucent so density comes from overlap rather than
            // from a colour ramp — one pass is faint, a commute is solid.
            PropertyFactory.lineWidth(1.6f),
            PropertyFactory.lineOpacity(0.55f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

/**
 * Frame the camera on a GeoJSON FeatureCollection of LineStrings.
 *
 * [withinDays] narrows what the *camera* considers, not what is drawn. Every
 * route stays on the map; the view simply opens on the recent ones.
 *
 * This is a deliberate divergence from the web app, which frames everything.
 * On a desktop that is fine — a lifetime of training on a large window is still
 * legible, and panning is cheap. On a phone the same bounds can span continents
 * for anyone who has travelled, so the card opens on a view where every route
 * is a few pixels and the user has to pan and zoom to find the ride they did on
 * Tuesday. Framing the last month puts the map where someone is actually
 * looking, and the history is one pinch away rather than absent.
 *
 * Falls back to framing everything when nothing falls in the window — after a
 * month off, an empty camera would be a worse answer than an old one.
 */
internal fun frameToGeoJson(
    map: org.maplibre.android.maps.MapLibreMap,
    geoJson: String,
    withinDays: Long? = null,
) {
    val collection = runCatching { FeatureCollection.fromJson(geoJson) }.getOrNull() ?: return
    val features = collection.features().orEmpty()

    val framed = withinDays?.let { days ->
        // The server sends `date` as an ISO datetime, and ISO dates compare
        // lexicographically — the same trick the dashboard uses to window its
        // training load, and the reason no parsing is needed here.
        val cutoff = java.time.LocalDate.now().minusDays(days).toString()
        features.filter { (it.getStringProperty("date") ?: "") >= cutoff }
    }.orEmpty().ifEmpty { features }

    val points = framed.mapNotNull { it.geometry() as? LineString }
        .flatMap { it.coordinates() }
    if (points.isEmpty()) return
    // GeoJSON is [lng, lat]; asLatLng expects [lat, lng], so build directly.
    frameTo(map, points.map { listOf(it.latitude(), it.longitude()) })
}

/**
 * The points arrive as **`[lat, lng]`** pairs.
 *
 * This file previously said `[lng, lat]` — GeoJSON's order, and a confident
 * comment asserting the opposite of what `/activities/heatmap` actually sends.
 * The server builds `[round(r.lat, 4), round(r.lng, 4)]`; latitude is first.
 *
 * The consequence was not a subtly shifted map. Every point was plotted with
 * its coordinates transposed, and [frameTo] handed a longitude to `LatLng` as
 * a latitude — which throws for any |longitude| > 90, i.e. most of the Americas
 * and Asia. So the heatmap did not render "wrongly", it took the whole app
 * down, and that is why it appeared never to have been implemented at all.
 *
 * Hence [asLatLng] rather than indexing inline: one place converts, and it is
 * named after what the pair means rather than the order it happens to be in.
 */
internal fun addHeatmap(style: org.maplibre.android.maps.Style, points: List<List<Double>>) {
    if (!style.isFullyLoaded) return

    if (style.getSource(HEATMAP_SOURCE) != null) return

    val features = heatmapPoints(points).map { p ->
        Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat))
    }
    if (features.isEmpty()) return

    style.addSource(GeoJsonSource(HEATMAP_SOURCE, FeatureCollection.fromFeatures(features)))
    style.addLayer(
        HeatmapLayer(HEATMAP_LAYER, HEATMAP_SOURCE).withProperties(
            PropertyFactory.heatmapRadius(6f),
            PropertyFactory.heatmapOpacity(0.7f),
            // Intensity rises with zoom so a dense city does not read as one
            // saturated blob when the user zooms in to find a single street.
            PropertyFactory.heatmapIntensity(1f),
        )
    )
}

/**
 * Point the camera at the data.
 *
 * Bounds rather than a centre point: the user's history might be one town or
 * two continents, and a fixed zoom would be wrong for both. Padded so tracks
 * at the edge are not flush against the screen. [frameCamera] owns the two
 * awkward parts — a history that collapses to a single point, and a fit that
 * would close in past the deepest tile anyone has.
 */
internal fun frameTo(map: org.maplibre.android.maps.MapLibreMap, points: List<List<Double>>) {
    frameCamera(
        map,
        heatmapPoints(points).map { LatLng(it.lat, it.lng) },
        paddingPx = 64,
    )
}

/**
 * The controls that sit over the map.
 *
 * Three actions and a menu, down from six buttons. The map is the content and
 * every pixel of chrome is terrain someone cannot see, so only the things you
 * do *mid-task* — find yourself, find a place, plan a line — keep a permanent
 * button. Layers, the legend and downloads are settings about the map and live
 * together in [MapMenuSheet]; tilting into 3D is a two-finger drag, so it needs
 * no control at all.
 *
 * Icons come from `material-icons-core`, which is what the app already carries;
 * the extended set is a far larger artifact for a handful of glyphs. That
 * constraint is why the route tool is a send arrow rather than a signpost, and
 * it is also how two pins ended up next to each other in the first version.
 */
@Composable
private fun MapControls(
    follow: FollowMode,
    onToggleFollow: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = onToggleFollow) {
            Icon(
                Icons.Filled.LocationOn,
                contentDescription = when (follow) {
                    FollowMode.Off -> "Show my location"
                    FollowMode.Follow -> "Following you — tap to face your heading"
                    FollowMode.FollowWithHeading -> "Facing your heading — tap to stop following"
                },
                tint = when (follow) {
                    FollowMode.Off -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
        FilledTonalIconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search places")
        }
        // Its own button rather than a line in the options sheet. What you have
        // saved and what is on your watch is a thing you open *during* a trip —
        // to send tomorrow's route over, to check the water stop is on the
        // wrist — and two taps behind a menu labelled "map options" is where it
        // was, which is to say nowhere anybody looked.
        //
        // There is no longer a button that starts a track, and this one wears
        // the pencil it used to. Drawing begins with a long press on the map,
        // which is the gesture that already worked and carries something the
        // button never could: *where*. Arming a mode first and then aiming is
        // two steps for one intention, and the rail is not the place you were
        // looking when you decided a route starts here.
        FilledTonalIconButton(onClick = onLibrary) {
            Icon(
                Icons.Filled.Create,
                contentDescription = "Tracks and waypoints",
            )
        }
        FilledTonalIconButton(onClick = onMenu) {
            Icon(Icons.Filled.Menu, contentDescription = "Map options")
        }
    }
}

/**
 * Groups that start switched off.
 *
 * Taken from the browser's own defaults rather than "everything on": Long
 * Trails draws emblem badges across whole states and is opt-in there for the
 * same reason it should be here.
 */
private fun defaultHiddenGroups(): Set<String> =
    LAYER_GROUPS.filterNot { it.onByDefault }.map { it.id }.toSet()

/** Matches the browser's MAX_PITCH (`use3D.js`) — see the note at the call site. */
private const val MAX_PITCH = 75.0

/** The browser's zoom window (`useMapInit.js`), for the same reasons. */
private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 16.0

/** Close enough to see the place, far enough to see what is around it. */
private const val SEARCH_RESULT_ZOOM = 13.0

/**
 * The closest a "show me this" ever lands.
 *
 * Framing solves for the shape and stops there, so a small saved thing gets the
 * deepest zoom the tiles allow and no surroundings at all. Somewhere near the
 * search zoom is the answer to the question actually being asked.
 */
private const val CONTEXT_ZOOM = 14.0

/**
 * Clear of the control rail.
 *
 * MapLibre's compass is a plain view positioned in pixels, so it does not know
 * about the buttons Compose draws over the same corner — without a margin it
 * lands underneath them.
 */
private const val COMPASS_TOP_MARGIN = 40
private const val COMPASS_RIGHT_MARGIN = 230

private const val ROUTE_SOURCE = "tracks_route_draft"
private const val ROUTE_LAYER = "tracks_route_draft_line"
private const val ROUTE_POINTS_SOURCE = "tracks_route_points"
private const val ROUTE_POINTS_LAYER = "tracks_route_points_circles"

/**
 * The route being built, drawn over everything else.
 *
 * Two sources: the snapped line from the router, and the taps themselves. Both
 * are needed, and showing only the line would be worse than it sounds — before
 * the second tap there is no line at all, so a lone start point would leave the
 * user unsure whether their tap registered.
 *
 * Sources are re-fed rather than re-added. Adding a layer that already exists
 * throws, and this runs on every waypoint.
 */
internal fun drawRoute(style: Style, route: RouteDraft) {
    // A style object is only valid until a newer one starts loading, and every
    // accessor on it throws afterwards rather than returning null. These are
    // called from effects keyed on data that keeps arriving — a download's
    // progress, a route being drawn — so one landing during a style reload
    // crashed the map outright. The check costs nothing and the state it guards
    // against is normal, not exceptional.
    if (!style.isFullyLoaded) return

    val line = route.geoJson ?: EMPTY_COLLECTION
    (style.getSource(ROUTE_SOURCE) as? GeoJsonSource)?.setGeoJson(line)
        ?: run {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, line))
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#2563eb"),
                    PropertyFactory.lineWidth(4.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }

    val points = FeatureCollection.fromFeatures(
        route.waypoints.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
    )
    (style.getSource(ROUTE_POINTS_SOURCE) as? GeoJsonSource)?.setGeoJson(points)
        ?: run {
            style.addSource(GeoJsonSource(ROUTE_POINTS_SOURCE, points))
            style.addLayer(
                CircleLayer(ROUTE_POINTS_LAYER, ROUTE_POINTS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor("#ffffff"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                    PropertyFactory.circleStrokeColor("#2563eb"),
                ),
            )
        }
}

/** What an empty GeoJSON source is fed, since null is not a document. */
private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

private const val LIBRARY_TRACKS_SOURCE = "tracks_saved_tracks"
private const val LIBRARY_TRACKS_LAYER = "tracks_saved_tracks_line"
private const val LIBRARY_TRACKS_CASING = "tracks_saved_tracks_casing"
private const val LIBRARY_PLACES_SOURCE = "tracks_saved_places"
private const val LIBRARY_PLACES_LAYER = "tracks_saved_places_dot"
private const val LIBRARY_PLACES_LABEL = "tracks_saved_places_label"
private const val LIBRARY_PLACES_PIN = "tracks_saved_places_pin"

/**
 * The tracks and places you have saved, on the map they are about.
 *
 * They were only ever in a list, which made the library a filing cabinet for
 * things you could not see — you could send a route to a watch without ever
 * looking at where it went. Everything here is drawn beneath the route builder's
 * own layers, because a line being drawn now has to stay legible over the ones
 * already saved.
 *
 * Colours come from the data rather than from here. Each track and place
 * carries the colour somebody chose for it, and `get("color")` is what makes
 * one source able to draw twelve differently — the alternative is a layer per
 * track, which is a layer per track.
 *
 * The casing under each line is not decoration. A saved track is frequently
 * drawn directly over the trail it follows, in a palette picked to stand out
 * from terrain rather than from a red dashed path; without a dark edge the two
 * blend into one ambiguous line at exactly the zoom where you are trying to
 * tell them apart.
 */
internal fun drawLibrary(
    context: android.content.Context,
    style: Style,
    courseGeoJson: String?,
    waypoints: List<Waypoint>,
) {
    // A style object is only valid until a newer one starts loading, and every
    // accessor afterwards throws rather than returning null. This is keyed on
    // data that keeps arriving, so one landing mid-reload would crash the map.
    if (!style.isFullyLoaded) return

    val tracks = courseGeoJson ?: EMPTY_COLLECTION
    (style.getSource(LIBRARY_TRACKS_SOURCE) as? GeoJsonSource)?.setGeoJson(tracks)
        ?: run {
            style.addSource(GeoJsonSource(LIBRARY_TRACKS_SOURCE, tracks))
            style.addLayer(
                LineLayer(LIBRARY_TRACKS_CASING, LIBRARY_TRACKS_SOURCE).withProperties(
                    PropertyFactory.lineColor("#1f2937"),
                    PropertyFactory.lineWidth(6.5f),
                    PropertyFactory.lineOpacity(0.55f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            style.addLayer(
                LineLayer(LIBRARY_TRACKS_LAYER, LIBRARY_TRACKS_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }

    // Before the features that name them: a symbol layer pointing at an image
    // the style does not hold draws nothing at all, silently.
    WaypointPins.ensure(context, style, waypoints)
    val symbols = WaypointIcons.ensure(context, style, waypoints)

    val places = FeatureCollection.fromFeatures(
        waypoints.map { waypoint ->
            Feature.fromGeometry(Point.fromLngLat(waypoint.lng, waypoint.lat)).apply {
                addNumberProperty("id", waypoint.id)
                addStringProperty("name", waypoint.name)
                // The style's own atlas is already loaded, so naming a sprite
                // is all it takes to draw one — no bundled icon set, and the
                // symbol matches the cartography around it because it IS the
                // cartography's. "marker" resolves to no sprite on purpose:
                // that is the plain dot.
                // The symbol, already tinted to this place's colour and
                // registered under its own name. Empty when the style has no
                // such sprite, which sends the feature to the pin layer rather
                // than drawing an empty square.
                val symbol = spriteFor(waypoint.icon)
                    ?.let { WaypointIcons.idFor(it, waypoint.color) }
                    ?.takeIf { it in symbols }
                addStringProperty("sprite", symbol ?: "")
                // A place with no particular symbol gets the classic pin, in
                // its own colour. Held as a separate property because the two
                // are drawn by different layers, anchored differently: a
                // symbol sits centred on the spot, a pin stands on its point.
                addStringProperty("pin", WaypointPins.idFor(waypoint.color))
            }
        }
    )
    (style.getSource(LIBRARY_PLACES_SOURCE) as? GeoJsonSource)?.setGeoJson(places)
        ?: run {
            style.addSource(GeoJsonSource(LIBRARY_PLACES_SOURCE, places))
            style.addLayer(
                SymbolLayer(LIBRARY_PLACES_LAYER, LIBRARY_PLACES_SOURCE).withProperties(
                    // The symbol *is* the mark now, drawn in the colour its
                    // owner chose. It used to be a monochrome sprite sitting on
                    // a coloured disc, because MapLibre cannot tint an ordinary
                    // image — see [WaypointIcons] for how that stopped being a
                    // constraint. A badge behind every place was a lot of ink
                    // spent saying nothing the symbol was not already saying.
                    PropertyFactory.iconImage(Expression.get("sprite")),
                    PropertyFactory.iconSize(1f),
                    // A saved place is never dropped for clutter: it is on the
                    // map because somebody put it there.
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ).withFilter(HAS_SYMBOL)
            )
            style.addLayer(
                SymbolLayer(LIBRARY_PLACES_PIN, LIBRARY_PLACES_SOURCE).withProperties(
                    PropertyFactory.iconImage(Expression.get("pin")),
                    // Standing on its point, which is the position it marks —
                    // a pin centred on its coordinate points at the ground a
                    // pin-height north of the place it means.
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ).withFilter(Expression.not(HAS_SYMBOL))
            )
            style.addLayer(
                SymbolLayer(LIBRARY_PLACES_LABEL, LIBRARY_PLACES_SOURCE).withProperties(
                    // Text only. The symbol and the pin are drawn by their own
                    // layers, which anchor differently — a name shares neither
                    // anchoring and would drag one of them around.
                    //
                    // The font is named explicitly, and that is not cosmetic.
                    // MapLibre defaults a layer's text-font to "Open Sans
                    // Regular", the glyph endpoint only serves Noto, and a font
                    // stack that 404s renders *no text at all* — silently.
                    // Every label added from code has to say this.
                    PropertyFactory.textFont(LABEL_FONT),
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor("#1f2937"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(1.4f),
                    // The label may be dropped where the map is busy; the
                    // symbol above never is. Losing a name to clutter is fine,
                    // losing the mark is not.
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textIgnorePlacement(false),
                    PropertyFactory.textOptional(true),
                ),
            )
        }
}

/**
 * Whether a place carries one of the map's own symbols.
 *
 * The empty string is how "no symbol" travels — a GeoJSON property cannot be
 * absent for some features and present for others without the expression
 * having to handle null, and this reads the same in both layers that ask.
 */
private val HAS_SYMBOL: Expression =
    Expression.neq(Expression.get("sprite"), Expression.literal(""))

/**
 * The font the glyph server actually has.
 *
 * The style ships Noto Sans and nothing else. MapLibre's default for a layer
 * created in code is Open Sans, which the endpoint answers with a 404, and the
 * failure mode is the worst kind: the layer is added, the features are there,
 * the placement runs, and nothing appears. This was why segment distances drew
 * nothing at all — and why saved places have been quietly nameless.
 */
private val LABEL_FONT = arrayOf("Noto Sans Regular")

private const val TAP_SOURCE = "tracks_tapped_point"
private const val TAP_LAYER = "tracks_tapped_point_ring"
private const val TAP_CENTRE = "tracks_tapped_point_dot"

/**
 * A mark where you just tapped.
 *
 * Without it the sheet describes "this spot" and the map gives no clue which
 * spot that is — and on a phone the sheet covers the bottom half of the screen,
 * so the tap is frequently *behind* the thing describing it. Two rings rather
 * than one symbol: a hollow ring reads as a transient pointer rather than as
 * something saved, which is the distinction that matters next to waypoints
 * drawn as solid dots.
 *
 * Cleared by passing null, which the screen does when the sheet closes. A mark
 * that outlived its sheet would be indistinguishable from a saved place.
 */
internal fun drawTappedPoint(style: Style, point: LatLng?) {
    if (!style.isFullyLoaded) return

    val features = FeatureCollection.fromFeatures(
        point?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
            ?: emptyList()
    )

    (style.getSource(TAP_SOURCE) as? GeoJsonSource)?.setGeoJson(features)
        ?: run {
            style.addSource(GeoJsonSource(TAP_SOURCE, features))
            style.addLayer(
                CircleLayer(TAP_LAYER, TAP_SOURCE).withProperties(
                    PropertyFactory.circleRadius(13f),
                    PropertyFactory.circleColor("#2563eb"),
                    PropertyFactory.circleOpacity(0.16f),
                    PropertyFactory.circleStrokeWidth(2f),
                    PropertyFactory.circleStrokeColor("#2563eb"),
                ),
            )
            style.addLayer(
                CircleLayer(TAP_CENTRE, TAP_SOURCE).withProperties(
                    PropertyFactory.circleRadius(3f),
                    PropertyFactory.circleColor("#2563eb"),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleStrokeColor("#ffffff"),
                ),
            )
        }
}

private const val REGIONS_SOURCE = "tracks_offline_regions"
private const val REGIONS_LINE_LAYER = "tracks_offline_regions_outline"

/**
 * Blue: stored on this phone, and good with the radio off.
 *
 * The colour the browser uses for a downloaded area, kept deliberately, because
 * this is the one that means what "downloaded" has always meant here.
 */
private const val PHONE_BORDER = "#2563eb"

/**
 * Orange: the server has this ground at full detail, the phone does not.
 *
 * Worth a colour of its own rather than a shade of the first. Inside an orange
 * box the map is as detailed as inside a blue one — every trail, contour and
 * building — right up until the signal goes, and then it is the coarse
 * overview. That is a genuinely different thing to know before walking into a
 * canyon, and it is not knowable from a list of names.
 */
private const val SERVER_BORDER = "#f97316"

/** Neither yet: still being cut on the server, or still being fetched here. */
private const val BUILDING_BORDER = "#94a3b8"

/**
 * The areas you can rely on, outlined on the map.
 *
 * Without this the download list is the only place a saved area exists, so the
 * question the map is actually asked — *am I covered where I am going* — has to
 * be answered by reading names in a sheet and imagining where they are. A
 * boundary you can see answers it at a glance, and shows the gaps between
 * regions that a list cannot.
 *
 * The border only, with no fill. The browser tints the interior as well, which
 * works on a large screen showing one or two regions and does not here: a wash
 * over the whole area competes with the terrain underneath, which is the thing
 * the map is for. The edge is the only part that carries information — inside it
 * you have the map, outside you do not.
 *
 * Dashed, because these are not features of the world. Nothing is there on the
 * ground, and a solid line at this weight reads like a boundary that is.
 */
internal fun drawRegions(style: Style, rows: List<RegionRow>) {
    // A style object is only valid until a newer one starts loading, and every
    // accessor on it throws afterwards rather than returning null. These are
    // called from effects keyed on data that keeps arriving — a download's
    // progress, a route being drawn — so one landing during a style reload
    // crashed the map outright. The check costs nothing and the state it guards
    // against is normal, not exceptional.
    if (!style.isFullyLoaded) return

    val features = rows.mapNotNull { row ->
        val bounds = row.bounds ?: return@mapNotNull null
        // The merged shape where there is one, the box otherwise. Two
        // overlapping downloads fold into one area whose box is the envelope of
        // both, and an L-shaped pair leaves a corner inside that envelope which
        // no tile covers — outlining it would claim coverage that is not there.
        (row.shape?.let(::outlineOf) ?: rectangle(bounds)).apply {
            // What the line is allowed to claim. An area still being built is
            // drawn faintly, because an outline promising coverage that is not
            // there yet is the one lie this feature cannot afford.
            addStringProperty("coverage", row.coverage.name.lowercase())
        }
    }
    val collection = FeatureCollection.fromFeatures(features)

    (style.getSource(REGIONS_SOURCE) as? GeoJsonSource)?.setGeoJson(collection)
        ?: run {
            style.addSource(GeoJsonSource(REGIONS_SOURCE, collection))
            style.addLayer(
                LineLayer(REGIONS_LINE_LAYER, REGIONS_SOURCE).withProperties(
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("coverage"),
                            Expression.literal(BUILDING_BORDER),
                            Expression.stop("phone", Expression.literal(PHONE_BORDER)),
                            Expression.stop("server", Expression.literal(SERVER_BORDER)),
                        )
                    ),
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineDasharray(arrayOf(4f, 4f)),
                    PropertyFactory.lineOpacity(
                        Expression.match(
                            Expression.get("coverage"),
                            Expression.literal(0.45f),
                            Expression.stop("phone", Expression.literal(1f)),
                            Expression.stop("server", Expression.literal(0.9f)),
                        )
                    ),
                ),
            )
        }
}

/** The plain box, as a feature. */
private fun rectangle(bounds: org.maplibre.android.geometry.LatLngBounds): Feature = Feature.fromGeometry(
    Polygon.fromLngLats(
        listOf(
            listOf(
                Point.fromLngLat(bounds.longitudeWest, bounds.latitudeSouth),
                Point.fromLngLat(bounds.longitudeEast, bounds.latitudeSouth),
                Point.fromLngLat(bounds.longitudeEast, bounds.latitudeNorth),
                Point.fromLngLat(bounds.longitudeWest, bounds.latitudeNorth),
                Point.fromLngLat(bounds.longitudeWest, bounds.latitudeSouth),
            )
        )
    )
)

/**
 * A server-sent geometry as a feature, or null if it cannot be read.
 *
 * Wrapped into a Feature document rather than dispatched on its `type`, because
 * the server sends a Polygon or a MultiPolygon depending on how a merge went
 * and the parser already knows how to tell them apart. Null on anything
 * malformed, so the caller falls back to the box: an area drawn as a rectangle
 * is a small overstatement, and an area not drawn at all is invisible coverage.
 */
private fun outlineOf(geometry: String): Feature? = runCatching {
    Feature.fromJson("""{"type":"Feature","properties":{},"geometry":$geometry}""")
}.getOrNull()

/**
 * The actions both sheets offer.
 *
 * Built once and handed to each, so the list and the tapped-item sheet cannot
 * drift into offering different things for the same track.
 */
@Composable
private fun libraryActions(
    toolsVm: MapToolsViewModel,
    onImport: () -> Unit = {},
) = LibraryActions(
    onCourseSendNow = toolsVm::sendCourseToWatch,
    onCourseRemove = toolsVm::removeCourseFromWatch,
    onCourseColor = toolsVm::setCourseColor,
    onCourseShow = toolsVm::showOnMap,
    onCourseRename = toolsVm::renameCourse,
    onCourseDelete = toolsVm::deleteCourse,
    onWaypointSendNow = toolsVm::sendWaypointToWatch,
    onWaypointRemove = toolsVm::removeWaypointFromWatch,
    onWaypointStyle = toolsVm::setWaypointStyle,
    onWaypointShow = toolsVm::showOnMap,
    onWaypointRename = toolsVm::renameWaypoint,
    onWaypointDelete = toolsVm::deleteWaypoint,
    onImport = onImport,
    onReadWatch = toolsVm::readFromWatch,
    onDismissNotice = toolsVm::dismissNotice,
)

/** Keeps the library sheet's wiring out of the screen's own body. */
@Composable
private fun TrackLibraryHost(
    container: com.tracks.app.AppContainer,
    tools: MapToolsUiState,
    actions: LibraryActions,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    TrackLibrarySheet(
        container = container,
        courses = tools.courses,
        waypoints = tools.waypoints,
        loading = tools.libraryLoading,
        notice = tools.notice,
        actions = actions,
        onDismiss = onDismiss,
    )
}

/**
 * Which saved thing, if any, is under a tap.
 *
 * Places beat tracks when both are hit: a waypoint is a point somebody put
 * there deliberately and is the smaller target of the two, so a tap that
 * catches both almost always meant the dot.
 */
private fun MapLibreMap.savedItemAt(point: LatLng): SavedHit {
    val screen = projection.toScreenLocation(point)
    val slop = TAP_SLOP_PX
    val box = android.graphics.RectF(
        screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop,
    )

    fun query(vararg ids: String): List<Feature> = runCatching {
        queryRenderedFeatures(box, *ids)
    }.getOrDefault(emptyList())

    val place = query(LIBRARY_PLACES_LAYER, LIBRARY_PLACES_LABEL, LIBRARY_PLACES_PIN)
        .firstNotNullOfOrNull { it.getNumberProperty("id")?.toInt() }
    val track = query(LIBRARY_TRACKS_LAYER, LIBRARY_TRACKS_CASING)
        .firstNotNullOfOrNull { it.getNumberProperty("id")?.toInt() }

    return SavedHit(courseId = track, waypointId = place)
}

private data class SavedHit(val courseId: Int?, val waypointId: Int?)

/**
 * Keep [into] holding this composable's position on screen.
 *
 * A list rather than one rectangle because the chrome is in pieces — a rail on
 * the right, a drawer button on the left — and each moves independently.
 * Registered on every layout pass and de-registered on disposal, so a control
 * that goes away (the drawer button hides while framing a download) stops
 * blocking taps where it used to be.
 */
private fun Modifier.reportBoundsTo(into: SnapshotStateList<android.graphics.RectF>): Modifier =
    composed {
        var mine by remember { mutableStateOf<android.graphics.RectF?>(null) }
        DisposableEffect(Unit) {
            onDispose { mine?.let(into::remove) }
        }
        onGloballyPositioned { coordinates ->
            val origin = coordinates.positionInRoot()
            val bounds = android.graphics.RectF(
                origin.x, origin.y,
                origin.x + coordinates.size.width, origin.y + coordinates.size.height,
            )
            if (bounds != mine) {
                mine?.let(into::remove)
                into.add(bounds)
                mine = bounds
            }
        }
    }

/** Whether a map click at this position landed under a piece of chrome. */
private fun List<android.graphics.RectF>.covers(map: MapLibreMap, point: LatLng): Boolean {
    if (isEmpty()) return false
    val screen = map.projection.toScreenLocation(point)
    return any { it.contains(screen.x, screen.y) }
}

/** Room around a framed track, so its ends are not against the screen edge. */
private const val FOCUS_PADDING_PX = 90

/**
 * What the file picker will offer.
 *
 * Wide on purpose. GPX has no registered MIME type that providers agree on —
 * Drive calls it `application/octet-stream`, a file manager may call it
 * `application/gpx+xml` or nothing at all — so a precise filter greys out the
 * very file somebody is trying to pick. The server checks the extension and
 * rejects anything it cannot parse, which is the honest place for that test.
 */
private val TRACK_FILE_TYPES = arrayOf("*/*")

/**
 * The name a content URI is presenting itself under.
 *
 * Needed rather than nice: the server picks its parser from the extension, so
 * an import whose name is lost arrives as an unparseable blob. Falls back to
 * the last path segment, which is right for a plain file URI and harmless
 * otherwise.
 */
private fun android.net.Uri.fileName(context: android.content.Context): String? = runCatching {
    context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
}.getOrNull() ?: lastPathSegment?.substringAfterLast('/')

/**
 * The land and trails under a tap, read from what is drawn.
 *
 * A few pixels of slop around the point, because a trail is a one-pixel line
 * and nobody hits one exactly. Layer ids come from [LAYER_GROUPS] so this
 * cannot drift from what the map is actually showing, and a missing layer is
 * skipped rather than an error — the served style is trimmed to the archives
 * that exist, so the trail layers genuinely are not there until a region is
 * downloaded.
 */
private fun MapLibreMap.surroundingsAt(point: LatLng): PointSurroundings {
    val screen = projection.toScreenLocation(point)
    val slop = TAP_SLOP_PX
    val box = android.graphics.RectF(
        screen.x - slop, screen.y - slop, screen.x + slop, screen.y + slop,
    )

    fun query(ids: List<String>): List<Feature> = runCatching {
        queryRenderedFeatures(box, *ids.toTypedArray())
    }.getOrDefault(emptyList())

    val land = query(
        LAYER_GROUPS.firstOrNull { it.id == "publiclands" }?.layers.orEmpty()
    ).firstOrNull()

    val trailLayers = LAYER_GROUPS
        .filter { it.id == "trails" || it.id == "long_trails" }
        .flatMap { it.layers }
    val trails = query(trailLayers)
        .mapNotNull { feature ->
            feature.getStringProperty("name")?.takeIf { it.isNotBlank() }
        }
        .distinct()
        .take(MAX_NEARBY_TRAILS)

    return PointSurroundings(
        landName = land?.getStringProperty("name")?.takeIf { it.isNotBlank() },
        landType = land?.let { f ->
            // The style carries several spellings of the same idea depending on
            // which archive the polygon came from; first one wins.
            listOf("designation", "kind", "class", "type")
                .firstNotNullOfOrNull { f.getStringProperty(it)?.takeIf { v -> v.isNotBlank() } }
        },
        landOwnership = land?.getStringProperty("ownership")?.takeIf { it.isNotBlank() },
        trails = trails,
    )
}

/** A trail is a hairline; a fingertip is not. */
private const val TAP_SLOP_PX = 14f

/** Enough to say what is around without turning the sheet into a list. */
private const val MAX_NEARBY_TRAILS = 6

/**
 * A bounds as the four points that define it.
 *
 * [frameCamera] takes points rather than a bounds so it can nudge a degenerate
 * one open; handing it the corners lets a saved area reuse the same framing —
 * including the zoom cap, which matters here because a small region would
 * otherwise be framed closer than any tile exists for.
 */
private fun org.maplibre.android.geometry.LatLngBounds.corners(): List<LatLng> = listOf(
    LatLng(latitudeNorth, longitudeEast),
    LatLng(latitudeSouth, longitudeWest),
)

private const val ROUTE_SEGMENTS_SOURCE = "tracks_route_segments"
private const val ROUTE_SEGMENTS_LAYER = "tracks_route_segments_label"

/**
 * How long each piece of the line being drawn is, written on the piece.
 *
 * The bar can only say what the whole route comes to, which is the answer to a
 * question asked once at the end. While the line is being built the useful
 * number is per-leg — this bit down to the lake is 2.4 km, that bit over the
 * saddle is 11 — and it is useful *in place*, next to the leg it describes,
 * because that is where the decision to keep or move the point is being made.
 *
 * Placed halfway along the drawn geometry rather than between the two taps, so
 * a leg that follows a switchbacked trail carries its label on the trail.
 */
internal fun drawSegmentLabels(style: Style, segments: List<RouteSegment>) {
    if (!style.isFullyLoaded) return

    val features = FeatureCollection.fromFeatures(
        segments.map { segment ->
            Feature.fromGeometry(Point.fromLngLat(segment.lng, segment.lat)).apply {
                addStringProperty("label", distance(segment.metres))
            }
        }
    )

    (style.getSource(ROUTE_SEGMENTS_SOURCE) as? GeoJsonSource)?.setGeoJson(features)
        ?: run {
            style.addSource(GeoJsonSource(ROUTE_SEGMENTS_SOURCE, features))
            style.addLayer(
                SymbolLayer(ROUTE_SEGMENTS_LAYER, ROUTE_SEGMENTS_SOURCE).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    // See [LABEL_FONT]: without this the distances are computed,
                    // placed, and drawn in a font that does not exist.
                    PropertyFactory.textFont(LABEL_FONT),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor("#1d4ed8"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(1.6f),
                    // Off the line rather than on it: a label centred on the
                    // route hides the very geometry it is measuring.
                    PropertyFactory.textOffset(arrayOf(0f, -0.9f)),
                    // Every leg is labelled, always. These are transient — they
                    // exist only while a line is being drawn — and a label the
                    // placement engine dropped for clutter would read as a leg
                    // with no length rather than as a crowded map.
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
        }
}

/**
 * The handles of the line being drawn, as things you can grab.
 *
 * One small touch target per handle, positioned over the map — *not* one sheet
 * across the whole screen. That was the first attempt and it broke placing
 * points entirely: a full-screen `pointerInput` wins the hit test at every
 * position, so the MapView beneath it stopped receiving taps and the second
 * point of every route went nowhere. Declining to consume is not enough; being
 * hit at all is what takes the map out of the path.
 *
 * The positions are recomputed as the camera moves, because they are projected
 * screen coordinates of world positions and the world slides under them on
 * every pan. Cheap: a handful of points, and only while a line is being drawn.
 *
 * While a handle is held, the map's own scrolling is switched off. An embedded
 * view gets its own touch dispatch regardless of what Compose does with the
 * gesture, so without this the map pans out from under the point being moved.
 */
@Composable
private fun RouteHandles(
    map: MapLibreMap?,
    waypoints: List<LatLng>,
    grabbed: (Boolean) -> Unit,
    onMove: (Int, LatLng, Boolean) -> Unit,
    onTap: (Int, IntOffset) -> Unit,
) {
    val ready = map ?: return
    if (waypoints.isEmpty()) return

    // Bumped on every camera change so the projections below are recomputed.
    // Both listeners: a fling reports moves, a programmatic fly-to may only
    // report idle, and a handle left behind by either is a target that is not
    // where it is drawn.
    var camera by remember { mutableStateOf(0) }
    DisposableEffect(ready) {
        val moving = MapLibreMap.OnCameraMoveListener { camera++ }
        val idle = MapLibreMap.OnCameraIdleListener { camera++ }
        ready.addOnCameraMoveListener(moving)
        ready.addOnCameraIdleListener(idle)
        onDispose {
            ready.removeOnCameraMoveListener(moving)
            ready.removeOnCameraIdleListener(idle)
        }
    }

    val positions = remember(waypoints, camera) {
        waypoints.map { ready.projection.toScreenLocation(it) }
    }
    val live = rememberUpdatedState(positions)
    val move = rememberUpdatedState(onMove)
    val tap = rememberUpdatedState(onTap)
    val half = with(LocalDensity.current) { HANDLE_TOUCH.toPx() / 2f }
    val moveSlop = with(LocalDensity.current) { HANDLE_DRAG_SLOP.toPx() }

    positions.forEachIndexed { index, screen ->
        Box(
            Modifier
                .offset { IntOffset((screen.x - half).toInt(), (screen.y - half).toInt()) }
                .size(HANDLE_TOUCH)
                .pointerInput(index, ready) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        grabbed(true)
                        ready.uiSettings.isScrollGesturesEnabled = false

                        // The box is centred on the handle and moves with it, so
                        // a position local to the box plus the box's own origin
                        // is the finger — and stays the finger as the point is
                        // dragged out from under it.
                        fun rootOf(local: androidx.compose.ui.geometry.Offset) =
                            live.value.getOrNull(index)?.let {
                                androidx.compose.ui.geometry.Offset(
                                    it.x - half + local.x,
                                    it.y - half + local.y,
                                )
                            }

                        var moved = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    change.consume()
                                    // A press that never travelled is a tap, and
                                    // asks about the point rather than moving it.
                                    if (moved) {
                                        rootOf(change.position)?.let {
                                            move.value(index, ready.latLngAt(it), true)
                                        }
                                    } else {
                                        tap.value(
                                            index,
                                            IntOffset(screen.x.toInt(), screen.y.toInt()),
                                        )
                                    }
                                    break
                                }
                                if (!moved &&
                                    (change.position - down.position).getDistance() > moveSlop
                                ) {
                                    moved = true
                                }
                                if (moved) {
                                    rootOf(change.position)?.let {
                                        move.value(index, ready.latLngAt(it), false)
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            ready.uiSettings.isScrollGesturesEnabled = true
                            grabbed(false)
                        }
                    }
                }
        )
    }
}

/** Big enough for a fingertip, small enough to leave the map tappable around it. */
private val HANDLE_TOUCH = 44.dp

/** The handle within [slop] pixels of a screen position, nearest first. */
private fun MapLibreMap.handleAt(
    position: androidx.compose.ui.geometry.Offset,
    handles: List<LatLng>,
    slop: Float,
): Int? {
    var best: Int? = null
    var bestDistance = slop
    handles.forEachIndexed { index, handle ->
        val screen = projection.toScreenLocation(handle)
        val distance = kotlin.math.hypot(screen.x - position.x, screen.y - position.y)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

/** Whether a map click landed on one of the line's own handles. */
private fun MapLibreMap.handleNear(point: LatLng, handles: List<LatLng>): Int? {
    if (handles.isEmpty()) return null
    val screen = projection.toScreenLocation(point)
    return handleAt(
        androidx.compose.ui.geometry.Offset(screen.x, screen.y),
        handles,
        HANDLE_GRAB_PX,
    )
}

private fun MapLibreMap.latLngAt(position: androidx.compose.ui.geometry.Offset): LatLng =
    projection.fromScreenLocation(android.graphics.PointF(position.x, position.y))

private fun androidx.compose.ui.geometry.Offset.asIntOffset(): IntOffset =
    IntOffset(x.toInt(), y.toInt())

/**
 * How close a finger has to be to a handle to have grabbed it.
 *
 * Larger than the dot it is grabbing, because the dot is drawn for the eye and
 * this is sized for a fingertip.
 */
private val HANDLE_GRAB = 26.dp

/** The same distance in raw pixels, for the map's own click arbitration. */
private const val HANDLE_GRAB_PX = 70f

/** Clear of the handle, so the menu points at it rather than hiding it. */
private const val HANDLE_MENU_GAP_PX = 18

/** Far enough that a shaky tap is still a tap and not a two-metre move. */
private val HANDLE_DRAG_SLOP = 7.dp
