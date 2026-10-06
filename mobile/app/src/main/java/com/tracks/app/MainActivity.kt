// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tracks.app.device.BluetoothPermissions
import com.tracks.app.device.CompanionDevicePicker
import com.tracks.app.device.WatchLinkPreference
import com.tracks.app.feeds.FeedPermissions
import com.tracks.app.feeds.WeatherReceiver
import com.tracks.app.ui.TracksNavHost
import com.tracks.app.ui.onboarding.OnboardingScreen
import com.tracks.app.ui.screens.FeedStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.theme.TracksTheme
import com.tracks.device.CompanionPairing
import kotlinx.coroutines.launch

/**
 * Hosts the Compose tree and the two things only an Activity can provide: the
 * companion-device dialog and the runtime permission prompt.
 *
 * Everything else lives under `ui/` — this file deliberately holds no layout,
 * so that the screens can be reasoned about (and eventually previewed and
 * tested) without an Activity in the picture.
 */
class MainActivity : ComponentActivity() {

    /**
     * Built here, and it has to be: `registerForActivityResult` refuses once the
     * Activity has started, so constructing this lazily — at the moment the user
     * taps "pair" — would throw. Cheap enough to hold unconditionally.
     */
    private lateinit var picker: CompanionDevicePicker
    private lateinit var permissions: BluetoothPermissions
    private lateinit var feedPermissions: FeedPermissions

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)
    }

    /** A notification asked for a particular page; the nav host picks it up. */
    private fun routeFrom(intent: android.content.Intent?) {
        val open = intent?.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN)
        (application as TracksApplication).container.pendingRoute.value = open
    }

    companion object {
        const val EXTRA_OPEN = "open"
        /** Settings, where the backup section is. */
        const val OPEN_BACKUP = "backup"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        picker = CompanionDevicePicker(this)
        permissions = BluetoothPermissions(this)
        feedPermissions = FeedPermissions(this)
        val container = (application as TracksApplication).container
        routeFrom(intent)
        setContent {
            // Collected here rather than inside the theme so a change repaints
            // the whole app, chrome included, in one recomposition.
            val themeMode by container.themeMode.collectAsStateWithLifecycle()
            val accent by container.accent.collectAsStateWithLifecycle()

            TracksTheme(mode = themeMode, accent = accent) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TracksApp(container, picker, permissions, feedPermissions)
                }
            }
        }
    }

    /**
     * Bring the watch link up from here, not from `Application.onCreate`.
     *
     * That was where it started, and Android refused it every time:
     *
     * ```
     * ForegroundServiceStartNotAllowedException: startForegroundService() not
     * allowed: service com.tracks.app/.WatchLinkService
     * ```
     *
     * `Application.onCreate` runs before any Activity exists, so the process is
     * still cached (`uidState: CEM`) and the start counts as a background one,
     * which Android 12+ forbids. The companion exemption does not cover it
     * either — that applies to an app with an *active* association acting on the
     * device, not to a process that has only just begun to exist.
     *
     * `onStart` is unambiguously foreground, where the start is always allowed.
     * The restriction is on *starting* a foreground service, not on one
     * continuing to run, so the link survives the app being backgrounded
     * afterwards — which is the whole point of it.
     *
     * The other route in is [WatchSyncService], which starts the link when a
     * scheduled sync finishes. That covers the case this cannot: a phone
     * rebooted and never opened.
     */
    override fun onStart() {
        super.onStart()
        WatchLinkPreference.start(this)
        (application as TracksApplication).container.smartWatchSync.appVisible(true)
    }

    override fun onStop() {
        (application as TracksApplication).container.smartWatchSync.appVisible(false)
        super.onStop()
    }

    /**
     * Back in the foreground: the phone may have crossed a time zone while the
     * process was gone, when no broadcast could reach it (TimezoneSync), and
     * may be somewhere else — the one moment Android lets this app read where
     * it is, since it asks for no background location (WeatherLocationSync).
     */
    override fun onResume() {
        super.onResume()
        val container = (application as TracksApplication).container
        container.syncTimezoneSoon()
        container.syncWeatherLocationSoon()
    }
}

