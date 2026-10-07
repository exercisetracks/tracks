// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.app.Application
import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.tracks.core.api.SessionState
import com.tracks.core.api.TracksClient
import com.tracks.core.api.UserSettingsUpdate
import com.tracks.app.ui.theme.Accent
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.core.platform.AndroidTokenStore
import com.tracks.core.platform.EncryptedDatabase
import com.tracks.core.replica.AccountGate
import com.tracks.core.replica.LinkResult
import com.tracks.core.replica.ReplicaStore
import java.security.SecureRandom
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.asKotlinRandom
import com.tracks.core.crypto.BouncyCastleIngestCrypto
import com.tracks.core.crypto.IngestCrypto
import com.tracks.core.sync.WatchUploader
import com.tracks.app.device.WatchLinkPreference
import com.tracks.app.device.WatchManager
import com.tracks.app.device.WatchSyncRunner
import kotlinx.coroutines.flow.MutableStateFlow
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/**
 * Wiring, done by hand.
 *
 * There is no dependency-injection framework here and there does not need to
 * be: the graph is four objects deep and every one of them is a singleton for
 * the life of the process. A DI library would add build time and indirection to
 * solve a problem this app does not have yet. Revisit when the device layer
 * lands and the graph actually branches.
 */
class TracksApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The vendored Gadgetbridge code reaches for a Context through its own
        // application singleton. Handing it ours here is the whole of that
        // wiring — and it must happen before anything touches the device layer,
        // which is why it is the first line rather than part of AppContainer.
        //
        // Found on device: the shim's guard threw "GBApplication.init() was
        // never called" the first time a watch sync was attempted. It failing
        // loudly rather than NPE-ing three frames deeper is the reason that took
        // one run to diagnose instead of an afternoon.
        GBApplication.init(this)
        raiseMapTileConcurrency()
        container = AppContainer(this)
        // Enqueued unconditionally: the worker itself decides whether there is
        // anything to do, and KEEP means re-running this on every launch does
        // not reset the schedule (which would stop it ever firing).
        SyncWorker.schedule(this)
        WatchSyncScheduler.schedule(this)
        // Watching for plan edits needs no Activity and no permission; it only
        // ever *acts* when a watch is paired and Bluetooth is granted.
        container.smartWatchSync.start()
        com.tracks.app.backup.BackupReminderWorker.schedule(this)
        // Cheap, and the recovery path for the two cases the boot receiver does
        // not cover: an app updated while the device was awake, and a process
        // killed hard enough to lose an alarm the system had not yet fired.
        com.tracks.app.meds.MedicationReminders.rearmAll(this)
        // The phone's time zone is the account's; see TimezoneSync.
        TimezoneReceiver { container.syncTimezoneSoon() }.register(this)
        // Activities imported before the parser learned a field get it from
        // their stored file; nothing to do once every summary is current.
        container.backfillActivitySummaries()
        // The watch link is deliberately *not* started here. This runs before
        // any Activity exists, so the process is still cached and Android 12+
        // rejects the foreground-service start outright — see the note on
        // MainActivity.onStart, which is where it starts instead.
    }

    /**
     * Let MapLibre's own tile downloader use more than five connections to
     * one host at a time.
     *
     * ## Why this exists
     *
     * MapLibre's native offline downloader ([org.maplibre.android.module.http.HttpRequestImpl])
     * talks to a `Call.Factory` this SDK builds for itself unless told
     * otherwise, and that default `OkHttpClient`'s `Dispatcher` caps
     * `maxRequestsPerHost` at OkHttp's own default of 5 — a sane politeness
     * limit for a third-party tile provider somebody else operates, and a
     * hard ceiling on throughput against a server this app's own user runs.
     * A region download's tiles all come from the *same* host, so five
     * requests in flight was the whole bottleneck: on a LAN capable of tens
     * of megabytes a second, a download of small tiles crept along at
     * roughly one, because nothing was ever fetching more than five of them
     * at once no matter how fast each one came back.
     *
     * It is also the reason a download looked like it "hung and then
     * burst": the tile pyramid — throttled by this cap — is what a region's
     * progress bar tracks, but the routing/POI/DEM data that follows it (see
     * [com.tracks.app.ui.map.RegionsViewModel.storeOnPhone]) goes out over
     * this app's own Ktor client instead, an entirely separate `OkHttpClient`
     * with no such cap, and finishes at the LAN's real speed the moment the
     * throttled part is done.
     *
     * Called once, here, before anything else could touch the network and
     * build the default client first. MapLibre's own docs describe
     * [HttpRequestUtil.setOkHttpClient] as the supported way to supply one —
     * but [org.maplibre.android.module.http.HttpRequestImpl]'s static
     * initialiser reaches back into [MapLibre] to identify itself in the
     * User-Agent it sends, and throws if that has never been configured. So
     * [MapLibre.getInstance] has to run first even here, before any
     * `MapView` exists to normally trigger it — found by shipping this
     * without it and crashing on every cold start.
     */
    private fun raiseMapTileConcurrency() {
        MapLibre.getInstance(this)
        val dispatcher = Dispatcher().apply {
            maxRequests = 48
            maxRequestsPerHost = 24
        }
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().dispatcher(dispatcher).build())
    }
}

