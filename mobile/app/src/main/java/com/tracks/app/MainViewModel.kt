// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.device.WatchConfigRevision
import com.tracks.app.device.WatchManager
import com.tracks.core.api.MusicServer
import com.tracks.core.api.MusicServerRequest
import com.tracks.core.api.MusicServerDiscovery
import com.tracks.core.api.RemoteSong
import com.tracks.app.device.WatchMusicPreference
import com.tracks.device.garmin.WatchAppConfig
import com.tracks.device.garmin.GarminIntegration
import com.tracks.core.api.ActivitySummary
import com.tracks.core.api.Capabilities
import com.tracks.core.api.IncompatibleServerException
import com.tracks.core.api.NotAuthenticatedException
import com.tracks.core.api.WrongPasswordException
import com.tracks.core.api.SessionState
import com.tracks.core.replica.LinkResult
import com.tracks.core.api.ServerNotFoundException
import com.tracks.core.api.VaultLockedException
import com.tracks.core.api.discoverApiBase
import com.tracks.core.api.RemotePlaylistList
import com.tracks.core.api.probeForTracks
import com.tracks.app.net.LanScan
import com.tracks.device.CompanionPairing
import com.tracks.device.ConnectionState
import com.tracks.device.PulledFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Screen state for the one screen this app currently has.
 *
 * `activities` is read from the local mirror, never from the network directly —
 * so the list renders on a cold start with no signal, which is the whole point
 * of the offline mirror. Syncing updates the mirror; the UI re-reads it.
 */
data class UiState(
    val serverUrl: String = "",
    val session: SessionState = SessionState.LoggedOut,
    val activities: List<ActivitySummary> = emptyList(),
    val capabilities: Capabilities? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** Progress of a running search for the server on this network; null when none is. */
    val scan: String? = null,
    /** True once this device can reopen the vault without a password. */
    val deviceKeyEnrolled: Boolean = false,
    /** GPS for the most recently tapped activity, if it has been fetched. */
    val trackPoints: Int? = null,
    val trackActivityId: Int? = null,
    val watch: WatchUiState = WatchUiState(),
    val music: MusicUiState = MusicUiState(),
    /**
     * A sign-in was refused because this phone holds another account's data.
     * Shown as a dialog offering to erase it; see [MainViewModel.eraseLocalData].
     */
    val accountConflict: LinkResult.OtherAccount? = null,
    /**
     * A sign-in on a server with a different id — a stranger's, or this
     * user's own rebuilt. Shown as "restore this phone's data here?"; see
     * [MainViewModel.restoreIntoNewServer] and [MainViewModel.declineNewServer].
     */
    val serverRestore: LinkResult.DifferentServer? = null,
)

/**
 * What the screen knows about music setup.
 *
 * Two destinations share the one music server. [smart], [tracks] and [plan]
 * describe the library the *Tracks server* pushes over USB. [watchPlaylists]
 * and [watchSelection] describe the on-watch app, which talks to the music
 * server itself over Wi-Fi and never sees that library. A user can have one
 * without the other, and the screens have to be able to say which.
 */
