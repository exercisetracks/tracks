// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tracks.app.AppContainer
import com.tracks.app.MainViewModel
import com.tracks.app.ui.activity.ActivityDetailScreen
import com.tracks.app.ui.activity.ActivityDetailViewModel
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.PullToSync
import com.tracks.app.ui.components.SyncProgressPopup
import com.tracks.app.ui.dashboard.DashboardPeriodActions
import com.tracks.app.ui.dashboard.DashboardScreen
import com.tracks.app.ui.dashboard.DashboardViewModel
import com.tracks.app.ui.flexibility.FlexibilityScreen
import com.tracks.app.ui.flexibility.FlexibilityViewModel
import com.tracks.app.ui.health.HealthRangeActions
import com.tracks.app.ui.health.HealthScreen
import com.tracks.app.ui.health.HealthViewModel
import com.tracks.app.ui.map.MapScreen
import com.tracks.app.ui.map.MapToolsViewModel
import com.tracks.app.ui.map.MapViewModel
import com.tracks.app.ui.map.RegionsViewModel
import com.tracks.app.ui.music.MusicSettings
import com.tracks.app.ui.plan.PlanViewModel
import com.tracks.app.ui.plan.TrainingScreen
import com.tracks.app.ui.race.RacePlansScreen
import com.tracks.app.ui.race.RacePlansViewModel
import com.tracks.app.ui.screens.ActivitiesScreen
import com.tracks.app.ui.screens.ActivitiesViewModel
import com.tracks.app.ui.screens.ActivityListActions
import com.tracks.app.ui.screens.FeedStatus
import com.tracks.app.ui.screens.SettingsScreen
import com.tracks.app.ui.strength.StrengthScreen
import com.tracks.app.ui.strength.StrengthViewModel
import com.tracks.app.ui.theme.Tokens
import com.tracks.app.ui.tour.tourAnchor
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import com.tracks.app.ui.workout.ActiveWorkout
import com.tracks.app.ui.workout.ActiveWorkoutBar
import com.tracks.app.ui.workout.GuidedWorkoutScreen
import com.tracks.app.ui.workout.GuidedWorkoutViewModel
import kotlinx.coroutines.launch