class AppContainer(private val context: Context) {

    /** For the few callers that need a Context (WorkManager, mostly). */
    val appContext: Context get() = context.applicationContext

    /**
     * This build's `versionName` and `versionCode`, read from the installed
     * package rather than BuildConfig (which this module does not generate).
     */
    val appVersion: Pair<String, Long> by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        (info.versionName ?: "unknown") to PackageInfoCompat.getLongVersionCode(info)
    }

    /**
     * Surfaced so the UI can react to the session going stale without every
     * screen having to interpret HTTP status codes. The client pushes into it
     * as it recovers, or fails to.
     */
    val sessionState = MutableStateFlow<SessionState>(SessionState.LoggedOut)

    val tokens = AndroidTokenStore(context)

    /** What the user typed. A self-hosted app has no default to guess. */
    val serverUrl = MutableStateFlow(loadServerUrl())

    /**
     * Where the API actually turned out to be — usually `<origin>/api` behind
     * the bundled Caddy, sometimes the origin itself when the backend is
     * exposed directly. Discovered once (see discoverApiBase) and remembered,
     * so the user never has to know which shape their deployment is.
     */
    val apiBase = MutableStateFlow(loadApiBase())

    /**
     * One driver for the whole database. The old mirror and the replica are
     * two views of the same SQLCipher file, and two drivers on one file would
     * be two connections racing each other's transactions.
     */
    private val database by lazy { EncryptedDatabase.driver(context) }

    /**
     * The phone's synced rows (see com.tracks.core.replica): everything a
     * person said, read and written by every screen through [sources], and
     * the half of the phone a backup must carry.
     */
    val replica: ReplicaStore by lazy {
        ReplicaStore(database, now = System::currentTimeMillis, random = SecureRandom().asKotlinRandom())
    }

    /** What this phone derived from its FIT files, and the ids screens use. See `Local.sq`. */
    val library: com.tracks.core.local.LocalLibrary by lazy { com.tracks.core.local.LocalLibrary(database, com.tracks.core.time.ZoneOffsets::of) }

    /** What a person said, as the API's models, read and written through the replica. */
    val sources: com.tracks.core.local.LocalSources by lazy { com.tracks.core.local.LocalSources(replica, library) }

    /**
     * The one door every FIT file enters by — from the watch, from the server's
     * history, or recorded on this phone — stored encrypted and imported in the
     * same call. Also the sync engine's blob store.
     */
    val files: com.tracks.core.local.LocalFiles by lazy {
        val importer = com.tracks.core.local.LocalImporter(
            library,
            thresholds = { importThresholds() },
            isDeleted = { uid -> replica.row("activity", uid)?.isTombstone == true },
            onActivity = { uid, startedAt, done -> matching.match(uid, startedAt, done) },
        )
        com.tracks.core.local.LocalFiles(blobs, library, importer)
    }

    /**
     * Bring activities imported by an older build up to date: newer parser
     * fields ([com.tracks.core.local.LocalImporter.backfillSummaries]) and
     * matches made by the UTC day ([com.tracks.core.local.LocalMatching.repairUtcDayMatches]).
     * Off the main thread: it can parse files.
     */
    fun backfillActivitySummaries() {
        signalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { files.backfillSummaries() }
                .onSuccess { if (it > 0) android.util.Log.i("TracksImport", "filled newer summary fields into $it activities") }
                .onFailure { android.util.Log.w("TracksImport", "summary backfill failed", it) }
            // Same once-per-launch, no-op-when-current shape: see repairUtcDayMatches.
            runCatching {
                matching.repairUtcDayMatches(
                    library.activityRows().mapNotNull { a ->
                        com.tracks.core.local.LocalMatching.Held(
                            a.uid, a.started_at ?: return@mapNotNull null,
                            com.tracks.core.plan.PlanAssembly.DoneActivity(a.sport, a.distance_meters, a.duration_seconds),
                        )
                    },
                )
            }
                .onSuccess { if (it > 0) android.util.Log.i("TracksImport", "moved $it workout match(es) off the UTC day") }
                .onFailure { android.util.Log.w("TracksImport", "match repair failed", it) }
        }
    }

    /** The FIT files themselves, sealed under a Keystore key. Shared by [files] and backups. */
    private val blobs: com.tracks.app.local.AndroidBlobs by lazy { com.tracks.app.local.AndroidBlobs(context) }

    // ── Backup ───────────────────────────────────────────────────────────────

    private val backup by lazy {
        com.tracks.core.backup.BackupService(replica, blobs, files, now = System::currentTimeMillis)
    }

    /**
     * A page a notification asked to open (see MainActivity.routeFrom), taken
     * and cleared by the nav host. A flow rather than a start destination so it
     * also works when the app is already running.
     */
    val pendingRoute = MutableStateFlow<String?>(null)

    /**
     * A race goal (uid) waiting for a course drawn on the map: the race plan's
     * "Draw on map" sets it, and the map's next saved track becomes that
     * race's course, clears it, and returns to Race Plans.
     */
    val pendingRaceCourse = MutableStateFlow<String?>(null)

    /** When this phone last wrote a backup, or null — drives the reminder. */
    val lastBackupAt = MutableStateFlow(prefs().getLong(KEY_LAST_BACKUP, 0L).takeIf { it > 0 })

    private val _backupProgress = MutableStateFlow<com.tracks.app.backup.BackupProgress?>(null)

    /** The backup being written, or null — the pill, the notification and Settings all watch it. */
    val backupProgress: kotlinx.coroutines.flow.StateFlow<com.tracks.app.backup.BackupProgress?> = _backupProgress

    private val _backupResult = MutableStateFlow<String?>(null)

    /** How the last backup this run of the app wrote ended, in words for Settings. */
    val backupResult: kotlinx.coroutines.flow.StateFlow<String?> = _backupResult

    /**
     * Owns a backup while it runs. Not the screen's scope: leaving Settings,
     * or the app, used to cancel the coroutine and with it the backup. This
     * one lives as long as the process, which [com.tracks.app.backup.BackupWriteService]
     * keeps alive until the backup is done.
     */
    private val backupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * Start writing a backup to [uri] and return at once; [backupProgress]
     * and [backupResult] report it. Takes ownership of [passphrase] and wipes
     * it when done. A second start while one runs is refused (false).
     */
    fun startBackup(uri: android.net.Uri, passphrase: CharArray): Boolean {
        if (!_backupProgress.compareAndSet(null, com.tracks.app.backup.BackupProgress(0, null))) {
            passphrase.fill(' ')
            return false
        }
        _backupResult.value = null
        com.tracks.app.backup.BackupWriteService.start(context)
        backupScope.launch {
            _backupResult.value = try {
                writeBackup(uri, passphrase)
                "Backup written."
            } catch (e: Throwable) {
                android.util.Log.w("TracksBackup", "backup failed", e)
                "Could not write the backup: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                passphrase.fill(' ')
                _backupProgress.value = null
            }
        }
        return true
    }

    /**
     * Write a passphrase-sealed backup to [uri] (a document the user picked
     * through the Storage Access Framework, so it can live on a USB stick or a
     * cloud folder of their choosing — never somewhere this app decides).
     *
     * Streamed: one FIT file in memory at a time, however long the history.
     * On any failure the document is removed (see [com.tracks.app.backup.writeOrDiscard]).
     */
    private suspend fun writeBackup(uri: android.net.Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
        com.tracks.app.backup.writeOrDiscard(
            open = { context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file.") },
            // Blocking, not discardBackup: after a cancellation a suspending
            // call would throw before deleting anything.
            discard = { discardBackupNow(uri) },
        ) { file ->
            val sealed = com.tracks.app.backup.BackupCrypto.sealing(file, passphrase)
            backup.write({ bytes, offset, length -> sealed.write(bytes, offset, length) }) { done, total ->
                _backupProgress.value = com.tracks.app.backup.BackupProgress(done, total)
            }
            sealed.finish()
        }
        markBackedUp()
    }

    /**
     * Remove a backup document that never got its backup: the picker created
     * it, and then the write failed or the passphrase was cancelled. Not every
     * provider can delete; one that cannot is at least emptied, so what stays
     * is plainly not a backup rather than a cut-off one that looks whole.
     */
    suspend fun discardBackup(uri: android.net.Uri) = withContext(Dispatchers.IO) { discardBackupNow(uri) }

    private fun discardBackupNow(uri: android.net.Uri) {
        val deleted = runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .getOrDefault(false)
        if (!deleted) runCatching { context.contentResolver.openOutputStream(uri, "wt")?.close() }
    }

    /**
     * Restore a backup from [uri]; returns how many FIT files were restored.
     * Throws on a wrong passphrase or a damaged file — before changing anything,
     * because the file is read through once to check it before it is applied
     * (see [com.tracks.core.backup.BackupService.verify]).
     */
    suspend fun restoreBackup(uri: android.net.Uri, passphrase: CharArray): Int = withContext(Dispatchers.IO) {
        suspend fun <T> reading(block: suspend (com.tracks.core.backup.ByteSource) -> T): T {
            val file = context.contentResolver.openInputStream(uri) ?: error("Could not open the file.")
            return com.tracks.app.backup.BackupCrypto.opening(file.buffered(), passphrase).use { plain ->
                block { into, offset, length -> plain.read(into, offset, length) }
            }
        }
        reading { backup.verify(it) }
        val restored = reading { backup.restore(it) }
        refreshPreferences()
        // The phone now holds exactly what that file holds, so the file is a
        // current backup of it: without this, a fresh install restored from a
        // backup is told at once that it has none and should make one.
        markBackedUp()
        restored
    }

    private fun markBackedUp() {
        val at = System.currentTimeMillis()
        prefs().edit().putLong(KEY_LAST_BACKUP, at).apply()
        lastBackupAt.value = at
    }

    /** Ticks off the planned workout each imported activity satisfies. */
    private val matching: com.tracks.core.local.LocalMatching by lazy { com.tracks.core.local.LocalMatching(replica, com.tracks.core.time.ZoneOffsets::of) }

    /** TSS thresholds for import, from the settings row. See [com.tracks.core.local.LocalSources.importThresholds]. */
    suspend fun importThresholds(): com.tracks.core.local.ImportThresholds = sources.importThresholds()

    /** One activity, read from its parsed file with the person's edits over it. */
    val activityDetail: com.tracks.core.local.LocalActivityDetail by lazy { com.tracks.core.local.LocalActivityDetail(library, sources) }

    /** Saved tracks and places — the map's sources. */
    val mapItems: com.tracks.core.local.LocalMapItems by lazy { com.tracks.core.local.LocalMapItems(sources) }

    /** Strength and mobility: libraries, saved workouts and flows, sessions, standings. */
    val training: com.tracks.core.local.LocalTraining by lazy { com.tracks.core.local.LocalTraining(sources) }

    /** The dashboard's and health page's endpoints, answered on the phone. */
    val metrics: com.tracks.core.local.LocalMetrics by lazy { com.tracks.core.local.LocalMetrics(library, sources) }

    /** Whether this phone has a server to sync with at all. Standalone is a normal state. */
    suspend fun isLinked(): Boolean = serverUrl.value.isNotBlank() && tokens.load().isAuthenticated

    /**
     * One sync with the server: push, pull, and — when [withHistory] — the FIT
     * files this phone does not have yet, which [files] imports as they land.
     * A no-op, not an error, for a standalone phone.
     */
    suspend fun syncWithServer(withHistory: Boolean): com.tracks.core.replica.ReplicaSyncEngine.Report? {
        if (!isLinked()) return null
        val engine = com.tracks.core.replica.ReplicaSyncEngine(
            replica,
            com.tracks.core.replica.HttpSyncTransport(client()),
            blobs = if (withHistory) files else null,
            onProgress = { _syncProgress.value = it },
        )
        try {
            val report = engine.sync()
            // A recreated server has none of this phone's files either;
            // uploadFiles sees its new server id and re-sends them.
            sources.changed()
            if (report.wiped) library.clear()
            uploadFiles()
            return report
        } finally {
            _syncProgress.value = null
        }
    }

    private val _syncProgress = kotlinx.coroutines.flow.MutableStateFlow<com.tracks.core.replica.SyncProgress?>(null)

    /**
     * How far the running sync has got, or null when none is running — what
     * the bottom progress popup shows during a first sync or a long catch-up.
     */
    val syncProgress: kotlinx.coroutines.flow.StateFlow<com.tracks.core.replica.SyncProgress?> = _syncProgress

    /**
     * Send the server every FIT file it has not had from this phone, through the
     * sealed ingest — which needs no unlocked vault, so this works on a locked
     * session. Best effort: whatever fails stays queued for the next run.
     *
     * In batches when the server can take them ([com.tracks.core.sync.BulkUpload]
     * says why that matters), and file by file, as before, when it is older.
     */
    suspend fun uploadFiles(): Int {
        if (!isLinked()) return 0
        // Null before the first pull from a server, and on one too old to
        // speak the sync protocol; neither can have moved the phone elsewhere.
        replica.server()?.let { library.uploadsGoTo(it.serverId) }
        val pending = library.notUploaded()
        if (pending.isEmpty()) return 0
        val uploader = watchUploader() ?: return 0
        fun progress(done: Int, total: Int) {
            _syncProgress.value = com.tracks.core.replica.SyncProgress(
                com.tracks.core.replica.SyncProgress.Step.Uploading, done, total,
            )
        }
        try {
            progress(0, pending.size)
            val caps = runCatching { client().capabilities() }.getOrNull()
            if (caps?.supports("sync_ingest_batch") == true) {
                var sent = 0
                val started = System.currentTimeMillis()
                runCatching {
                    com.tracks.core.sync.BulkUpload(uploader, files::get, caps.limits).send(
                        pending,
                        onDelivered = { library.markUploaded(it); sent += it.size },
                        onProgress = ::progress,
                    )
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.w("TracksUpload", "batch upload stopped", it)
                }
                android.util.Log.i(
                    "TracksUpload",
                    "$sent of ${pending.size} file(s) on the server after ${System.currentTimeMillis() - started} ms",
                )
                return sent
            }
            var sent = 0
            for ((i, sha) in pending.withIndex()) {
                progress(i, pending.size)
                val bytes = files.get(sha) ?: continue
                val ok = runCatching { uploader.upload("$sha.fit", bytes) }.isSuccess
                if (!ok) break
                library.markUploaded(sha)
                sent++
            }
            return sent
        } finally {
            // Called on its own too, not only as the last step of a sync.
            _syncProgress.value = null
        }
    }

    /**
     * Whether first-run setup has been walked through.
     *
     * ## Why this is not just a boolean read
     *
     * The obvious version — `flag || tokens.isAuthenticated` — shipped and was
     * wrong in a way that undid the entire point of the flow. Signing in is a
     * *step inside* onboarding, so the moment it succeeded the fallback started
     * answering "already onboarded", and the next Activity recreation threw the
     * user out of the wizard and onto the dashboard with the permission and
     * pairing steps never shown. Caught on a real device: the permissions step
     * simply never appeared, which is precisely the silent ungranted-feed
     * failure this app has already been bitten by once.
     *
     * So the session fallback is treated as what it actually is — a one-time
     * **migration** for installs that predate onboarding — and it is consulted
     * only at the instant the flow would first be shown. After that the flow is
     * latched: started means started, and only [completeOnboarding] ends it.
     *
     * Not cleared on logout, deliberately: signing out is not a factory reset,
     * and the server URL survives it. Settings already offers a sign-in card,
     * which is all a returning user needs.
     */
    suspend fun onboardingComplete(): Boolean {
        val prefs = prefs()
        if (prefs.getBoolean(KEY_ONBOARDED, false)) return true
        // Already in the flow. Never re-consult the session here — that is the
        // bug above.
        if (prefs.getBoolean(KEY_ONBOARD_STARTED, false)) return false

        if (tokens.load().isAuthenticated) {
            // Predates onboarding: configured, signed in, nothing to ask.
            completeOnboarding()
            return true
        }
        prefs.edit().putBoolean(KEY_ONBOARD_STARTED, true).apply()
        return false
    }

    /**
     * Where onboarding had got to, so closing the app mid-way resumes there.
     *
     * Saved state (rememberSaveable) only survives the system killing the
     * process; a user closing the app — which they will, if it misbehaves —
     * throws it away, and they were sent back to the welcome screen after
     * already signing in. So the step and the path choices live in prefs too,
     * written as they change and cleared by [completeOnboarding]. Null when
     * nothing was saved.
     */
    fun onboardingProgress(): OnboardingProgress? {
        val prefs = prefs()
        val step = prefs.getString(KEY_ONBOARD_STEP, null) ?: return null
        return OnboardingProgress(
            step = step,
            standalone = prefs.getBoolean(KEY_ONBOARD_STANDALONE, false),
            restored = prefs.getBoolean(KEY_ONBOARD_RESTORED, false),
            hasDevice = prefs.getBoolean(KEY_ONBOARD_HAS_DEVICE, true),
        )
    }

    fun saveOnboardingProgress(progress: OnboardingProgress) {
        prefs().edit()
            .putString(KEY_ONBOARD_STEP, progress.step)
            .putBoolean(KEY_ONBOARD_STANDALONE, progress.standalone)
            .putBoolean(KEY_ONBOARD_RESTORED, progress.restored)
            .putBoolean(KEY_ONBOARD_HAS_DEVICE, progress.hasDevice)
            .apply()
    }

    fun completeOnboarding() {
        prefs().edit()
            .putBoolean(KEY_ONBOARDED, true)
            .remove(KEY_ONBOARD_STEP).remove(KEY_ONBOARD_STANDALONE)
            .remove(KEY_ONBOARD_RESTORED).remove(KEY_ONBOARD_HAS_DEVICE)
            .apply()
        // The account's settings row, created here for a standalone phone —
        // one that signed in already has it from its first pull, and writing
        // the same field again is a harmless stamped edit.
        signalScope.launch { runCatching { sources.writeSetting("setup_complete", true) } }
    }

    /**
     * Whether the user has a watch at all.
     *
     * The app was built watch-first, and for a while that was the same thing as
     * being built for its users. It no longer is: the phone records runs, runs
     * strength and mobility sessions, and holds the offline map, and someone who
     * owns none of Garmin's hardware can use every one of those. For them the
     * pairing prompts, the sync controls and the "your watch has not synced in
     * three days" notices are not merely useless — they read as a broken app.
     *
     * Defaults to true, which is the migration-safe answer: every existing
     * install got here by pairing a watch, and a default of false would hide
     * their sync controls the moment they updated.
     */
    val hasDevice = MutableStateFlow(prefs().getBoolean(KEY_HAS_DEVICE, true))

    fun setHasDevice(value: Boolean) {
        prefs().edit().putBoolean(KEY_HAS_DEVICE, value).apply()
        hasDevice.value = value
    }

    // ── Appearance and units ─────────────────────────────────────────────────
    //
    // All three are the account's (spec/sync.yaml `settings`): the user asked
    // for light/dark to follow them between phone and web like the accent and
    // units do. "System" remains a choice for anyone who wants each device to
    // follow its own OS.
    //
    // All three are cached in preferences regardless, because the first frame
    // has to be painted before the database is open — and on a phone with no
    // signal, it is the only answer there will be.

    val themeMode = MutableStateFlow(ThemeMode.of(prefs().getString(KEY_THEME, null)))

    val accent = MutableStateFlow(Accent.of(prefs().getString(KEY_ACCENT, null)))

    val imperial = MutableStateFlow(prefs().getBoolean(KEY_IMPERIAL, false))
        .also { com.tracks.core.format.Units.imperial = it.value }

    /**
     * Theme mode, accent and units are account settings (spec/sync.yaml,
     * `settings`), so a choice made on the web reaches the phone and the other
     * way round. SharedPreferences keeps a copy only so the first frame after a
     * cold start is already the right colour, before the database is opened.
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        prefs().edit().putString(KEY_THEME, mode.key).apply()
        themeMode.value = mode
        writeSetting("theme_mode", mode.key)
    }

    suspend fun setAccent(value: Accent) {
        prefs().edit().putString(KEY_ACCENT, value.key).apply()
        accent.value = value
        writeSetting("accent_color", value.key)
    }

    suspend fun setImperial(value: Boolean) {
        prefs().edit().putBoolean(KEY_IMPERIAL, value).apply()
        imperial.value = value
        com.tracks.core.format.Units.imperial = value
        writeSetting("units", if (value) "imperial" else "metric")
    }

    /** One field of the account's settings row, which exists once per account. */
    suspend fun writeSetting(field: String, value: String?) = sources.writeSetting(field, value)

    /**
     * Take the settings row's answer for the preferences it owns — after a
     * pull brought someone's change from another device. A field never set
     * leaves the phone's own choice alone rather than resetting it to a
     * default nobody picked.
     */
    suspend fun refreshPreferences() {
        suspend fun str(f: String) = sources.setting(f)
        str("theme_mode")?.let { key ->
            val mode = ThemeMode.of(key)
            prefs().edit().putString(KEY_THEME, mode.key).apply()
            themeMode.value = mode
        }
        str("accent_color")?.let { key ->
            val resolved = Accent.of(key)
            prefs().edit().putString(KEY_ACCENT, resolved.key).apply()
            accent.value = resolved
        }
        str("units")?.let { units ->
            val imp = units == "imperial"
            prefs().edit().putBoolean(KEY_IMPERIAL, imp).apply()
            imperial.value = imp
            com.tracks.core.format.Units.imperial = imp
        }
        // Runs at startup and after every pull — the second is what writes the
        // zone on a phone whose settings row only arrived with its first pull.
        syncTimezone()
        syncWeatherLocation()
    }

    /**
     * Write the phone's time zone to the account if it differs — see
     * [TimezoneSync] for when, and why there is no field for it on the phone.
     */
    suspend fun syncTimezone(phone: String = java.time.ZoneId.systemDefault().id) {
        runCatching {
            val row = sources.settingValues()
            TimezoneSync.zoneToWrite(row["timezone"] as? String, phone, rowExists = row.isNotEmpty())
                ?.let { sources.writeSetting("timezone", it) }
        }
    }

    /** [syncTimezone] from a callback that cannot suspend: a broadcast, a resume. */
    fun syncTimezoneSoon() {
        signalScope.launch { syncTimezone() }
    }

    /**
     * Store where the phone is in the synced `weather_location`, or clear it
     * when Weather is off — see [WeatherLocationSync]. Returns the position to
     * forecast for afterwards: the stored one, freshly updated if the phone
     * had a newer fix. Null when Weather is off or nothing is known.
     */
    internal suspend fun syncWeatherLocation(): WeatherLocationSync.Fix? = runCatching {
        val row = sources.settingValues()
        val enabled = when (val v = row["weather_enabled"]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: true
            else -> true
        }
        val stored = WeatherLocationSync.stored(row["weather_location"])
        val phone = if (enabled) WeatherLocationSync.phone(context) else null
        when (val change = WeatherLocationSync.change(stored, phone, enabled, rowExists = row.isNotEmpty())) {
            is WeatherLocationSync.Change.Set -> sources.writeSetting("weather_location", change.value)
            WeatherLocationSync.Change.Clear -> sources.writeSetting("weather_location", null)
            null -> Unit
        }
        when {
            !enabled -> null
            phone != null && (stored == null || phone.at.isAfter(stored.at)) -> phone
            else -> stored
        }
    }.getOrNull()

    /** [syncWeatherLocation] from a resume, which cannot suspend. */
    fun syncWeatherLocationSoon() {
        signalScope.launch { syncWeatherLocation() }
    }

    /**
     * The watch. Lazy because constructing it touches the Bluetooth and
     * companion-device system services, and a phone with the app installed but
     * no watch paired should not pay for that on every cold start.
     */
    val watch: WatchManager by lazy { WatchManager(context.applicationContext) }

    /**
     * "Something the phone holds has changed." One instance, watched by the
     * page-level view models, written by whatever reads a watch file. See
     * [com.tracks.app.device.LocalDataSignal].
     */
    val localData = com.tracks.app.device.LocalDataSignal()

    /**
     * Derived data and synced rows each count their own changes; screens
     * watch one signal. Folded here, once, rather than in every view model.
     */
    private val signalScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    init {
        signalScope.launch {
            // At most one refresh per SIGNAL_INTERVAL_MS, the last change
            // always delivered. A first sync imports thousands of files, each a
            // change; passed through one by one, every open page reloaded per
            // file — thousands of overlapping reloads — which is what made
            // pages take seconds to open and the app stop responding. Conflate
            // keeps only the newest pending change while the delay runs, so a
            // burst becomes a steady trickle and its end is never missed.
            kotlinx.coroutines.flow.merge(library.version, sources.version)
                .conflate()
                .collect {
                    localData.changed()
                    kotlinx.coroutines.delay(SIGNAL_INTERVAL_MS)
                }
        }
    }

    private var pendingRebuild: kotlinx.coroutines.Job? = null

    /**
     * A setting the plan is built from changed (PlanStaleness.PLAN_SETTINGS):
     * rebuild the active plan, as the server does for the same change —
     * there is no Regenerate button to press. Debounced, because a threshold
     * typed digit by digit is five writes, and each rebuild reshuffles the
     * strength and stretch picks; the last value is the one that counts.
     */
    fun planInputsChanged() {
        pendingRebuild?.cancel()
        pendingRebuild = signalScope.launch {
            kotlinx.coroutines.delay(PLAN_REBUILD_DEBOUNCE_MS)
            val today = java.time.LocalDate.now()
            runCatching {
                com.tracks.core.local.LocalPlanning(sources, library).refreshActive(
                    com.tracks.core.fit.decode.CivilDate(today.year, today.monthValue, today.dayOfMonth),
                    System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * One runner, one mutex, shared by every caller of a full watch sync — the
     * UI button and the background service alike. See [WatchSyncRunner] for
     * why a second instance here would defeat the point of having one.
     */
    val watchSync: WatchSyncRunner by lazy { WatchSyncRunner(this) }

    /**
     * Syncs the watch on the first open each morning and shortly after the
     * plan changes. Shares [signalScope] — it watches the same write signal
     * that scope fans out, and lives exactly as long as the process.
     */
    val smartWatchSync: com.tracks.app.device.SmartWatchSync by lazy {
        com.tracks.app.device.SmartWatchSync(this, signalScope)
    }

    /**
     * The phone's own tile store. Lazy for the same reason the watch is:
     * touching it opens MapLibre's offline database, which a user who never
     * downloads an area should not pay for.
     */
    private val tiles: com.tracks.app.map.OfflineTiles by lazy {
        com.tracks.app.map.OfflineTiles(context)
    }

    fun offlineTiles(): com.tracks.app.map.OfflineTiles = tiles

    /**
     * The phone's routing data, and the engine that reads it.
     *
     * Separate from the tile store because they answer different questions —
     * see [com.tracks.app.map.OfflineRoutingData]. Both lazy for the same
     * reason: a user who never downloads an area should pay for neither.
     */
    private val routingData: com.tracks.app.map.OfflineRoutingData by lazy {
        com.tracks.app.map.OfflineRoutingData(context)
    }

    fun offlineRoutingData(): com.tracks.app.map.OfflineRoutingData = routingData

    private val router: com.tracks.app.map.OfflineRouter by lazy {
        com.tracks.app.map.OfflineRouter(routingData)
    }

    fun offlineRouter(): com.tracks.app.map.OfflineRouter = router

    /**
     * The phone's own gazetteer, for places inside a downloaded region. Lazy
     * for the same reason [tiles]/[routingData] are: a user who never
     * downloads a region should not pay for opening this file.
     */
    private val offlinePoi: com.tracks.app.map.OfflinePoiData by lazy {
        com.tracks.app.map.OfflinePoiData(context)
    }

    fun offlinePoiData(): com.tracks.app.map.OfflinePoiData = offlinePoi

    /** Point elevation with no server to ask. Lazy for the same reason as [offlinePoi]. */
    private val dem: com.tracks.app.map.OfflineDem by lazy {
        com.tracks.app.map.OfflineDem(context)
    }

    fun offlineDem(): com.tracks.app.map.OfflineDem = dem

    /**
     * Forecasts, asked of Open-Meteo directly rather than through the server —
     * see [com.tracks.core.weather.OpenMeteo] for why and what it sends. Lazy:
     * most sessions never open a forecast.
     */
    val openMeteo: com.tracks.core.weather.OpenMeteo by lazy { com.tracks.core.weather.OpenMeteo() }

    /**
     * Whether the Weather privacy switch allows a forecast request. Read from
     * the synced settings row on every call, not cached, so turning it off on
     * the web or here stops the next request rather than the next launch. A
     * row that never set it gets the server's default, on.
     *
     * Security: this is the only thing standing between a tap on the map and a
     * coordinate leaving the phone, so every [openMeteo] call goes through it.
     */
    suspend fun weatherAllowed(): Boolean =
        when (val v = runCatching { sources.settingValues() }.getOrNull()?.get("weather_enabled")) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull() ?: true
            else -> true
        }

    val crypto: IngestCrypto = BouncyCastleIngestCrypto()

    /**
     * The map style, kept on disk so the Map tab opens with no signal.
     *
     * App-private storage and plain on purpose: a style document is public
     * cartography — layer definitions, colours, tile URLs — with nothing about
     * the user in it. The encrypted mirror is for the user's own data, and
     * putting a 200 KB public document through SQLCipher would buy nothing.
     */
    fun mapStyleCache(): MapStyleCache = MapStyleCache(context.applicationContext)

    /**
     * The uploader, or null when this phone has no agent token and cannot get
     * one right now.
     *
     * Null rather than throwing, because "not registered" is an ordinary state:
     * a user who has not signed in has no agent token, and watch sync should
     * say so rather than crash.
     *
     * The retry matters more than it looks. Registration normally happens at
     * sign-in — see [registerSyncAgent] — but anyone who signed in before that
     * existed, or whose registration call failed at the time, holds a perfectly
     * good session and no agent token, and nothing would ever try again. On
     * device that presented as a completed sync reporting "347 file(s) pulled,
     * but this phone is not registered with the server yet — sign in once to
     * finish setup", to a user who *had* signed in and had no way to act on the
     * advice short of signing out.
     *
     * This does not weaken the argument for registering at sign-in: that is
     * still where it should happen, because it is the one moment a live session
     * is guaranteed. This is the recovery path, and it fails quietly back to
     * null when there is no session or no signal.
     */
    suspend fun watchUploader(): WatchUploader? {
        val token = tokens.load().agentToken
            ?: runCatching { registerSyncAgent(android.os.Build.MODEL) }
                .fold({ tokens.load().agentToken }, { null })
            ?: return null
        return WatchUploader(client(), crypto, token)
    }

    /**
     * Register this phone as a sync agent, once.
     *
     * Called at sign-in because that is the only moment the agent path needs a
     * session — after this, uploads work with no session at all, which is the
     * entire point. Doing it lazily at first watch sync would put the one
     * network call that requires authentication in the one place most likely to
     * have neither signal nor a live session.
     */
    suspend fun registerSyncAgent(label: String) {
        val existing = tokens.load()
        if (existing.agentToken != null) return
        val agent = client().registerSyncAgent(label)
        tokens.save(existing.copy(agentToken = agent.token))
    }

    private var cachedClient: Pair<String, TracksClient>? = null

    /**
     * Rebuilt when the server URL changes, and only then. Ktor clients own a
     * connection pool and a thread pool, so one per call would leak both.
     */
    fun client(): TracksClient {
        val url = apiBase.value.ifBlank { serverUrl.value }
        cachedClient?.let { (cachedUrl, client) -> if (cachedUrl == url) return client }
        cachedClient?.second?.close()
        val fresh = TracksClient(
            baseUrl = url,
            tokens = tokens,
            onSessionState = { sessionState.value = it },
            clientVersion = appVersion.let { (name, code) -> "android/$name ($code)" },
        )
        cachedClient = url to fresh
        return fresh
    }

    fun setServerUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs().edit().putString(KEY_SERVER, cleaned).apply()
        serverUrl.value = cleaned
        // The old base belonged to the old origin.
        setApiBase("")
    }

    fun setApiBase(base: String) {
        prefs().edit().putString(KEY_API_BASE, base).apply()
        apiBase.value = base
    }

    /**
     * Sign out: forget the session, keep the data.
     *
     * This used to delete the database, on the reasoning that the next person
     * to hold the phone should not read the last one's history. That was right
     * for a cache of a server and is wrong now: a phone may hold the *only*
     * copy of what it recorded standalone, and signing out to switch servers
     * would have destroyed it. The protection moved to sign-in instead —
     * [checkAccount] refuses anyone but the account this data belongs to — and
     * the database stays encrypted under a key only this app can read.
     * Deleting the data is [eraseLocalData], asked for explicitly.
     */
    suspend fun logout() {
        client().logout()
    }

    /**
     * Right after a password sign-in: is this the account this phone's data
     * belongs to? On [LinkResult.OtherAccount] the session has already been
     * dropped again, and the UI offers [eraseLocalData].
     */
    suspend fun checkAccount(): LinkResult = AccountGate(client(), replica).afterSignIn()

    /** Yes to [LinkResult.DifferentServer]: this phone's data goes to the new server. */
    suspend fun restoreInto(question: LinkResult.DifferentServer) =
        AccountGate(client(), replica).restore(question)

    /** No to [LinkResult.DifferentServer]: drop the session, keep everything else. */
    suspend fun declineNewServer() = AccountGate(client(), replica).decline()

    /**
     * Erase this phone's data — the explicit step a different account needs
     * before it can sign in. Everything goes: the replica's rows (unpushed
     * edits included), the old mirror and outbox, and the account binding.
     * FIT files and everything derived from them go too.
     */
    suspend fun eraseLocalData() {
        client().logout()
        replica.erase()
        files.clear()
    }

    private fun prefs() = context.getSharedPreferences("tracks_app", Context.MODE_PRIVATE)

    private fun loadServerUrl(): String = prefs().getString(KEY_SERVER, "") ?: ""

    private fun loadApiBase(): String = prefs().getString(KEY_API_BASE, "") ?: ""

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_API_BASE = "api_base"
        /** See the signal fan-out in init: a burst of writes refreshes screens at this pace. */
        const val SIGNAL_INTERVAL_MS = 1_500L
        const val PLAN_REBUILD_DEBOUNCE_MS = 1_500L
        const val KEY_ONBOARDED = "onboarding_complete"
        const val KEY_ONBOARD_STARTED = "onboarding_started"
        const val KEY_ONBOARD_STEP = "onboarding_step"
        const val KEY_ONBOARD_STANDALONE = "onboarding_standalone"
        const val KEY_ONBOARD_RESTORED = "onboarding_restored"
        const val KEY_ONBOARD_HAS_DEVICE = "onboarding_has_device"
        const val KEY_HAS_DEVICE = "has_device"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_IMPERIAL = "imperial_units"
        private const val KEY_LAST_BACKUP = "last_backup_at"
    }
}

/** See [AppContainer.onboardingProgress]. `step` is an OnboardingStep name. */
data class OnboardingProgress(
    val step: String,
    val standalone: Boolean,
    val restored: Boolean,
    val hasDevice: Boolean,
)