data class MusicUiState(
    val server: MusicServer? = null,
    /** What the watch app can download: two built-ins, then the server's playlists. */
    val watchPlaylists: List<WatchPlaylist> = emptyList(),
    /** The phone's pick of [watchPlaylists], by id. Optional — the watch has its own. */
    val watchSelection: Set<String> = emptySet(),
    /** A selection has been queued for the watch and not yet collected. */
    val watchSelectionPending: Boolean = false,
    /** Single songs picked for the watch, on top of whole playlists. */
    val watchSongs: List<WatchMusicPreference.PickedSong> = emptyList(),
    /** The last search's results, for picking from. */
    val songResults: List<RemoteSong> = emptyList(),
    val searching: Boolean = false,
    val watchAppInstalled: Boolean = false,
    /** Apps installed on the watch, for the manage-and-remove list; null until loaded. */
    val installedApps: List<GarminIntegration.InstalledApp>? = null,
    val appsBusy: Boolean = false,
    /** A music server the LAN search turned up, for the address field to take. */
    val foundServer: String? = null,
    /** Progress of a running network search; null when none is. */
    val scan: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** One thing the watch app can be asked to keep. */
data class WatchPlaylist(val id: String, val name: String, val songCount: Int?)

/**
 * The two playlists the watch app synthesises from the server rather than
 * reading as playlists. Ids match the watch app's TracksLibrary constants, and
 * cannot collide with a server id — Navidrome never mints one starting `~`.
 */
private val WATCH_BUILTINS = listOf(
    WatchPlaylist("~starred", "Liked songs", null),
    WatchPlaylist("~recent", "Recently played", null),
)

internal data class WatchPlaylists(val playlists: List<WatchPlaylist>, val selected: Set<String>)

/**
 * What the phone offers for the watch: the two built-ins, then the server's
 * playlists.
 *
 * A built-in is dropped when the server already has a playlist of the same
 * name — people who keep a "Liked Songs" smart playlist for devices that
 * cannot read stars saw it twice, once ticked and once not. A tick on the
 * dropped built-in moves to that playlist, so what the watch keeps does not
 * quietly change. Names match ignoring case, spaces and punctuation.
 */
internal fun watchPlaylistsFor(remote: RemotePlaylistList, selected: Set<String>): WatchPlaylists {
    fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    val byName = remote.playlists.associateBy { key(it.name) }
    val counts = mapOf("~starred" to remote.starredCount, "~recent" to remote.recentCount)
    val ticks = selected.toMutableSet()
    val builtins = WATCH_BUILTINS.mapNotNull { b ->
        val twin = byName[key(b.name)] ?: return@mapNotNull b.copy(songCount = counts[b.id])
        if (ticks.remove(b.id)) ticks.add(twin.id)
        null
    }
    return WatchPlaylists(
        builtins + remote.playlists.map { WatchPlaylist(it.id, it.name, it.songCount) },
        ticks,
    )
}

/**
 * What the screen knows about the watch.
 *
 * Separate from the rest of [UiState] because it updates on a different clock —
 * connection state and battery arrive from the BLE layer whenever the watch
 * feels like it, not in response to anything the user did.
 */
data class WatchUiState(
    val pairedName: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val batteryPercent: Int? = null,
    /**
     * Files seen coming off the watch during this run, for progress only.
     *
     * Deliberately not what gets uploaded. This is filled by a flow collector
     * with no ordering against the run finishing, so it is routinely behind —
     * uploading from it left 54 of 116 files stranded on the phone. The upload
     * list comes from [WatchManager.pullAll]'s return value.
     */
    val pulled: List<PulledFile> = emptyList(),
)

/**
 * ## A note on how state is written
 *
 * Every mutation here goes through `_state.update { }`, never
 * `_state.value = _state.value.copy(...)`. The difference is not stylistic.
 *
 * The assignment form reads the current value, computes a copy, and writes it
 * back — and on this screen those three steps are routinely separated by a
 * suspension point, because the read happens before an `await` and the write
 * after it. Six coroutines write this state: the cache load, the session
 * collector, and one collector per watch signal. Whichever one suspends longest
 * silently discards everything the others did while it was away.
 *
 * That is not theoretical. It shipped, and it presented on a real device as a
 * paired watch that reported "No watch paired" — the pairing loaded correctly
 * and was then overwritten by a cache read that had captured the state before
 * the pairing arrived. `update` is a compare-and-set loop and cannot do that.
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState(serverUrl = container.serverUrl.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Show whatever is cached before touching the network, so a cold
            // start on a train shows the user's data rather than a spinner.
            refreshFromCache()
            _state.update { current -> current.copy(
                deviceKeyEnrolled = container.tokens.load().canSelfUnlock,
                session = if (container.tokens.load().isAuthenticated) {
                    SessionState.Active
                } else {
                    SessionState.LoggedOut
                },
            ) }
            // The account's own preferences — units and accent — before
            // anything is drawn that formats a distance, and again once a pull
            // may have brought another device's choice.
            runCatching { container.refreshPreferences() }
            catchUp()
            runCatching { container.refreshPreferences() }
        }
        viewModelScope.launch {
            container.sessionState.collect { s -> _state.update { current -> current.copy(session = s) } }
        }
        // The list is this view model's, and rows can arrive without it asking:
        // the background watch service runs the same sync as the button, and a
        // run recorded on the phone writes its own provisional row. Both used
        // to be invisible until the next cold start, which offline is the only
        // thing that ever refreshes anything.
        viewModelScope.launch {
            container.localData.revision.drop(1).collect { refreshFromCache() }
        }
        observeWatch()
    }

    /**
     * Follow the watch's own reporting.
     *
     * Four separate collectors rather than one combined flow: they emit at
     * wildly different rates — a pulled file every few minutes, a connection
     * change a few times a day, battery whenever the watch mentions it — and
     * combining them would recompose the screen on the union of all of them.
     */
    private fun observeWatch() {
        val watch = container.watch
        viewModelScope.launch {
            watch.paired.collect { device -> updateWatch { it.copy(pairedName = device?.name) } }
        }
        viewModelScope.launch {
            watch.connection.collect { state -> updateWatch { it.copy(connection = state) } }
        }
        viewModelScope.launch {
            watch.battery.collect { percent -> updateWatch { it.copy(batteryPercent = percent) } }
        }
        viewModelScope.launch {
            watch.pulledFiles.collect { file -> updateWatch { it.copy(pulled = it.pulled + file) } }
        }
    }

    private fun updateWatch(transform: (WatchUiState) -> WatchUiState) {
        _state.update { current -> current.copy(watch = transform(current.watch)) }
    }

    /**
     * Associate a watch through the system dialog.
     *
     * The picker comes from the Activity because only an Activity can show that
     * dialog — see CompanionDevicePicker. Everything after this point works
     * from a background worker.
     */
    fun pairWatch(picker: CompanionPairing.DevicePicker) = run("Pairing") {
        val device = container.watch.pair(picker)
        _state.update { current -> current.copy(message = "Paired with ${device.name}.") }
    }

    /**
     * A full watch sync: push what the server has waiting, pull what the watch
     * recorded, seal it, upload it, and only then let the watch reclaim it.
     *
     * The sequence itself lives in [WatchSyncRunner], shared with
     * [WatchSyncService] — this function's job is only to turn an [Outcome]
     * into a message a person reads. [alreadyRunning] means the background
     * service beat this button to the watch; that is success, not a fault, so
     * it gets a message rather than the generic failure path.
     */
    fun syncWatch() = run("Syncing watch") { syncWatchNow() }

    /**
     * The same sync, awaited.
     *
     * For a pull-to-refresh, which draws its own spinner and needs to know when
     * to stop — a fire-and-forget launch would leave the gesture's indicator
     * guessing. Everything it does to state, including the message, is the
     * button's; only the waiting differs.
     */
    private suspend fun syncWatchNow() {
        updateWatch { it.copy(pulled = emptyList()) }
        val outcome = container.watchSync.runOnceDetached()

        if (outcome.alreadyRunning) {
            _state.update { current -> current.copy(message = "A sync is already running.") }
            return
        }
        if (!outcome.paired) {
            _state.update { current -> current.copy(message = "No watch paired yet.") }
            return
        }

        // The schedule counts as news even when no workout file moved: it is
        // the half the user can actually see on the watch, and a run that
        // re-sent only the calendar should say so rather than report nothing.
        val pushNote = outcome.pushed?.let { push ->
            val files = if (push.delivered > 0 || push.failed > 0) {
                "Sent ${push.delivered} to the watch. "
            } else {
                ""
            }
            val schedule = push.scheduled?.takeIf { it > 0 }
                ?.let { "Scheduled $it workout(s) on the watch. " } ?: ""
            files + schedule
        } ?: ""

        if (outcome.pulled.isEmpty()) {
            _state.update { current -> current.copy(message = pushNote + "Watch had nothing new.") }
            return
        }

        // The list reads the mirror, and the mirror has just gained rows the
        // phone read out of the watch's own files. Without this they would not
        // appear until something else happened to refresh — which, with no
        // signal, is nothing.
        if (outcome.imported > 0) refreshFromCache()
        val importNote = if (outcome.imported > 0) {
            "${outcome.imported} new activity(s) are in your list. "
        } else {
            ""
        }
        // Days of sleep, steps and body data the phone read for itself — the
        // half of a watch sync that used to be invisible until the files
        // reached a server. Worth saying out loud for the same reason the
        // activity count is: it is how somebody in a hut knows the sync did
        // something rather than merely finished.
        val healthNote = if (outcome.health > 0) {
            "${outcome.health} day(s) of health data updated. "
        } else {
            ""
        }

        if (outcome.notRegistered) {
            _state.update { current -> current.copy(
                message = pushNote + importNote + healthNote +
                    "Pulled ${outcome.pulled.size} file(s), but this phone is " +
                    "not registered with the server yet — sign in once to finish setup.",
            ) }
            return
        }

        val result = outcome.uploadResult
        _state.update { current -> current.copy(
            message = buildString {
                append(pushNote)
                append(importNote)
                append(healthNote)
                append("Pulled ${outcome.pulled.size}, delivered ${result?.delivered ?: 0}")
                if ((result?.failed ?: 0) > 0) append(", ${result?.failed} still on the watch to retry")
            },
        ) }
    }

    fun disconnectWatch() = run("Disconnecting") {
        container.watch.disconnect()
    }

    /**
     * Save a new server address and find the API behind it straight away.
     *
     * Saving alone used to leave the client pointed at the bare origin until
     * somebody pressed Check — and behind a reverse proxy the bare origin is
     * the web app, which answers every API call with an HTML page. The check
     * is what makes the address usable, so it is part of saving it.
     */
    fun setServerUrl(url: String) {
        container.setServerUrl(url)
        // A new address has not answered yet: the old one's capabilities
        // would show sign-in (ServerConnectForm) for a server never reached.
        _state.update { current -> current.copy(serverUrl = container.serverUrl.value, capabilities = null) }
        if (url.isNotBlank()) checkServer()
    }

    /**
     * Find the API behind whatever the user typed, then check we can talk to it.
     *
     * Discovery runs here rather than on every request: the answer depends on
     * how the operator deployed Tracks, not on anything that changes, so it is
     * settled once and remembered.
     */
    fun checkServer() = run("Checking server") {
        stopServerScan()
        val base = discoverApiBase(container.serverUrl.value)
        container.setApiBase(base)
        val caps = container.client().requireCompatible()
        _state.update { current -> current.copy(
            capabilities = caps,
            message = "${caps.app} ${caps.serverVersion} — API v${caps.apiVersion} at $base",
        ) }
    }

    /**
     * Look for a Tracks server on the network this phone is already on.
     *
     * For the case the address field cannot help with: a server that is running
     * on the LAN at an address its owner has never had to know. Self-hosting
     * means there is nothing to guess, and "find the box on my own network" is
     * the one guess a phone can actually make.
     *
     * Reports how far it has got. A sweep of a subnet takes seconds even when
     * it succeeds and longer when it does not, and a button that sits there
     * saying nothing is one people press twice.
     */
    fun searchLan() {
        // A second press stops it, like any other search.
        serverScan?.takeIf { it.isActive }?.let { stopServerScan(); return }
        serverScan = viewModelScope.launch {
            try {
                findTracksOnLan()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Searching the network failed: ${e.message}") }
            } finally {
                _state.update { it.copy(scan = null) }
            }
        }
    }

    /**
     * Stop a network search. Connect calls this: the user who typed an address
     * and pressed Connect has answered the question the sweep was asking, and
     * a late find must not overwrite what they chose.
     */
    private fun stopServerScan() {
        serverScan?.cancel()
        serverScan = null
        _state.update { it.copy(scan = null) }
    }

    private var serverScan: kotlinx.coroutines.Job? = null

    private suspend fun findTracksOnLan() {
        val subnet = LanScan.subnet(container.appContext)
        if (subnet == null) {
            _state.update { current -> current.copy(
                message = "This phone is not on a network I can search — connect to Wi-Fi first.",
            ) }
            return
        }

        val hosts = LanScan.hosts(subnet)
        if (hosts.isEmpty()) {
            _state.update { current -> current.copy(
                message = "This network is too large to search (/${subnet.prefixLength}). " +
                    "Enter the address instead.",
            ) }
            return
        }

        _state.update { current -> current.copy(
            message = null,
            scan = "Searching ${hosts.size} addresses…",
        ) }

        val found = probeForTracks(
            candidates = LanScan.candidates(hosts),
            onProgress = { checked, total ->
                _state.update { current -> current.copy(
                    scan = "Searching your network… ${checked * 100 / total}%",
                ) }
            },
        )

        if (found == null) {
            _state.update { current -> current.copy(
                message = "No Tracks server answered on this network. " +
                    "If it is somewhere else, enter its address.",
            ) }
            return
        }

        // Everything the Connect button would have done, minus the typing.
        val origin = LanScan.originOf(found)
        container.setServerUrl(origin)
        container.setApiBase(found)
        val caps = container.client().requireCompatible()
        _state.update { current -> current.copy(
            serverUrl = container.serverUrl.value,
            capabilities = caps,
            message = "Found ${caps.app} ${caps.serverVersion} at $origin",
        ) }
    }

    /**
     * Log in, then immediately enrol a device key.
     *
     * Enrolment happens here rather than on a settings screen because it needs
     * a live vault, and this is the one moment the app is guaranteed to have
     * one. Skip it and the user is back to a password prompt the first time
     * their crypto session lapses — roughly weekly, which is the entire problem
     * device keys exist to solve.
     */
    fun login(username: String, password: String) = run("Signing in") {
        // Sign-in is often the first thing tried, so discover if Check wasn't
        // pressed — otherwise the request goes to whatever the user typed and
        // fails for a reason that looks like bad credentials.
        if (container.apiBase.value.isBlank()) {
            container.setApiBase(discoverApiBase(container.serverUrl.value))
        }
        container.client().login(username, password, deviceLabel = android.os.Build.MODEL)
        // Before anything else touches the session: if this phone's data
        // belongs to someone else, the check has already signed back out.
        val link = try {
            container.checkAccount()
        } catch (e: Exception) {
            // Could not tell (the network dropped between the two calls).
            // Refuse rather than guess: an accidental merge cannot be undone.
            container.client().logout()
            throw e
        }
        when (link) {
            is LinkResult.OtherAccount -> {
                _state.update { current -> current.copy(accountConflict = link) }
                return@run
            }
            // The session stays up while the user decides, so "yes" needs no
            // second password; nothing syncs until they have answered.
            is LinkResult.DifferentServer -> {
                _state.update { current -> current.copy(serverRestore = link) }
                return@run
            }
            else -> Unit
        }
        finishSignIn()
    }

    /**
     * Yes to "restore this phone's data into <username> here?": rebind to the
     * new server and queue everything for it, then finish signing in.
     */
    fun restoreIntoNewServer() = run("Restoring this phone's data") {
        val question = _state.value.serverRestore ?: return@run
        container.restoreInto(question)
        _state.update { current -> current.copy(serverRestore = null) }
        finishSignIn()
    }

    /** No: sign back out, with this phone's data and its account untouched. */
    fun declineNewServer() = run("Signing out") {
        container.declineNewServer()
        _state.update { current -> current.copy(
            serverRestore = null,
            message = "Signed out. This phone's data was kept.",
        ) }
    }

    private suspend fun finishSignIn() {
        val enrolled = runCatching {
            container.client().enrolDeviceKey(android.os.Build.MODEL)
        }.isSuccess
        // Register the sync agent here for the same reason device keys are
        // enrolled here: this is the one moment a live session is guaranteed,
        // and after it watch sync never needs one again.
        runCatching { container.registerSyncAgent(android.os.Build.MODEL) }
            .onFailure { _state.update { current -> current.copy(message = "Sync agent registration failed: ${it.message}") } }
        _state.update { current -> current.copy(
            deviceKeyEnrolled = enrolled,
            message = if (enrolled) {
                "Signed in. This device can unlock on its own."
            } else {
                "Signed in, but device enrolment failed — you may be asked for " +
                    "your password again later."
            },
        ) }
        sync()
    }

    /**
     * Pull anything new, quietly, when the app opens.
     *
     * ## Why this exists
     *
     * Nothing else was doing it. The mirror was filled by the six-hourly
     * [SyncWorker] and by the Sync button in Settings, and neither runs when
     * someone opens the app to look at what they did last night — so an
     * activity imported an hour ago was simply absent, with no indication that
     * anything was missing. "It shows on the web but not on my phone" is what
     * that looks like from the outside, and the answer was always "sync again",
     * which is not an answer.
     *
     * ## Quiet on purpose
     *
     * No busy flag and no snackbar. The user did not ask for this and does not
     * need to be told it happened — the screen simply has the new rows on it a
     * moment after opening. It is also cheap: with nothing to fetch a delta
     * sync is one request that returns an empty page, and the six-hourly
     * worker still covers the case where the app is never opened at all.
     *
     * Failure is silence. There is no signal often, that is the whole premise
     * of the mirror, and an error toast on every cold start in a valley would
     * be worse than useless.
     */
    private suspend fun catchUp() {
        runCatching { container.syncWithServer(withHistory = false) }
        refreshFromCache()
    }

    fun sync() = run("Syncing") {
        if (!container.isLinked()) {
            _state.update { it.copy(message = "This phone is not linked to a server — everything is already here.") }
            return@run
        }
        val result = container.syncWithServer(withHistory = true)
        refreshFromCache()
        _state.update { current -> current.copy(
            message = buildString {
                append("Synced: ${result?.pushed ?: 0} sent, ${result?.pulled ?: 0} received")
                if ((result?.blobsFetched ?: 0) > 0) append(", ${result!!.blobsFetched} file(s)")
                if (result?.newServer == true) append(" — restored this phone's data to the server")
                if (result?.wiped == true) append(" — your data was deleted on the server, and here too")
                if (result?.truncated == true) append(" — more to come, sync again")
            },
        ) }
    }

    /**
     * Fetch an activity's GPS.
     *
     * The first thing this app does that needs the *vault* rather than just a
     * token: lat/lng are encrypted columns, so the server can only answer with
     * a live crypto session. If that session has lapsed the request comes back
     * `session_expired`, and the client unlocks with the device key and retries
     * — which is the entire point of enrolling one, and is invisible from here.
     *
     * Bounded at 500 points. The server-side default is the user's
     * chart_resolution and can be uncapped, which on a long ride is tens of
     * thousands of samples nobody asked a phone to hold.
     */
    fun loadTrack(activityId: Int) = run("Loading GPS") {
        val points = container.client().activityTrack(activityId, maxPoints = 500)
        val located = points.count { it.lat != null && it.lng != null }
        _state.update { current -> current.copy(
            trackActivityId = activityId,
            trackPoints = located,
            message = "Activity $activityId: $located GPS points (vault is open)",
        ) }
    }

    /**
     * Change the account's password. The server signs every other device out
     * and revokes this phone's own credentials with the rest; the client swaps
     * in the replacements it hands back and re-enrols this phone's device key,
     * so the user stays signed in here and only the others need the new one.
     */
    fun changePassword(current: String, new: String) = run("Changing password") {
        val result = container.client().changePassword(current, new, android.os.Build.MODEL)
        val others = result.otherDevicesSignedOut
        _state.update { now -> now.copy(
            deviceKeyEnrolled = result.deviceKeyReEnrolled,
            message = buildString {
                append("Password changed.")
                if (others > 0) append(" $others other device${if (others == 1) " was" else "s were"} signed out.")
                if (!result.deviceKeyReEnrolled) append(" You may be asked for your password again later.")
            },
        ) }
    }

    fun logout() = run("Signing out") {
        // Stop background work before dropping the credentials it needs, so a
        // run cannot land between the two and log a spurious failure.
        SyncWorker.cancel(container.appContext)
        container.logout()
        _state.update { current -> current.copy(
            activities = emptyList(),
            deviceKeyEnrolled = false,
            capabilities = null,
            message = "Signed out.",
        ) }
    }

    /** Keep this phone's data; the refused sign-in stays refused. */
    fun dismissAccountConflict() {
        _state.update { current -> current.copy(accountConflict = null) }
    }

    /**
     * Erase everything on this phone so a different account can sign in. The
     * user signs in again afterwards — deliberately: erasing and linking are
     * two decisions, and this only makes the first.
     */
    fun eraseLocalData() = run("Erasing this phone's data") {
        SyncWorker.cancel(container.appContext)
        container.eraseLocalData()
        _state.update { current -> current.copy(
            accountConflict = null,
            activities = emptyList(),
            message = "This phone's data was erased. Sign in again.",
        ) }
    }

    fun dismissMessage() {
        _state.update { current -> current.copy(message = null) }
    }

    private suspend fun refreshFromCache() {
        // Read first, then update. `update` retries its lambda when another
        // coroutine wins the race, so anything inside it must be cheap and
        // repeatable — a database read is neither.
        val activities = container.sources.activities()
        _state.update { current -> current.copy(activities = activities) }
    }

    /**
     * Runs work with a busy flag and turns the client's typed failures into
     * something a person can read.
     *
     * The three exceptions map to genuinely different situations, and telling
     * them apart is the difference between "enter your password" and "check
     * your connection" — advice that is useless if it is wrong.
     */
    // ── Music ────────────────────────────────────────────────────────────────

    /**
     * Load the music-server connection and the playlists on offer.
     *
     * [arm] also re-arms the phone's answer to the watch app's config request
     * (see [refreshWatchMusicConfig]) — which hands the music password to any
     * app on the watch that asks, for ten minutes. Onboarding's music step
     * arms, as the Music page did on every visit; Settings does not, because
     * Settings is opened far more often than that page was and the window
     * would be open most of the day. There, the user re-arms on purpose with
     * [resendWatchMusicLogin].
     */
    fun loadMusicSetup(arm: Boolean = true) {
        viewModelScope.launch {
            try {
                val client = container.client()
                val server = client.musicServer()
                val context = container.appContext
                val offered = if (server.configured) {
                    watchPlaylistsFor(
                        runCatching { client.remotePlaylists() }.getOrDefault(RemotePlaylistList()),
                        WatchMusicPreference.selected(context),
                    )
                } else {
                    WatchPlaylists(emptyList(), WatchMusicPreference.selected(context))
                }
                WatchMusicPreference.setSelected(context, offered.selected)
                updateMusic {
                    it.copy(
                        server = server,
                        watchPlaylists = offered.playlists,
                        watchSelection = offered.selected,
                        watchSelectionPending = WatchMusicPreference.isPending(context),
                        watchSongs = WatchMusicPreference.pickedSongs(context),
                        watchAppInstalled = WatchMusicPreference.isAppOnWatch(context),
                        message = null,
                    )
                }
                // Check the remembered answer against the watch itself, so an
                // app removed on the watch brings the send button back.
                if (_state.value.watch.connection is ConnectionState.Connected &&
                    _state.value.music.installedApps == null
                ) {
                    loadInstalledApps()
                }
                // Keep the phone able to answer the watch's config request even
                // when the app was installed on an earlier run — and, when the
                // server has gone (removed in the web app, say), able to tell
                // the watch so.
                if (!server.configured) signOutWatchMusicConfig() else if (arm) refreshWatchMusicConfig()
            } catch (e: Exception) {
                updateMusic { it.copy(message = e.message) }
            }
        }
    }

    /**
     * The first half of connecting a music server: is there one at this address?
     *
     * The address is resolved by asking each candidate — scheme, default port —
     * to identify itself as a music server, so `music.home` or `10.0.0.5` is
     * enough. [onResult] gets the address that answered, or null and the reason
     * none did. Nothing is saved: the credentials have not been typed yet, and
     * asking for them before anything was known to be listening was the
     * commonest dead end of the old one-form login.
     */
    fun findMusicServer(url: String, onResult: (found: String?, error: String?) -> Unit) {
        stopMusicScan()
        viewModelScope.launch {
            updateMusic { it.copy(busy = true, message = null) }
            val found = runCatching { MusicServerDiscovery.find(MusicServerDiscovery.candidatesFor(url)) }.getOrNull()
            updateMusic { it.copy(busy = false) }
            if (found == null) {
                onResult(null, "No music server answered at ${url.trim()}. Check the address, or search your network.")
            } else {
                onResult(found, null)
            }
        }
    }

    /**
     * The second half: sign in to the server [findMusicServer] found.
     *
     * The credentials go to the Tracks server, which verifies them against the
     * music server before storing anything — so a failure here means "those
     * details do not work", not "saved, will break later". [onResult] gets null
     * on success or the reason.
     */
    fun logInMusicServer(url: String, username: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            updateMusic { it.copy(busy = true, message = null) }
            try {
                container.client().setMusicServer(
                    MusicServerRequest(url = url, username = username.trim(), password = password.ifBlank { null }),
                )
                updateMusic { it.copy(busy = false) }
                onResult(null)
                loadMusicSetup()
            } catch (e: Exception) {
                updateMusic { it.copy(busy = false) }
                onResult(e.message ?: "Could not sign in")
            }
        }
    }

    /**
     * Sweep the phone's network for a music server on a default port.
     *
     * For the address nobody knows: the box in the cupboard. Fills the field
     * (via [MusicUiState.foundServer]) rather than connecting, because the
     * credentials are still to come.
     */
    fun searchLanForMusicServer() {
        // A second press stops it.
        musicScan?.takeIf { it.isActive }?.let { stopMusicScan(); return }
        musicScan = viewModelScope.launch {
            try {
                val subnet = LanScan.subnet(container.appContext)
                if (subnet == null) {
                    updateMusic { it.copy(message = "Connect to Wi-Fi first — there is no network to search.") }
                    return@launch
                }
                val hosts = LanScan.hosts(subnet)
                if (hosts.isEmpty()) {
                    updateMusic { it.copy(message = "This network is too large to search (/${subnet.prefixLength}). Enter the address instead.") }
                    return@launch
                }
                // Its own progress line, not `busy`: the sweep takes a while,
                // and Connect has to stay pressable throughout it.
                updateMusic { it.copy(scan = "Searching ${hosts.size} addresses…", message = null) }
                val found = MusicServerDiscovery.find(
                    MusicServerDiscovery.lanCandidates(hosts),
                    onProgress = { checked, total ->
                        updateMusic { it.copy(scan = "Searching your network… ${checked * 100 / total}%") }
                    },
                )
                updateMusic {
                    it.copy(
                        foundServer = found,
                        message = if (found == null) "No music server answered on this network." else "Found a music server at $found",
                    )
                }
            } finally {
                updateMusic { it.copy(scan = null) }
            }
        }
    }

    private var musicScan: kotlinx.coroutines.Job? = null

    /** See [stopServerScan]: Connect wins over a sweep still running. */
    private fun stopMusicScan() {
        musicScan?.cancel()
        musicScan = null
        updateMusic { it.copy(scan = null) }
    }

    /**
     * Put the music app on the watch, over Bluetooth.
     *
     * The app asks the phone who it belongs to on first run, so the phone is
     * armed with the answer before the bytes go over.
     */
    /**
     * See [WatchManager.stopWifiUploads]. [onDone] gets null on success or the
     * reason it failed — onboarding waits on it before moving on, rather than
     * reading a shared message that an earlier action may have left behind.
     */
    fun stopWatchWifiUploads(onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val failure = when (val outcome = runCatching { container.watch.stopWifiUploads() }
                .getOrElse { WatchManager.Outcome.Failed(it.message ?: "The watch could not be reached") }) {
                is WatchManager.Outcome.Failed -> outcome.reason
                else -> null
            }
            _state.update { it.copy(busy = false) }
            onDone(failure)
        }
    }

    fun installWatchMusicApp() {
        viewModelScope.launch {
            updateMusic { it.copy(busy = true, message = null) }
            try {
                val config = watchConfig()
                    ?: throw IllegalStateException("Connect a music server first")
                val outcome = container.watch.installMusicApp(config)
                when (outcome) {
                    is WatchManager.Outcome.Failed -> updateMusic {
                        it.copy(busy = false, message = outcome.reason)
                    }
                    else -> {
                        WatchMusicPreference.setAppOnWatch(container.appContext, true)
                        updateMusic { it.copy(busy = false, watchAppInstalled = true, message = null) }
                    }
                }
            } catch (e: Exception) {
                updateMusic { it.copy(busy = false, message = e.message ?: "Could not install") }
            }
        }
    }

    /**
     * Re-arm the phone's answer to the watch app's config request.
     *
     * Needed because the answer lives in memory: a phone restart, or a watch
     * app installed during an earlier session, would otherwise leave the watch
     * asking a phone that has forgotten what to say. Called after every change
     * to the music settings — connecting, removing, sending a selection — so
     * the watch can collect the change the next time it opens or syncs.
     *
     * When the credentials cannot be fetched (no network), the answer already
     * armed is left alone: it was right a moment ago, and "could not check" is
     * not "the server is gone" — treating it so would sign the watch out and
     * delete its music.
     */
    private suspend fun refreshWatchMusicConfig() {
        val config = watchConfig() ?: return
        container.watch.setMusicAppConfig(config, config.revision)
        WatchAppConfig.onPlaylistsServed = {
            WatchMusicPreference.setPending(container.appContext, false)
            updateMusic { it.copy(watchSelectionPending = false) }
        }
    }

    /**
     * Tell the watch the phone has no music server. It signs out — and deletes
     * its downloads — only if it last heard from the phone before the server
     * went; see [WatchConfigRevision].
     */
    private fun signOutWatchMusicConfig() {
        val revision = WatchMusicPreference.reconcileRevision(container.appContext, WatchConfigRevision.NO_SERVER)
        container.watch.setMusicAppConfig(null, revision)
    }

    /**
     * The music server credentials plus, while one is pending, the phone's
     * playlist selection, stamped with the revision that describes them. Null
     * when they could not be fetched.
     */
    private suspend fun watchConfig(): WatchAppConfig.Config? {
        val remote = runCatching { container.client().musicWatchConfig() }.getOrNull() ?: return null
        val context = container.appContext
        val pending = WatchMusicPreference.isPending(context)
        val revision = WatchMusicPreference.reconcileRevision(
            context,
            WatchMusicPreference.fingerprint(context, remote.url, remote.username, remote.password),
        )
        return WatchAppConfig.Config(
            serverUrl = remote.url,
            username = remote.username,
            password = remote.password,
            playlists = if (pending) WatchMusicPreference.selected(context).toList() else null,
            songs = if (pending) WatchMusicPreference.pickedSongs(context).map { it.id to it.albumId } else null,
            revision = revision,
        )
    }

    /**
     * Hand the watch app the current login again — after the music server's
     * password changed, say. Arms the answer for its ten minutes; the watch
     * collects it when Tracks Music is next opened.
     */
    fun resendWatchMusicLogin() {
        viewModelScope.launch { refreshWatchMusicConfig() }
    }

    /** Look a song up on the music server, to pick it for the watch. */
    fun searchWatchSongs(query: String) {
        if (query.isBlank()) {
            updateMusic { it.copy(songResults = emptyList()) }
            return
        }
        viewModelScope.launch {
            updateMusic { it.copy(searching = true) }
            try {
                val songs = container.client().searchRemoteSongs(query.trim()).songs
                updateMusic { it.copy(songResults = songs, searching = false) }
            } catch (e: Exception) {
                updateMusic { it.copy(searching = false, message = e.message) }
            }
        }
    }

    /** Pick a single song for the watch, or drop it. Sent with [sendWatchPlaylists]. */
    fun setWatchSong(song: RemoteSong, wanted: Boolean) {
        val context = container.appContext
        val next = WatchMusicPreference.pickedSongs(context).filterNot { it.id == song.id }.toMutableList()
        if (wanted) next.add(WatchMusicPreference.PickedSong(song.id, song.albumId, song.title, song.artist))
        WatchMusicPreference.setPickedSongs(context, next)
        updateMusic { it.copy(watchSongs = next) }
    }

    fun dropWatchSong(id: String) {
        val context = container.appContext
        val next = WatchMusicPreference.pickedSongs(context).filterNot { it.id == id }
        WatchMusicPreference.setPickedSongs(context, next)
        updateMusic { it.copy(watchSongs = next) }
    }

    /** Tick or untick a playlist for the watch. Nothing is sent until [sendWatchPlaylists]. */
    fun setWatchPlaylist(id: String, wanted: Boolean) {
        val context = container.appContext
        val next = WatchMusicPreference.selected(context).toMutableSet()
        if (wanted) next.add(id) else next.remove(id)
        WatchMusicPreference.setSelected(context, next)
        updateMusic { it.copy(watchSelection = next) }
    }

    /**
     * Queue the phone's selection for the watch.
     *
     * The phone cannot push to the watch; the watch collects its configuration
     * whenever the app opens or syncs. So this moves the revision — a selection
     * is a change the watch must take even though the login is the same —
     * arms the answer, and the screen says "open Tracks Music on the watch",
     * which is the truth.
     */
    fun sendWatchPlaylists() {
        viewModelScope.launch {
            WatchMusicPreference.bumpRevision(container.appContext)
            WatchMusicPreference.setPending(container.appContext, true)
            updateMusic { it.copy(watchSelectionPending = true, message = null) }
            refreshWatchMusicConfig()
        }
    }

    /** Forget the music server, and tell the watch app it has gone. */
    fun disconnectMusicServer() {
        viewModelScope.launch {
            updateMusic { it.copy(busy = true, message = null) }
            try {
                container.client().clearMusicServer()
                signOutWatchMusicConfig()
                updateMusic { it.copy(busy = false, server = null, watchPlaylists = emptyList()) }
                loadMusicSetup()
            } catch (e: Exception) {
                updateMusic { it.copy(busy = false, message = e.message) }
            }
        }
    }

    /**
     * Load the Connect IQ apps the watch has installed, for the manage list.
     * Needs the watch connected over Bluetooth.
     */
    fun loadInstalledApps() {
        viewModelScope.launch {
            updateMusic { it.copy(appsBusy = true, message = null) }
            try {
                val apps = container.watch.installedWatchApps()
                updateMusic { it.copy(appsBusy = false, installedApps = apps) }
                noteAppOnWatch(apps)
            } catch (e: Exception) {
                updateMusic { it.copy(appsBusy = false, installedApps = emptyList(), message = e.message ?: "Could not read the watch's apps") }
            }
        }
    }

    /**
     * Remove one app from the watch, then refresh the list. A watch that
     * refuses (the app is open, say) leaves the list unchanged with a note.
     */
    fun deleteWatchApp(app: GarminIntegration.InstalledApp) {
        viewModelScope.launch {
            updateMusic { it.copy(appsBusy = true, message = null) }
            try {
                val ok = container.watch.removeWatchApp(app)
                if (ok) {
                    val apps = container.watch.installedWatchApps()
                    updateMusic { it.copy(appsBusy = false, installedApps = apps) }
                    noteAppOnWatch(apps)
                } else {
                    updateMusic { it.copy(appsBusy = false, message = "The watch would not remove ${app.name} — close it on the watch and try again") }
                }
            } catch (e: Exception) {
                updateMusic { it.copy(appsBusy = false, message = e.message ?: "Could not remove the app") }
            }
        }
    }

    /**
     * Record whether Tracks Music is among [apps]. Only while connected: a
     * disconnected watch lists nothing, which is not the same as not having it.
     */
    private fun noteAppOnWatch(apps: List<GarminIntegration.InstalledApp>) {
        if (_state.value.watch.connection !is ConnectionState.Connected) return
        val onWatch = apps.any { it.isTracksMusic }
        WatchMusicPreference.setAppOnWatch(container.appContext, onWatch)
        updateMusic { it.copy(watchAppInstalled = onWatch) }
    }

    private fun updateMusic(transform: (MusicUiState) -> MusicUiState) {
        _state.update { it.copy(music = transform(it.music)) }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        viewModelScope.launch { awaiting(label, block) }
    }

    /**
     * As [run], for a caller that has to know when it finished — a
     * pull-to-refresh drawing its own indicator, which would otherwise have to
     * guess how long to spin for.
     */
    private suspend fun awaiting(label: String, block: suspend () -> Unit) {
        run {
            _state.update { current -> current.copy(busy = true, message = null) }
            try {
                block()
            } catch (e: VaultLockedException) {
                _state.update { current -> current.copy(
                    message = "Your data is locked. Sign in again to unlock it.",
                ) }
            } catch (e: NotAuthenticatedException) {
                _state.update { current -> current.copy(message = "Sign-in required.") }
            } catch (e: WrongPasswordException) {
                _state.update { current -> current.copy(message = "That is not your current password.") }
            } catch (e: ServerNotFoundException) {
                _state.update { current -> current.copy(message = e.message) }
            } catch (e: IncompatibleServerException) {
                _state.update { current -> current.copy(message = e.message) }
            } catch (e: Exception) {
                _state.update { current -> current.copy(message = "$label failed: ${e.message}") }
            } finally {
                _state.update { current -> current.copy(busy = false) }
            }
        }
    }

    /**
     * Sync the watch and come back when it is done.
     *
     * The pull gesture on the dashboard and the health page. It is the *watch*
     * it syncs with, not the server, and deliberately so: those two pages are
     * about what your body did, every figure on them originates in a file the
     * watch is holding, and on the trips this app is for the watch is the half
     * that is actually reachable. The server catches up on its own.
     */
    suspend fun syncWatchFromGesture() = awaiting("Syncing watch") { syncWatchNow() }
}