/**
 * The shell: a bottom bar, a nav graph, and one place messages surface.
 *
 * Messages are a snackbar rather than a card in the layout, which is a change
 * from the first screen and a deliberate one — the old inline card pushed the
 * content down every time a sync reported, so the list moved under the user's
 * thumb mid-scroll. A snackbar says the same thing without reflowing anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksNavHost(
    vm: MainViewModel,
    container: AppContainer,
    feeds: List<FeedStatus>,
    onPair: () -> Unit,
    onSync: () -> Unit,
) {
    val navController = rememberNavController()
    val state by vm.state.collectAsStateWithLifecycle()
    // Read here rather than inside Settings so the same answer can shape
    // anything else that is watch-only later, from one place.
    val hasDevice by container.hasDevice.collectAsStateWithLifecycle()
    val themeMode by container.themeMode.collectAsStateWithLifecycle()
    val syncProgress by container.syncProgress.collectAsStateWithLifecycle()
    val backupProgress by container.backupProgress.collectAsStateWithLifecycle()
    val accent by container.accent.collectAsStateWithLifecycle()
    val imperial by container.imperial.collectAsStateWithLifecycle()
    val bodyFemale by container.bodyFemale.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val pendingRoute by container.pendingRoute.collectAsStateWithLifecycle()

    // After a workout that went to the watch: did its exercises animate? Asked
    // once, app-wide, when the app opens — see AnimationRecaps.
    if (hasDevice) com.tracks.app.ui.recap.AnimationRecapHost(
        sources = container.sources,
        today = { java.time.LocalDate.now().toString() },
        nowIso = { java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString() },
    )
    LaunchedEffect(pendingRoute) {
        when (val route = pendingRoute) {
            com.tracks.app.MainActivity.OPEN_BACKUP -> {
                container.pendingRoute.value = null
                navController.navigate(Destination.Settings.route) { launchSingleTop = true }
            }
            com.tracks.app.MainActivity.OPEN_TODAY -> {
                container.pendingRoute.value = null
                navController.navigate(Destination.Dashboard.route) { launchSingleTop = true }
            }
            // A page asked for from inside the app (race plan → map to draw a
            // course, and back).
            Destination.Map.route, Destination.RacePlans.route -> {
                container.pendingRoute.value = null
                navController.navigate(route) { launchSingleTop = true }
            }
            else -> Unit
        }
    }

    // One message at a time, dismissed when the next arrives. `state.message`
    // is the single channel every operation reports through, so this is the
    // only place the app has to think about surfacing them.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbars.showSnackbar(message)
        vm.dismissMessage()
    }

    AccountConflictDialog(vm)

    // Built here rather than inside the Activities destination, because the app
    // bar needs it too: the list's sort and type filter live in the bar beside
    // the page title, and the bar is the shell's. Held by the Activity rather
    // than by a nav entry, which is if anything stronger than before — the
    // route thumbnails are one request for the whole list, and this is what
    // stops it being re-fired every time the user glances at another page.
    // Held here for the same two reasons as the list's: the app bar carries this
    // page's controls, and nine requests is a lot to re-fire every time the user
    // glances at another tab.
    val dashboardVm: DashboardViewModel = viewModel(
        key = "dashboard",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DashboardViewModel(container) as T
        },
    )

    val activitiesVm: ActivitiesViewModel = viewModel(
        key = "activities",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ActivitiesViewModel(container) as T
        },
    )

    // Hoisted for the third time and for the same reason as the two above: the
    // app bar carries this page's range pills, and the bar belongs to the shell.
    val healthVm: HealthViewModel = viewModel(
        key = "health",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HealthViewModel(container) as T
        },
    )

    // The Activity's own store, captured here because inside the NavHost the
    // ambient owner is the navigation entry. A workout session's ViewModel lives
    // here — see [ActiveWorkout] for why it must outlive the screen.
    val activityOwner = checkNotNull(androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current)
    fun guidedViewModel(workoutId: Int, key: String): GuidedWorkoutViewModel =
        ViewModelProvider(
            activityOwner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GuidedWorkoutViewModel(container, workoutId, key) as T
            },
        )[key, GuidedWorkoutViewModel::class.java]
    val activeWorkout by ActiveWorkout.current.collectAsStateWithLifecycle()

    // The tutorial: one per app, like the web's TourProvider in its Layout.
    val tourVm: com.tracks.app.ui.tour.TourViewModel = viewModel(
        key = "tour",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                com.tracks.app.ui.tour.TourViewModel(container) as T
        },
    )
    val tour by tourVm.state.collectAsStateWithLifecycle()
    val tourAnchors = remember { com.tracks.app.ui.tour.TourAnchors() }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val here = Destination.forRoute(route)

    // A page's tour, the first time it is opened — the web's useTourAutoStart.
    // Leaving the page mid-tour drops it without marking it seen, so it shows
    // again next time; the half-second is for the page to compose the things
    // the tips point at.
    val tourHere = com.tracks.app.ui.tour.Tours.idForRoute(route)
    LaunchedEffect(tourHere, tour.hydrated, tour.enabled, tour.seen, tour.active) {
        val active = tour.active
        if (active != null) {
            if (active != tourHere) tourVm.abort()
            return@LaunchedEffect
        }
        if (!tour.shouldStart(tourHere)) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        tourHere?.let(tourVm::start)
    }

    fun navigateTo(destination: Destination) {
        navController.navigate(destination.route) {
            // One entry per page on the back stack, state kept when
            // switching away and back, so a scrolled activity list
            // stays where it was left. Same behaviour the bottom bar
            // had; the bar is gone, the expectation is not.
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Every watch-only control below reads this rather than a parameter —
    // see LocalHasDevice for why, and for why it only hides.
    androidx.compose.runtime.CompositionLocalProvider(
        com.tracks.app.ui.components.LocalHasDevice provides hasDevice,
        com.tracks.app.ui.tour.LocalTourAnchors provides tourAnchors,
        com.tracks.app.ui.body.LocalBodyGender provides
            if (bodyFemale) com.tracks.app.ui.body.BodyGender.Female else com.tracks.app.ui.body.BodyGender.Male,
    ) {
    androidx.compose.foundation.layout.Box {
    ModalNavigationDrawer(
        drawerState = drawer,
        // Swipe from the left edge, anywhere in the app but the map.
        //
        // Detail screens were excluded on the theory that the drawer would
        // fight the system back gesture. In practice it does not, and the
        // exclusion had a cost that showed up immediately: reaching any other
        // page from an activity meant backing out to the list first, so the one
        // screen people arrive at by tapping was the one they could not leave
        // by swiping.
        //
        // The map keeps its exception, because there the left edge is a place
        // you pan from. Losing the leftmost strip of a map to a menu is a real
        // cost on the screen this app exists for, and the map already carries
        // the button. Closing an open drawer by swiping still works everywhere.
        gesturesEnabled = here != Destination.Map || drawer.isOpen,
        drawerContent = {
            TracksDrawer(
                current = here,
                hasDevice = hasDevice,
                onNavigate = { destination ->
                    scope.launch { drawer.close() }
                    navigateTo(destination)
                },
            )
        },
    ) {
        com.tracks.app.ui.components.SlideOverHost {
        Scaffold(
            snackbarHost = {
                // The sync pill sits where snackbars do: bottom centre, above
                // content, out of the way of both bars.
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    SyncProgressPopup(syncProgress)
                    com.tracks.app.ui.components.ProgressPill(
                        label = backupProgress?.let { com.tracks.app.backup.backupLabel(it) },
                        fraction = backupProgress?.fraction,
                    )
                    SnackbarHost(snackbars)
                }
            },
            topBar = {
                // The map has no bar. It is the one screen whose content goes
                // all the way to the edges by design — a persistent 64dp strip
                // over a map you are navigating by is the most expensive chrome
                // in the app — so it carries the same button as a floating
                // control instead, in the same top-left corner. Every other
                // screen gets the bar, and the corner means the same thing on
                // all of them.
                //
                // A detail screen gets none either, for a different reason: it
                // brings its own bar with a back arrow and the activity's name.
                // Rendering the shell's as well stacked two bars — the top one
                // empty, because a detail route is not one of the nine pages
                // and has no label — which is the "massive header" it looked
                // like.
                Column {
                if (here != null && here != Destination.Map) {
                    TopAppBar(
                        title = { Text(here.label) },
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawer.open() } },
                                modifier = Modifier.tourAnchor("nav"),
                            ) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                            }
                        },
                        // A page's own controls go in the bar beside its name
                        // rather than in a strip under it. The strip cost a row
                        // of the list on a screen that is a list, and it
                        // scrolled away with the content — so the way to change
                        // the sort was to scroll back to the top first.
                        actions = {
                            when (here) {
                                Destination.Activities -> ActivityListActions(
                                    vm = activitiesVm,
                                    activities = state.activities,
                                )
                                Destination.Dashboard -> DashboardPeriodActions(
                                    dashboardVm,
                                    Modifier.tourAnchor("dashboard-period"),
                                )
                                Destination.Health -> HealthRangeActions(
                                    healthVm,
                                    Modifier.tourAnchor("health-range"),
                                )
                                else -> Unit
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
                // The way back to a workout in progress, under the page's bar
                // on every page but the map (no chrome there — its own button
                // and the run's notification stand in) and the workout itself.
                val session = activeWorkout
                val onWorkout = route == "workout/{workoutId}" &&
                    backStack?.arguments?.getInt("workoutId") == session?.workoutId
                if (session != null && here != Destination.Map && !onWorkout) {
                    val guided by guidedViewModel(session.workoutId, session.key).state.collectAsStateWithLifecycle()
                    ActiveWorkoutBar(
                        session = session,
                        state = guided,
                        onOpen = { navController.navigate("workout/${session.workoutId}") { launchSingleTop = true } },
                        // A detail page brings its own bar and the shell
                        // draws none, so here this is the topmost thing and
                        // has to keep clear of the status bar itself.
                        modifier = if (here == null) {
                            Modifier.windowInsetsPadding(WindowInsets.statusBars)
                        } else {
                            Modifier
                        },
                    )
                }
                }
            },
        ) { padding ->
            NavHost(
            navController = navController,
            startDestination = Destination.start.route,
            // Padded away from the system bars, *and* told they have been dealt
            // with. The consume is what stops a screen that brings its own
            // Scaffold — the activity detail, a guided workout — from applying
            // the status-bar inset a second time inside content this padding
            // has already pushed down. That double inset is why the detail
            // screen's header stood a status bar taller than every other page's.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Destination.Dashboard.route) {
                // Drag down to sync the watch. Both of the pages that offer
                // it are about what your body did, and the watch is where
                // that comes from — see [PullToSync].
                PullToSync(onSync = {
                    // With no watch the gesture only refreshes: a watch sync
                    // would try to reach a device that does not exist.
                    if (hasDevice) vm.syncWatchFromGesture()
                    // And the server-backed half — readiness, VO₂max, the
                    // coaching line. The mirror-derived figures redraw
                    // themselves when the sync writes; these do not.
                    dashboardVm.refresh()
                }) {
                    DashboardScreen(
                        vm = dashboardVm,
                        onOpenWorkout = { id -> navController.navigate("workout/$id") },
                        banner = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                com.tracks.app.ui.profile.BackupReminder(container, state.session) {
                                    navController.navigate(Destination.Settings.route)
                                }
                                com.tracks.app.donate.DonateBanner()
                            }
                        },
                    )
                }
            }
            composable(Destination.Activities.route) {
                ActivitiesScreen(
                    state = state,
                    vm = activitiesVm,
                    onActivityClick = { id -> navController.navigate("activity/$id") },
                )
            }
            composable(
                route = "activity/{activityId}",
                arguments = listOf(navArgument("activityId") { type = NavType.IntType }),
            ) { entry ->
                val activityId = entry.arguments?.getInt("activityId") ?: return@composable
                // Keyed on the id so navigating from one activity to another
                // builds a fresh ViewModel rather than showing the previous
                // activity's numbers under the new one's name.
                val detailVm: ActivityDetailViewModel = viewModel(
                    key = "activity-$activityId",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            ActivityDetailViewModel(container, activityId) as T
                    },
                )
                ActivityDetailScreen(vm = detailVm, onBack = { navController.popBackStack() })
            }
            composable(
                route = "workout/{workoutId}",
                arguments = listOf(navArgument("workoutId") { type = NavType.IntType }),
            ) { entry ->
                val workoutId = entry.arguments?.getInt("workoutId") ?: return@composable
                // Held by the Activity rather than this entry, under a key
                // [ActiveWorkout] hands out: the session in progress when this
                // is the workout in progress, a fresh one otherwise — so going
                // back mid-run keeps the step cursor, and a finished session
                // is never shown as the start of the next one. Saveable, so the
                // entry keeps its key across leaving and returning.
                val key = androidx.compose.runtime.saveable.rememberSaveable { ActiveWorkout.keyFor(workoutId) }
                val guidedVm = remember(key) { guidedViewModel(workoutId, key) }
                GuidedWorkoutScreen(
                    vm = guidedVm,
                    onBack = { navController.popBackStack() },
                    onOpenWorkout = { id ->
                        navController.navigate("workout/$id") {
                            popUpTo("workout/{workoutId}") { inclusive = true }
                        }
                    },
                )
            }
            composable(Destination.Map.route) {
                // Held by the nav entry, so panning away to another tab and
                // back does not refetch a 200 KB style and the whole heatmap.
                val mapVm: MapViewModel = viewModel(
                    key = "map",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            MapViewModel(container) as T
                    },
                )
                // Separate from [mapVm] but held the same way: a download runs
                // for minutes and must survive leaving the tab to look at
                // something else.
                val regionsVm: RegionsViewModel = viewModel(
                    key = "regions",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            RegionsViewModel(
                                container,
                                container.offlineTiles(),
                                onCoverageChanged = { mapVm.load() },
                            ) as T
                    },
                )
                val toolsVm: MapToolsViewModel = viewModel(
                    key = "map-tools",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            MapToolsViewModel(container) as T
                    },
                )
                MapScreen(
                    vm = mapVm,
                    regionsVm = regionsVm,
                    toolsVm = toolsVm,
                    onOpenDrawer = { scope.launch { drawer.open() } },
                )
            }
            composable(Destination.Health.route) {
                PullToSync(onSync = {
                    if (hasDevice) vm.syncWatchFromGesture()
                    healthVm.load()
                }) {
                    HealthScreen(vm = healthVm)
                }
            }
            composable(Destination.Calendar.route) {
                val planVm: PlanViewModel = viewModel(
                    key = "calendar",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            PlanViewModel(container) as T
                    },
                )
                TrainingScreen(
                    vm = planVm,
                    onOpenWorkout = { id -> navController.navigate("workout/$id") },
                    onSyncWatch = { vm.syncWatch() },
                )
            }
            composable(Destination.RacePlans.route) {
                val raceVm: RacePlansViewModel = viewModel(
                    key = "race-plans",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            RacePlansViewModel(container) as T
                    },
                )
                RacePlansScreen(vm = raceVm)
            }
            composable(Destination.Strength.route) {
                val strengthVm: StrengthViewModel = viewModel(
                    key = "strength",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            StrengthViewModel(container) as T
                    },
                )
                StrengthScreen(vm = strengthVm)
            }
            composable(Destination.Mobility.route) {
                val flexVm: FlexibilityViewModel = viewModel(
                    key = "mobility",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            FlexibilityViewModel(container) as T
                    },
                )
                FlexibilityScreen(vm = flexVm)
            }
            composable(Destination.Settings.route) {
                val profileVm: com.tracks.app.ui.profile.ProfileViewModel = viewModel(
                    key = "profile",
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            com.tracks.app.ui.profile.ProfileViewModel(container) as T
                    },
                )
                val profile by profileVm.state.collectAsStateWithLifecycle()
                // Re-read whenever the session changes: a sign-in pulls the
                // account's settings row down, and a sign-out makes the phone
                // standalone again.
                val linked by produceState(false, state.session) { value = container.isLinked() }
                LaunchedEffect(state.session) { profileVm.reload() }
                SettingsScreen(
                    state = state,
                    profile = profile,
                    onSet = { field, value -> profileVm.set(field, value) },
                    container = container,
                    linked = linked,
                    feeds = feeds,
                    onServerUrlChange = vm::setServerUrl,
                    onSearchServer = vm::searchLan,
                    onLogin = vm::login,
                    // Two different syncs, and they are not interchangeable:
                    // `vm::sync` syncs with the server, `onSync` pulls files
                    // off the watch over Bluetooth.
                    onSync = vm::sync,
                    onWatchPair = onPair,
                    onWatchSync = onSync,
                    hasDevice = hasDevice,
                    onHasDeviceChange = container::setHasDevice,
                    onLogout = vm::logout,
                    onChangePassword = vm::changePassword,
                    onErase = { vm.eraseLocalData(); profileVm.reload() },
                    onRestored = { profileVm.reload(); container.localData.changed() },
                    tutorialEnabled = tour.enabled,
                    onTutorialEnabled = tourVm::setEnabled,
                    // To the dashboard, so the first tour starts at once — as
                    // the web's Restart tutorial does.
                    onRestartTutorial = {
                        tourVm.restart()
                        navigateTo(Destination.Dashboard)
                    },
                    music = { MusicSettings(vm, state.music) },
                )
            }
        }
        }
    }
    }
    // Over the drawer and every page, detail screens included, which bring
    // their own Scaffold and would otherwise draw over a tip.
    com.tracks.app.ui.tour.TourOverlay(
        state = tour,
        anchors = tourAnchors,
        visible = drawer.isClosed && drawer.targetValue == DrawerValue.Closed,
        onNext = tourVm::next,
        onPrev = tourVm::prev,
        onClose = tourVm::complete,
    )
    }
}
}

/**
 * The sidebar.
 *
 * A flat list, in the desktop app's order, with no grouping and no headers —
 * nine items do not need organising, and a group label is a thing to read on the
 * way to the thing you wanted. The selected item is filled rather than merely
 * tinted, because on a screen the drawer covers most of, the highlight is the
 * only thing saying where you already are.
 */
