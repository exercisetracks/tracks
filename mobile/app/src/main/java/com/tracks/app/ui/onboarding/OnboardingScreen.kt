// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tracks.app.AppContainer
import com.tracks.app.MainViewModel
import com.tracks.app.MusicUiState
import com.tracks.app.OnboardingProgress
import com.tracks.app.device.BluetoothPermissions
import com.tracks.app.feeds.FeedPermissions
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PasswordField
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.profile.rememberRestoreFlow
import com.tracks.app.ui.profile.BodyForm
import com.tracks.app.ui.profile.ChoiceCard
import com.tracks.app.ui.profile.FrequencyForm
import com.tracks.app.ui.profile.LookForm
import com.tracks.app.ui.profile.ProfileViewModel
import com.tracks.app.ui.profile.SetField
import com.tracks.app.ui.profile.StrengthForm
import com.tracks.app.ui.screens.ServerConnectForm
import com.tracks.app.ui.profile.ZonesForm
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.SessionState
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * First run, which forks on its first screen: this phone alone, or a server.
 *
 * ## Two paths, because there are two kinds of user
 *
 * **Standalone** asks what the web's Setup asks — body, zones, strength, look —
 * because on this path the phone is the only place those answers can come
 * from. Then one short screen on what choosing no server means, since that is a
 * privacy trade the person should make knowingly, and the grants.
 *
 * **Server** asks only what the phone alone can know: which server, and which
 * grants. The account already has a profile; asking again would offer a second
 * place to set the same value differently. Its sign-in is still the step that
 * matters: it is the one moment the app is guaranteed a live vault, and it uses
 * it to enrol a device key and register a sync agent — after which watch sync
 * works with no session and no signal for as long as the user is out.
 *
 * Either path can take up the other later, from Settings. Pairing a watch is
 * offered but skippable — it wants the watch in hand and out of its charger,
 * which is a poor bet during setup, and Settings asks again at any time.
 */