@Composable
private fun TracksApp(
    container: AppContainer,
    picker: CompanionPairing.DevicePicker,
    permissions: BluetoothPermissions,
    feedPermissions: FeedPermissions,
) {
    val vm: MainViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(container) as T
        },
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Three states, not two: null means "not yet known". Rendering the nav host
    // while the answer is outstanding would flash the main UI at a first-run
    // user for a frame before replacing it with the wizard, and rendering the
    // wizard would do the same to everyone else — worse, since a stray tap
    // during that frame lands on a text field. A blank frame is the only
    // honest thing to show while a disk read is in flight.
    var onboarded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { onboarded = container.onboardingComplete() }

    when (onboarded) {
        null -> return
        false -> {
            OnboardingScreen(
                vm = vm,
                container = container,
                bluetooth = permissions,
                feeds = feedPermissions,
                onPair = { scope.launch { if (permissions.ensure()) vm.pairWatch(picker) } },
                onHasDevice = container::setHasDevice,
                onFinished = {
                    container.completeOnboarding()
                    onboarded = true
                },
            )
            // The first sign-in happens in here, so the refusal has to be
            // visible here too — see AccountConflictDialog.
            com.tracks.app.ui.AccountConflictDialog(vm)
            return
        }
        else -> Unit
    }

    // Recomputed whenever the app returns to the foreground: all three of
    // these are changed *outside* Tracks — in system settings, or in Breezy —
    // so the only reliable moment to re-read them is on resume. Without this
    // the user grants notification access, comes back, and Settings still
    // says it is off.
    val lifecycleOwner = LocalLifecycleOwner.current
    var feedsNonce by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) feedsNonce++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val feeds = remember(feedsNonce) {
        buildFeedStatuses(context, feedPermissions, scope)
    }

    TracksNavHost(
        vm = vm,
        container = container,
        feeds = feeds,
        // The permission prompt has to precede both actions, not just pairing:
        // a phone that was granted the permission, then had it revoked in
        // Settings, still has a stored association — so "Sync" is reachable
        // with no permission at all.
        onPair = { scope.launch { if (permissions.ensure()) vm.pairWatch(picker) } },
        onSync = { scope.launch { if (permissions.ensure()) vm.syncWatch() } },
    )
}

/**
 * The three phone feeds, and what is stopping each one.
 *
 * All three shipped wired and silent — see [FeedPermissions] for how that
 * happened. The point of listing them here is that a feed which is merely
 * ungranted is indistinguishable from one that is broken, and only one of
 * those is the user's to fix.
 */
private fun buildFeedStatuses(
    context: android.content.Context,
    feedPermissions: FeedPermissions,
    scope: kotlinx.coroutines.CoroutineScope,
): List<FeedStatus> {
    val notificationsOn = FeedPermissions.notificationAccessGranted(context)
    val weather = com.tracks.app.feeds.WeatherSources.let { sources ->
        sources.status(sources.installed(context), sources.history(context), System.currentTimeMillis()) {
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))
        }
    }

    return listOf(
        FeedStatus(
            name = "Notifications",
            description = if (notificationsOn) {
                "Phone notifications are relayed to your watch."
            } else {
                "Off. Tracks needs notification access — find Tracks in the " +
                    "list Android opens and turn it on."
            },
            enabled = notificationsOn,
            actionLabel = "Grant notification access",
            onAction = { context.startActivity(FeedPermissions.notificationAccessIntent()) },
        ),
        FeedStatus(
            name = "Calendar",
            description = if (feedPermissions.calendarGranted) {
                "Your agenda is available when the watch asks for it."
            } else {
                "Off. Only event title, time, and location ever leave the phone."
            },
            enabled = feedPermissions.calendarGranted,
            actionLabel = "Allow calendar access",
            onAction = { scope.launch { feedPermissions.ensureCalendar() } },
        ),
        FeedStatus(
            name = "Weather",
            // From what actually arrived, persisted across restarts — not from
            // the in-memory forecast, which every process restart cleared.
            description = weather.text,
            enabled = weather.ok,
            actionLabel = weather.provider?.let { "Open ${it.name}" } ?: "Get Breezy Weather",
            onAction = {
                val provider = weather.provider
                if (provider != null) {
                    com.tracks.app.feeds.WeatherSources.open(context, provider)
                } else {
                    com.tracks.app.feeds.WeatherSources.openLink(
                        context, com.tracks.app.feeds.WeatherSources.PROVIDERS.first().downloads.first().second,
                    )
                }
            },
        ),
    )
}