@Composable
private fun TracksDrawer(
    current: Destination?,
    hasDevice: Boolean,
    onNavigate: (Destination) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "Tracks",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
            )

            Destination.entries.forEach { destination ->
                // Settings sits under a rule at the bottom of the list, as it
                // does in the browser: it is the one entry that is not a place
                // you go to look at your training.
                if (destination == Destination.Settings) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }
                NavigationDrawerItem(
                    label = { Text(destination.label) },
                    icon = { Icon(destination.icon, contentDescription = null) },
                    selected = destination == current,
                    onClick = { onNavigate(destination) },
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The map's own way into the sidebar.
 *
 * The same hamburger in the same corner, drawn as a floating control because the
 * map has no bar to put it in. Matched to the map's existing round buttons at
 * the top right rather than to the app bar, so it reads as part of the map's
 * furniture — which is what it is.
 */
@Composable
fun MapDrawerButton(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.padding(12.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.xl),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
    ) {
        IconButton(onClick = onOpen) {
            Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
        }
    }
}

/**
 * The sign-in refusal, where the user can see it and choose.
 *
 * Deliberately plain: the onboarding and settings rework will give this a
 * proper home. What matters now is that the refusal is visible and that
 * erasing is a choice the user makes, never a side effect. Shown from both the
 * onboarding flow and the main shell, because the first sign-in happens in one
 * and every later one in the other.
 */