@Composable
fun OnboardingScreen(
    vm: MainViewModel,
    /** For the profile forms and a restore — shared with Settings. */
    container: AppContainer,
    bluetooth: BluetoothPermissions,
    feeds: FeedPermissions,
    /** Shared with the Watch tab — the companion dialog needs the Activity. */
    onPair: () -> Unit,
    /** Records whether the user owns a watch at all — see [DeviceStep]. */
    onHasDevice: (Boolean) -> Unit,
    onFinished: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profileVm: ProfileViewModel = viewModel(
        key = "onboarding-profile",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(container) as T
        },
    )
    val profile by profileVm.state.collectAsStateWithLifecycle()
    val set: SetField = { field, value -> profileVm.set(field, value) }

    // Saveable, not remembered: a rotation or a process death partway through
    // would otherwise drop the user back to the welcome screen with their
    // server URL typed and their session already established. The profile
    // answers themselves need no saving — each is written to the settings row
    // as it is made.
    //
    // Saveable is not enough on its own, though: closing the app discards it.
    // So the step and path also persist in the container (see
    // AppContainer.onboardingProgress) and are the starting point here.
    val saved = remember { container.onboardingProgress() }
    var step by rememberSaveable {
        mutableStateOf(resumeStep(saved?.step, signedIn = state.session is SessionState.Active))
    }
    // Only used to word the last screen. The answer itself goes straight to
    // the container, because Settings has to be able to read it later.
    var hasDevice by rememberSaveable { mutableStateOf(saved?.hasDevice ?: true) }
    var standalone by rememberSaveable { mutableStateOf(saved?.standalone ?: false) }
    // A restored backup already holds the profile, so its steps are skipped.
    var restored by rememberSaveable { mutableStateOf(saved?.restored ?: false) }
    // A server account that has not been through setup gets the profile steps
    // after sign-in (UserSettings.setupComplete). Not persisted: a reopened
    // app resuming at one of those steps keeps them by being on one.
    var askProfile by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step, standalone, restored, hasDevice) {
        container.saveOnboardingProgress(
            OnboardingProgress(step.name, standalone = standalone, restored = restored, hasDevice = hasDevice),
        )
    }
    // "Restore from a backup" goes straight to the file picker. It once opened
    // a dialog holding the whole Settings backup section — Back up now,
    // Restore, Close — which only added a step before the same button.
    val restore = rememberRestoreFlow(container) {
        standalone = true
        restored = true
        profileVm.reload()
        step = OnboardingStep.Device
    }

    val path = onboardingPath(
        standalone = standalone, restored = restored, hasDevice = hasDevice,
        askProfile = askProfile || step in PROFILE_STEPS,
    )
    // Back walks the path rather than leaving the app. Not back into sign-in
    // once the session is live, though: that would offer a second sign-in over
    // one that has already bound this phone to an account.
    val previous = path.getOrNull(path.indexOf(step) - 1)
    val canGoBack = previous != null &&
        !(!standalone && previous in setOf(OnboardingStep.Welcome, OnboardingStep.Server) &&
            state.session is SessionState.Active)
    fun next() { path.getOrNull(path.indexOf(step) + 1)?.let { step = it } }
    fun back() { if (canGoBack) step = previous!! }

    BackHandler(enabled = canGoBack) { back() }

    // Advance out of sign-in the moment the session goes live, rather than on
    // the button press: login also enrols a device key and registers a sync
    // agent, and moving on before those land would show "you're all set" over
    // an app that cannot yet upload.
    //
    // Also after sign-in has *finished* (not busy), because the session goes
    // live a moment before the account check runs — and that check may sign
    // straight back out, refusing a phone that holds another account's data.
    LaunchedEffect(state.session, state.busy) {
        if (step == OnboardingStep.Server && state.session is SessionState.Active &&
            !state.busy && state.accountConflict == null && state.serverRestore == null
        ) {
            // The account's own settings arrive with the first sync.
            profileVm.reload()
            // Asked of the server directly: the first pull may not have
            // landed yet, and an unreachable answer reads as done.
            val done = runCatching { container.client().userSettings().setupComplete }.getOrDefault(true)
            if (!done) {
                askProfile = true
                profileVm.set("timezone", ZoneId.systemDefault().id)
                step = OnboardingStep.Body
            } else {
                step = OnboardingStep.Device
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        // Edge-to-edge: without this the progress bar and title sat under the
        // status bar, and the last button under the gesture bar.
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            LinearProgressIndicator(
                progress = { (path.indexOf(step).coerceAtLeast(0) + 1f) / path.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(Tokens.Space.s6),
                verticalArrangement = Arrangement.spacedBy(Tokens.Space.s5),
            ) {
                when (step) {
                    OnboardingStep.Welcome -> WelcomeStep(
                        onServer = { standalone = false; restored = false; step = OnboardingStep.Server },
                        onStandalone = {
                            standalone = true
                            restored = false
                            // Written now, not at the end: the plan's day
                            // boundaries read it, and this first write is what
                            // creates the settings row the forms then edit.
                            profileVm.set("timezone", ZoneId.systemDefault().id)
                            step = OnboardingStep.Body
                        },
                        onRestore = restore.start,
                        restoring = restore.working,
                        restoreStatus = restore.status,
                    )
                    OnboardingStep.Body -> ProfileStep(
                        "About you",
                        "Used for calories, zones and the body model. All of it can be changed later in Settings.",
                        onBack = ::back, onNext = ::next,
                        // Read from the view model, not `profile`: the age
                        // committed by Continue's own focus change has not
                        // recomposed into `profile` yet.
                        missing = {
                            if (profileVm.state.value.num("birth_year") == null) "Enter your age to continue." else null
                        },
                    ) { if (profile.loaded) BodyForm(profile, set) }
                    OnboardingStep.Zones -> ProfileStep(
                        "Heart rate & power",
                        "Leave these on Auto and Tracks works them out from your recordings.",
                        onBack = ::back, onNext = ::next,
                    ) { ZonesForm(profile, set) }
                    OnboardingStep.Strength -> ProfileStep(
                        "Strength",
                        "What you can lift with. Strength sessions only use what is ticked.",
                        onBack = ::back, onNext = ::next,
                    ) { StrengthForm(profile, set) }
                    OnboardingStep.Habits -> ProfileStep(
                        "How often you train",
                        "Sets where each sport's first plan starts, and how hard strength begins, until Tracks has your own history. Skip any you don't do.",
                        onBack = ::back, onNext = ::next,
                    ) { FrequencyForm(profile, set) }
                    OnboardingStep.Look -> ProfileStep(
                        "Look",
                        "Shared with Tracks on the web if you connect a server later.",
                        onBack = ::back, onNext = ::next,
                    ) { LookForm(profile, set) }
                    OnboardingStep.Privacy -> PrivacyStep(onBack = ::back, onNext = ::next)
                    OnboardingStep.Server -> ServerStep(
                        serverUrl = state.serverUrl,
                        busy = state.busy,
                        scan = state.scan,
                        connectedTo = state.capabilities?.let { "${it.app} ${it.serverVersion}" },
                        message = state.message,
                        onConnect = vm::setServerUrl,
                        onSearch = vm::searchLan,
                        onLogin = vm::login,
                        onBack = ::back,
                    )
                    OnboardingStep.Device -> DeviceStep(
                        onAnswer = { owns ->
                            hasDevice = owns
                            onHasDevice(owns)
                            // Permissions follow either answer; what they ask
                            // for depends on it.
                            step = OnboardingStep.Permissions
                        },
                    )
                    OnboardingStep.Permissions -> PermissionsStep(
                        bluetooth = bluetooth,
                        feeds = feeds,
                        hasDevice = hasDevice,
                        onNext = ::next,
                    )
                    OnboardingStep.Watch -> WatchStep(
                        pairedName = state.watch.pairedName,
                        busy = state.busy,
                        message = state.message,
                        onPair = onPair,
                        onNext = ::next,
                    )
                    OnboardingStep.Uploads -> WatchUploadsStep(
                        paired = state.watch.pairedName != null,
                        initiallyBlocked = com.tracks.app.device.WatchPrivacyPreference.blocksWifiUploads(context),
                        onApply = { block, done ->
                            com.tracks.app.device.WatchPrivacyPreference.setBlocksWifiUploads(context, block)
                            if (block) vm.stopWatchWifiUploads(done) else done(null)
                        },
                        onNext = ::next,
                    )
                    OnboardingStep.Weather -> WeatherStep(onNext = ::next)
                    OnboardingStep.Music -> MusicStep(
                        music = state.music,
                        watchPaired = state.watch.pairedName != null,
                        onLoad = vm::loadMusicSetup,
                        onConnect = vm::connectMusicServer,
                        onSearch = vm::searchLanForMusicServer,
                        onPlaylist = vm::setWatchPlaylist,
                        onSendPlaylists = vm::sendWatchPlaylists,
                        onInstall = vm::installWatchMusicApp,
                        onNext = ::next,
                    )
                    OnboardingStep.Done -> DoneStep(
                        pairedName = state.watch.pairedName,
                        hasDevice = hasDevice,
                        standalone = standalone,
                        onFinish = onFinished,
                    )
                }
            }
        }
    }
}

/**
 * Where to reopen onboarding. The saved step, except that a phone already
 * signed in never goes back to the server or sign-in screens: the session is
 * live and bound, and offering a second sign-in over it is the one thing the
 * back button here already refuses to do.
 */
internal fun resumeStep(saved: String?, signedIn: Boolean): OnboardingStep {
    // "SignIn" was its own step until it joined the server's.
    val name = if (saved == "SignIn") OnboardingStep.Server.name else saved
    val step = name?.let { n -> OnboardingStep.entries.firstOrNull { it.name == n } }
        ?: OnboardingStep.Welcome
    return if (signedIn && step in setOf(OnboardingStep.Welcome, OnboardingStep.Server)) {
        OnboardingStep.Device
    } else {
        step
    }
}

internal enum class OnboardingStep { Welcome, Body, Zones, Strength, Habits, Look, Privacy, Server, Device, Permissions, Watch, Uploads, Weather, Music, Done }

/**
 * The steps this person will walk, in order — the progress bar's denominator
 * and what Back and Continue move along.
 *
 * Standalone asks what the desktop's Setup asks, because on this path the
 * phone is the only place those answers can come from. The server path skips
 * it when the account already has a profile — asking again would offer a
 * second place to set the same value differently — and asks it ([askProfile])
 * for an account that never went through setup, so age and how often you
 * train are never left unasked. Music needs a music server
 * behind the watch app, so only the server path has it (docs/offline-first.md).
 * A restored backup carries its profile, so it skips the profile steps too.
 *
 * The watch question comes before the permissions, because it decides them:
 * Bluetooth, the calendar and notification access are only for a watch, and
 * asking a phone-only user for them is asking for access to nothing.
 */
internal fun onboardingPath(
    standalone: Boolean,
    restored: Boolean,
    hasDevice: Boolean,
    askProfile: Boolean = false,
): List<OnboardingStep> {
    val start = when {
        !standalone -> listOf(OnboardingStep.Welcome, OnboardingStep.Server) +
            if (askProfile) PROFILE_STEPS else emptyList()
        restored -> listOf(OnboardingStep.Welcome)
        else -> listOf(OnboardingStep.Welcome) + PROFILE_STEPS + OnboardingStep.Privacy
    }
    val watch = when {
        !hasDevice -> emptyList()
        standalone -> listOf(OnboardingStep.Watch, OnboardingStep.Uploads, OnboardingStep.Weather)
        else -> listOf(OnboardingStep.Watch, OnboardingStep.Uploads, OnboardingStep.Weather, OnboardingStep.Music)
    }
    return start + listOf(OnboardingStep.Device, OnboardingStep.Permissions) + watch + OnboardingStep.Done
}

/** The profile questions, in order — what the web's Setup asks. */
internal val PROFILE_STEPS = listOf(
    OnboardingStep.Body, OnboardingStep.Zones, OnboardingStep.Strength,
    OnboardingStep.Habits, OnboardingStep.Look,
)

// ── Steps ────────────────────────────────────────────────────────────────────

/**
 * The fork: a server, or this phone alone.
 *
 * Standalone is a full way to use Tracks, not a trial — the phone parses its
 * watch's files, computes everything, and keeps it all encrypted here (see
 * docs/offline-first.md). What it gives up is said plainly and briefly: map
 * basemaps come from a server, and a server is also the backup and the more
 * private home for years of health data. A server can be added later from
 * Settings, and this phone's data merges into it.
 *
 * This is groundwork; the onboarding redesign replaces this screen.
 */
@Composable
internal fun WelcomeStep(
    onServer: () -> Unit,
    onStandalone: () -> Unit,
    onRestore: () -> Unit,
    restoring: Boolean = false,
    restoreStatus: String? = null,
) {
    StepHeading(
        "Welcome to Tracks",
        "Your training, health and plans — on this phone, and on your own server if you run one.",
    )
    ChoiceCard(
        title = "Use on this phone",
        body = "Everything works offline: activities, health, plans, strength and flexibility. " +
            "Your data stays here, encrypted.",
        selected = false,
        onClick = onStandalone,
    )
    ChoiceCard(
        title = "Connect to my server",
        body = "Sign in to your self-hosted Tracks server to sync with it and the web app.",
        selected = false,
        onClick = onServer,
    )
    TonalButton("Restore from a backup", onClick = onRestore, enabled = !restoring)
    restoreStatus?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

/**
 * One profile step: heading, the shared form, and Back / Continue.
 *
 * [missing] names what the step still needs, or null when it can be left.
 * It is asked only once Continue has taken focus off the form, because the
 * forms write a typed number when its field loses focus: a button disabled
 * until the value was stored would stay disabled under someone who typed
 * their age and went straight for Continue.
 */
@Composable
internal fun ProfileStep(
    title: String,
    body: String,
    onBack: () -> Unit,
    onNext: () -> Unit,
    missing: () -> String? = { null },
    form: @Composable () -> Unit,
) {
    val focus = LocalFocusManager.current
    var blocked by remember { mutableStateOf<String?>(null) }
    StepHeading(title, body)
    form()
    blocked?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        NeutralButton("Back", onClick = onBack, modifier = Modifier.weight(1f))
        PrimaryButton(
            "Continue",
            onClick = {
                focus.clearFocus()
                blocked = missing()
                if (blocked == null) onNext()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * What choosing no server means, said once and briefly — the point is an
 * informed choice, not a sales pitch, and the user asked for it concise.
 */
@Composable
internal fun PrivacyStep(onBack: () -> Unit, onNext: () -> Unit) {
    StepHeading("Your data", "Everything you record stays on this phone, encrypted.")
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        Bullet("A self-hosted Tracks server keeps everything on hardware you control and adds maps — link one any time in Settings.")
        Bullet("Without one, map basemaps are blank; your tracks and places still draw.")
        Bullet("Back up from Settings: without a server, this phone is the only copy.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        NeutralButton("Back", onClick = onBack, modifier = Modifier.weight(1f))
        PrimaryButton("Continue", onClick = onNext, modifier = Modifier.weight(1f))
    }
}

/**
 * The server and the sign-in, as one step: the address first, and the
 * username and password only once it has answered ([ServerConnectForm], the
 * same flow as Settings' "Connect server"). Sign-in moves on by itself when
 * the session goes live (the LaunchedEffect above).
 */
@Composable
private fun ServerStep(
    serverUrl: String,
    busy: Boolean,
    scan: String?,
    connectedTo: String?,
    message: String?,
    onConnect: (String) -> Unit,
    onSearch: () -> Unit,
    onLogin: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    StepHeading(
        "Your server",
        "Tracks is self-hosted. If it is running on this network the app can find it; " +
            "otherwise enter the address you use in a browser.",
    )
    ServerConnectForm(
        serverUrl = serverUrl,
        busy = busy,
        scan = scan,
        connectedTo = connectedTo,
        message = message,
        onConnect = onConnect,
        onSearch = onSearch,
        onLogin = onLogin,
    )
    NeutralButton("Back", onClick = onBack)
}

/**
 * Ask for the grants, one row at a time, with the reason next to each.
 *
 * Rows re-read their state on every resume — three of the four are answered
 * outside this app, in a system dialog or another Activity entirely, and the
 * screen has no other way to learn the answer. Getting this wrong is what made
 * the feeds look broken before: the user granted something, came back, and the
 * app still said it was off.
 */
@Composable
private fun PermissionsStep(
    bluetooth: BluetoothPermissions,
    feeds: FeedPermissions,
    hasDevice: Boolean,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requester = remember(bluetooth, feeds) { GrantRequester(context, bluetooth, feeds) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var nonce by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nonce++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grants = grantsFor(hasDevice)
    val granted = remember(nonce, hasDevice) { grants.associate { it.key to it.isGranted(context) } }
    PermissionsList(
        grants = grants,
        granted = granted,
        onRequest = { grant -> scope.launch { grant.request(requester); nonce++ } },
        onNext = onNext,
    )
}

/** The permissions step's content, apart from the Activity machinery — so it can be drawn in a test. */
@Composable
internal fun PermissionsList(
    grants: List<Grant>,
    granted: Map<String, Boolean>,
    onRequest: (Grant) -> Unit,
    onNext: () -> Unit,
) {
    val requiredMet = grants.filter { it.required }.all { granted[it.key] == true }

    StepHeading("Permissions", "Each one is for one feature. Change them any time in Settings.")

    grants.forEach { grant ->
        GrantRow(
            grant = grant,
            granted = granted[grant.key] == true,
            onRequest = { onRequest(grant) },
        )
    }

    PrimaryButton("Continue", onClick = onNext, modifier = Modifier.fillMaxWidth(), enabled = requiredMet)

    if (!requiredMet) {
        Text(
            "Bluetooth is the one Tracks cannot work without — it is how the app " +
                "reaches your watch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ask whether there is a watch, before assuming there is one.
 *
 * This app was built watch-first, which for a long time was indistinguishable
 * from being built for its users. It no longer is. The phone records runs, runs
 * strength and mobility sessions, holds the offline map and plans routes on it —
 * someone who owns none of Garmin's hardware can use all of that. Walking them
 * through a pairing screen, and then showing them sync controls and
 * "your watch has not synced in three days" notices forever, does not merely
 * waste their time: it reads as an app that is broken for them.
 *
 * One question, two answers, no skip link. A skip would be a third answer
 * meaning "I did not read this", and the whole point is to get a real one.
 */
@Composable
internal fun DeviceStep(onAnswer: (Boolean) -> Unit) {
    StepHeading(
        "Do you have a watch?",
        "Tracks syncs with Garmin watches over Bluetooth. It also works on its " +
            "own — the phone can record runs, guide strength and mobility " +
            "sessions, and hold maps for offline use.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton("Yes, I have a Garmin watch", onClick = { onAnswer(true) }, modifier = Modifier.fillMaxWidth())
        TonalButton("No — just my phone", onClick = { onAnswer(false) }, modifier = Modifier.fillMaxWidth())
    }
    Text(
        // Said plainly, because the honest reason people hesitate on a question
        // like this is fear of picking the door that locks.
        "You can change this later in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Pair the watch, here rather than later.
 *
 * This step was originally left out, on the reasoning that pairing wants the
 * watch awake and off its charger and that is a poor bet during setup. That
 * was the wrong call: watch sync is the reason this app exists, and an
 * onboarding that never mentions the watch leaves its single most important
 * capability behind a tab the user has no particular reason to visit. Someone
 * whose watch is genuinely not to hand can skip in one tap — which is a much
 * smaller cost than never being told the feature is there.
 *
 * The prerequisites are stated up front, because all three are things only the
 * user can fix and each produces the same symptom — an empty picker — that
 * looks like the app is broken.
 */
@Composable
private fun WatchStep(
    pairedName: String?,
    busy: Boolean,
    message: String?,
    onPair: () -> Unit,
    onNext: () -> Unit,
) {
    StepHeading(
        "Your watch",
        "Tracks talks to your watch over Bluetooth to pull activities and push " +
            "planned workouts — no cable, and no Garmin account.",
    )

    if (pairedName != null) {
        StatusCard(good = true, text = "Paired with $pairedName.")
        PrimaryButton("Continue", onClick = onNext, modifier = Modifier.fillMaxWidth())
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Bullet("Wake the watch and take it off the charger")
        Bullet("Keep it within arm's reach of the phone")
        // The one that surprises people, and the one no amount of retrying
        // fixes: a Garmin holds a single companion pairing, so it has to be
        // unpaired from Garmin Connect before it will answer this app.
        Bullet("Unpair it from Garmin Connect first — a watch will only talk to one phone app")
    }
    Spacer(Modifier.height(4.dp))
    PrimaryButton(if (busy) "Looking…" else "Pair watch", onClick = onPair, modifier = Modifier.fillMaxWidth(), enabled = !busy)
    if (message != null && !busy) StatusCard(good = false, text = message)
    NeutralButton("Skip — I'll do this later", onClick = onNext)
}

/**
 * The watch's own Wi-Fi upload, which no phone app can see.
 *
 * A Garmin that was ever set up through Garmin Connect keeps that account's
 * credentials and the Wi-Fi networks it was given, and with Auto Upload on it
 * sends every activity straight to Garmin the moment it is saved on a known
 * network — with or without Garmin Connect installed, with or without a phone
 * nearby. That is the user's GPS tracks and heart rate on a server they may
 * believe they left, which is the reason this step exists at all: Tracks is
 * the alternative to that copy, and cannot prevent it.
 *
 * It also marks the activity synced on the watch, which hides it from the
 * listing Tracks reads first. Tracks recovers such files (see the recovery
 * listing in FileSyncServiceHandler), so this is not what keeps activities
 * arriving; it is what keeps them private. Found 2026-09-30: a run absent from
 * Tracks for an hour turned out to be on Garmin's servers 17 seconds after it
 * was saved, from a watch paired with Garmin Connect a month earlier for
 * protocol captures.
 *
 * Both parts are needed. Auto Upload off stops the uploads, and the toggle
 * here does that over Bluetooth (WatchManager.stopWifiUploads, verified on a
 * fenix 6X) — on by default, with a confirmation to turn it off, because the
 * cost of the wrong default is a person's location history on a server they
 * thought they had left. Removing the watch from the Garmin account revokes the
 * credentials it would use if it were turned back on, and only its owner can
 * do that, at connect.garmin.com.
 */
@Composable
internal fun WatchUploadsStep(
    paired: Boolean,
    initiallyBlocked: Boolean,
    /** Save the choice and, when blocking, tell the watch; reports null or why it failed. */
    onApply: (block: Boolean, done: (String?) -> Unit) -> Unit,
    onNext: () -> Unit,
) {
    var block by rememberSaveable { mutableStateOf(initiallyBlocked) }
    var confirmingOff by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    StepHeading(
        "Keep activities off Garmin's servers",
        "A watch that was ever set up with Garmin Connect can upload every " +
            "activity to Garmin by itself over Wi-Fi — even with Garmin Connect " +
            "uninstalled and the phone switched off.",
    )
    com.tracks.app.ui.profile.ToggleRow(
        "Block Wi-Fi uploads to Garmin",
        "Tracks turns the watch's Auto Upload off, and turns it off again if it " +
            "finds an activity the watch has already sent.",
        block,
    ) { wanted -> if (wanted) block = true else confirmingOff = true }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The half no Bluetooth message can do: the watch keeps its Garmin
        // login until the account forgets the watch.
        Bullet("At connect.garmin.com, remove the watch from your devices, so it no longer holds your Garmin account")
        Bullet("Activities already uploaded stay on Garmin until you delete them there")
    }
    failure?.let { StatusCard(good = false, text = "Could not reach the watch: $it. You can change this later by pairing again.") }
    Spacer(Modifier.height(4.dp))
    PrimaryButton(
        if (applying) "Telling the watch…" else if (failure != null) "Continue anyway" else "Continue",
        enabled = !applying,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (failure != null || !paired) {
                onApply(block) { }
                onNext()
                return@PrimaryButton
            }
            applying = true
            onApply(block) { reason ->
                applying = false
                if (reason == null) onNext() else failure = reason
            }
        },
    )

    if (confirmingOff) {
        AlertDialog(
            onDismissRequest = { confirmingOff = false },
            title = { Text("Allow uploads to Garmin?") },
            text = {
                Text(
                    "If this watch has Garmin Wi-Fi uploads set up, every activity you " +
                        "record — GPS tracks, heart rate, where you live and train — " +
                        "will be copied to Garmin's servers as soon as you save it.",
                )
            },
            confirmButton = {
                TextButton(onClick = { block = false; confirmingOff = false }) { Text("Allow uploads") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingOff = false }) { Text("Keep blocked") }
            },
        )
    }
}

/**
 * Weather on the watch, from a weather app on the phone.
 *
 * Modelled on Gadgetbridge's own weather help, because the arrangement is the
 * same and so is the thing people get stuck on: the watch app does not fetch
 * weather, a weather app has to be told to send it. So this says which apps
 * can, recommends Breezy Weather (free software, worldwide), and — when one is
 * already installed — skips the shopping and says exactly where its switch is.
 *
 * The status line is the point of the step: it is re-read every time the
 * person comes back from the weather app, so they see "Receiving forecasts"
 * the moment it works rather than trusting that it will.
 */
@Composable
internal fun WeatherStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var nonce by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) nonce++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val sources = com.tracks.app.feeds.WeatherSources
    val installed = remember(nonce) { sources.installed(context) }
    val status = remember(nonce) {
        sources.status(installed, sources.history(context), System.currentTimeMillis()) {
            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(it))
        }
    }
    var showOthers by rememberSaveable { mutableStateOf(false) }

    StepHeading(
        "Weather on your watch",
        "Tracks doesn't fetch weather itself. A weather app on your phone sends its " +
            "forecast, the same way it would to Gadgetbridge, and Tracks passes it to the watch.",
    )
    StatusCard(good = status.ok, text = status.text)

    if (installed.isNotEmpty()) {
        installed.forEach { provider ->
            Bullet(provider.enableSteps)
            NeutralButton("Open ${provider.name}", onClick = { sources.open(context, provider) })
        }
    } else {
        val breezy = sources.PROVIDERS.first { it.recommended }
        Text("Recommended: ${breezy.name}", style = MaterialTheme.typography.titleSmall)
        Text(breezy.blurb, style = MaterialTheme.typography.bodyMedium)
        breezy.downloads.forEach { (store, url) ->
            NeutralButton("Get it on $store", onClick = { sources.openLink(context, url) })
        }
        Bullet("Then: ${breezy.enableSteps}")
        TextButton(onClick = { showOthers = !showOthers }) {
            Text(if (showOthers) "Hide other apps" else "Other apps that can send weather")
        }
        if (showOthers) {
            sources.PROVIDERS.filterNot { it.recommended }.forEach { provider ->
                Text(provider.name, style = MaterialTheme.typography.titleSmall)
                Text(provider.blurb, style = MaterialTheme.typography.bodySmall)
                provider.downloads.forEach { (store, url) ->
                    NeutralButton("Get it on $store", onClick = { sources.openLink(context, url) })
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    PrimaryButton(if (status.ok) "Continue" else "Continue without weather", onClick = onNext, modifier = Modifier.fillMaxWidth())
}

/**
 * Music: the one place two features share a watch and must not be confused.
 *
 * A Garmin can hold music two ways, and they do not mix. Files pushed over USB
 * land in the watch's own library and play under "My Music". Audio the Tracks
 * watch app downloads lands in that app's encrypted store and plays there. The
 * same song loaded both ways is on the watch twice, and a user who does not
 * know that will go looking for their playlist in the wrong player and conclude
 * the sync is broken.
 *
 * So the difference is stated first, before either option is offered, rather
 * than in help text somebody reads afterwards.
 *
 * Everything here is skippable. Music is the least essential thing Tracks does,
 * and this step arrives late in a flow the user is already tired of.
 */
@Composable
private fun MusicStep(
    music: MusicUiState,
    watchPaired: Boolean,
    onLoad: () -> Unit,
    onConnect: (String, String, String) -> Unit,
    onSearch: () -> Unit,
    onPlaylist: (String, Boolean) -> Unit,
    onSendPlaylists: () -> Unit,
    onInstall: () -> Unit,
    onNext: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }

    var url by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val connected = music.server?.configured == true

    StepHeading(
        "Music",
        "Optional. Tracks can put music on your watch without a cable and " +
            "without a Garmin account.",
    )

    // The distinction, up front and unavoidable.
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Two separate libraries",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Set up here, the Tracks Music watch app downloads playlists from " +
                    "your music server over the watch's own Wi-Fi — on the charger, " +
                    "with no phone involved — and plays them in its own player.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Music you push over USB from the web app plays under the watch's " +
                    "own \"My Music\" instead. The two do not see each other — a song " +
                    "loaded both ways is on the watch twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ── 1. Where the music comes from ────────────────────────────────────────
    Text(
        "Your music server",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (connected) {
        StatusCard(good = true, text = "Connected to ${music.server?.url}.")
    } else {
        Text(
            "Navidrome. The watch signs in to it directly, so the address has to " +
                "be one the watch can reach over Wi-Fi, with a real certificate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LaunchedEffect(music.foundServer) { music.foundServer?.let { url = it } }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Music server address") },
            placeholder = { Text("music.example.com or 10.0.0.5") },
            supportingText = { Text("http/https and the usual ports are tried for you") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TonalButton(if (music.scan != null) "Stop searching" else "Find it on my network", onClick = onSearch, modifier = Modifier.fillMaxWidth(), enabled = !music.busy)
        music.scan?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(if (music.busy) "Checking…" else "Connect music server", onClick = { onConnect(url, username, password) }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy && url.isNotBlank() && username.isNotBlank())
        if (music.busy && music.message != null) {
            Text(music.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // ── 2. The player itself ─────────────────────────────────────────────────
    Text(
        "The watch app",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (!watchPaired) {
        Text(
            "Pair a watch first and this becomes available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Sent straight from this phone over Bluetooth — it never goes near " +
                "the Connect IQ store. On first run it asks this phone for the " +
                "music server and signs in by itself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(if (music.busy) "Sending…" else "Send music app to watch", onClick = onInstall, modifier = Modifier.fillMaxWidth(), enabled = !music.busy && connected)
        if (music.watchAppInstalled) {
            StatusCard(
                good = true,
                text = "Sent. The app may not show in the watch's file list — " +
                    "media apps are stored hidden, which is normal. Find it under " +
                    "Music › Music Providers, pick playlists, and sync.",
            )
        }
    }

    // ── 3. What the watch keeps (optional) ───────────────────────────────────
    if (connected && watchPaired && music.watchPlaylists.isNotEmpty()) {
        Text(
            "Playlists on the watch",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Optional — the watch has the same list on its own screen. " +
                "\"Liked songs\" and \"Recently played\" follow what you listen to.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (pl in music.watchPlaylists) {
                TonalButton(if (pl.id in music.watchSelection) "✓ ${pl.name}" else pl.name, onClick = { onPlaylist(pl.id, pl.id !in music.watchSelection) }, modifier = Modifier.fillMaxWidth(), enabled = !music.busy)
            }
        }
        PrimaryButton("Send this selection to the watch", onClick = onSendPlaylists, modifier = Modifier.fillMaxWidth(), enabled = !music.busy && music.watchSelection.isNotEmpty())
        if (music.watchSelectionPending) {
            StatusCard(
                good = true,
                text = "Queued. The watch picks it up when you open Tracks Music on the watch.",
            )
        }
    }

    if (music.message != null && !music.busy) StatusCard(good = false, text = music.message)

    Spacer(Modifier.height(4.dp))
    NeutralButton(if (connected || music.watchAppInstalled) "Continue" else "Skip — no music for now", onClick = onNext, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun DoneStep(pairedName: String?, hasDevice: Boolean, standalone: Boolean, onFinish: () -> Unit) {
    StepHeading(
        "You're set",
        when {
            standalone -> "Everything runs on this phone."
            hasDevice -> "Tracks syncs in the background from here on."
            else -> "Your server and this phone are linked."
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        // A phone-only user is pointed at what they *can* do rather than at the
        // thing they have just said they do not own.
        if (!hasDevice) {
            Bullet("Start a planned run from the dashboard — GPS, splits and spoken cues")
            Bullet("Strength and Flexibility hold your library and guide a session")
        } else if (pairedName == null) {
            Bullet("Pair your watch any time from Settings")
        } else {
            Bullet("Your watch syncs on its own — sync now from Settings")
        }
        if (standalone) Bullet("Set a goal under Training and the plan is built on this phone")
        else Bullet("Open Maps once while you have signal — it caches the basemap for offline use")
        Bullet("Anything you turned down is listed in Settings, with a way to turn it on")
    }
    Spacer(Modifier.height(Tokens.Space.s1))
    PrimaryButton("Open Tracks", onClick = onFinish, modifier = Modifier.fillMaxWidth())
}

// ── Pieces ───────────────────────────────────────────────────────────────────

@Composable
private fun StepHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp),
        ) {
            Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GrantRow(grant: Grant, granted: Boolean, onRequest: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    Modifier.size(8.dp),
                    shape = CircleShape,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                ) {}
                Text(
                    grant.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!grant.required) {
                    Text(
                        "Optional",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                grant.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                TonalButton("Allow", onClick = onRequest)
            }
        }
    }
}

@Composable
private fun StatusCard(good: Boolean, text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (good) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Text(
            text,
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
            color = if (good) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