@Composable
fun AccountConflictDialog(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    state.accountConflict?.let {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissAccountConflict,
            title = { androidx.compose.material3.Text("This phone holds another account's data") },
            text = {
                androidx.compose.material3.Text(
                    "Signing in would merge it into this account, which cannot be undone, " +
                        "so it is refused. Erase this phone's data to continue, or keep it " +
                        "and sign in as the account it belongs to.",
                )
            },
            confirmButton = {
                DangerButton("Erase this phone's data", onClick = vm::eraseLocalData)
            },
            dismissButton = {
                NeutralButton("Keep it", onClick = vm::dismissAccountConflict)
            },
        )
    }

    // Not a refusal: a different server id cannot be told apart from this
    // user's own server rebuilt, so it is a question (spec/sync.yaml,
    // "Accounts and sign-out").
    state.serverRestore?.let { question ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::declineNewServer,
            title = { androidx.compose.material3.Text("Restore this phone's data?") },
            text = {
                androidx.compose.material3.Text(
                    "This phone's data came from a different or rebuilt Tracks server. " +
                        "Restore it into ${question.username ?: "this account"} here?",
                )
            },
            confirmButton = {
                PrimaryButton("Restore", onClick = vm::restoreIntoNewServer)
            },
            dismissButton = {
                NeutralButton("No, sign out", onClick = vm::declineNewServer)
            },
        )
    }
}
