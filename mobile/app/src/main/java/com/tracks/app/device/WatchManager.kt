// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tracks.app.WatchLinkService
import com.tracks.app.feeds.MusicMonitor
import com.tracks.app.feeds.PhoneFinder
import com.tracks.app.feeds.NotificationRelayPreferences
import com.tracks.app.feeds.TracksNotificationListener
import android.os.SystemClock
import com.tracks.app.feeds.WeatherReceiver
import com.tracks.core.api.WatchWeather
import com.tracks.device.DailyForecast
import com.tracks.device.HourlyForecast
import com.tracks.device.WeatherReport
import com.tracks.device.CompanionPairing
import com.tracks.device.ConnectionState
import com.tracks.device.DeviceIntegration
import com.tracks.device.MusicCommand
import com.tracks.device.DeviceNotification
import com.tracks.device.PairedDevice
import com.tracks.device.PulledFile
import com.tracks.device.PulledFileKind
import com.tracks.core.api.CourseSummary
import com.tracks.core.api.MarkItem
import com.tracks.core.api.PushItem
import com.tracks.core.api.Waypoint
import com.tracks.app.TracksApplication
import com.tracks.core.api.TracksClient
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.sync.CoachingContext
import com.tracks.core.sync.CoursePushJob
import com.tracks.core.sync.SCHEDULE_DAYS_AHEAD
import com.tracks.core.sync.WatchPushBundle
import com.tracks.core.sync.WatchPushFile
import com.tracks.core.sync.buildScheduleBundle
import com.tracks.core.sync.localCoachingContext
import com.tracks.core.sync.WaypointPushJob
import com.tracks.core.sync.WatchUploader
import com.tracks.core.sync.coursesToPush
import com.tracks.core.sync.courseCoordinatesFromGeoJson
import com.tracks.core.sync.waypointPushPlan
import com.tracks.device.garmin.CourseFitEncoder
import com.tracks.device.garmin.GarminIntegration
import com.tracks.device.garmin.WatchAppConfig
import com.tracks.device.garmin.LocationsFitEncoder
import com.tracks.device.garmin.WaypointLocation
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the watch connection and everything that flows over it.
 *
 * One layer above [DeviceIntegration] and one below the UI. It exists because
 * the four phone feeds — notifications, weather, music, calendar — have nothing
 * to do with each other and nothing to do with the watch protocol, but they all
 * need the same answer to "is a watch listening right now?". Putting that
 * question in one place is cheaper than teaching four independent feeds about
 * connection state.
 *
 * Vendor-neutral by construction: [integration] is a `DeviceIntegration`, and
 * the Garmin implementation is chosen once, at the bottom of this file. A Coros
 * implementation is a different constructor argument.
 */
@OptIn(ExperimentalEncodingApi::class)
class WatchManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val integration: DeviceIntegration = GarminIntegration(context),
    /**
     * The app's container, for the cached courses/waypoints library a course
     * or waypoint push reads and patches — see [pushCourseNow]/
     * [pushWaypointsNow] — and for the weather fallback's settings, activity
     * files and Open-Meteo client. A supplier rather than the container,
     * because the container is built lazily and taking it eagerly would make a
     * paired watch pay for that setup before anything asked it to.
     */
    private val containerProvider: () -> com.tracks.app.AppContainer = {
        (context.applicationContext as TracksApplication).container
    },
) {

    private val music = MusicMonitor(context)
    private val finder = PhoneFinder(context)
    /** Every watch model's Tracks Music build — see [installMusicApp]. */
    private val musicApps = WatchAppBundle { context.assets.open(it) }
    private var musicSubscription: MusicMonitor.Subscription? = null
    private var stopObservingReplies: (() -> Unit)? = null

    /** The long-lived collectors started by [attachFeeds], cancelled on detach. */
    private val feedJobs = mutableListOf<Job>()

    private val sendLock = Mutex()

    private val _paired = MutableStateFlow(loadPairedDevice())

    /** The watch the user has associated, or null before they have paired one. */
    val paired: Flow<PairedDevice?> = _paired.asStateFlow()

    val connection: Flow<ConnectionState> get() = integration.connection

    /** Watch battery percentage, or null before the watch has reported one. */
    val battery: Flow<Int?> get() = integration.battery

    /**
     * Files that have come off the watch and are waiting to be uploaded.
     *
     * Exposed rather than consumed here: sealing bytes and talking to the
     * server is the sync layer's job, and it is the only thing that knows
     * whether an upload succeeded well enough to let the watch discard its copy.
     */
    val pulledFiles: Flow<PulledFile> get() = integration.pulledFiles

    /**
     * Associate a watch. Needs an Activity-backed picker, so this is only
     * callable from the UI — which is correct, since it puts a dialog on screen.
     */
    suspend fun pair(picker: CompanionPairing.DevicePicker): PairedDevice {
        val device = integration.pair(picker)
        savePairedDevice(device)
        _paired.value = device
        // Pairing is the moment the user said they want these two talking, so
        // the link comes up now rather than at the next sync six hours away.
        WatchLinkPreference.start(context)
        return device
    }

    /**
     * Connect to the paired watch and bring the feeds up behind it.
     *
     * Returns false rather than throwing when nothing is paired: "no watch yet"
     * is the ordinary state of a fresh install, and a background sync should
     * treat it as nothing to do rather than as a failure to report.
     */
    suspend fun connect(): Boolean {
        val device = _paired.value ?: return false
        integration.connect(device)
        attachFeeds()
        return true
    }

    suspend fun disconnect() {
        detachFeeds()
        integration.disconnect()
    }

    /**
     * Pull everything the watch has recorded, and return it. Suspends until the
     * run finishes.
     *
     * Upload what comes back from here, not what a flow collector happens to
     * have accumulated by now — see [DeviceIntegration.pullAll].
     */
    suspend fun pullAll(): List<PulledFile> = integration.pullAll().also { reblockWifiUploadsIfSeen() }

    /**
     * An activity arrived already flagged synced, recorded since the last check
     * — the watch's own Wi-Fi upload is on again (a factory reset, a firmware
     * update, or someone switching it back at the watch). If the person chose
     * to block it, block it again now, while the link is up.
     */
    private suspend fun reblockWifiUploadsIfSeen() {
        val garmin = integration as? GarminIntegration ?: return
        val seen = garmin.takeActivitiesSyncedElsewhere()
        if (seen == 0 || !WatchPrivacyPreference.blocksWifiUploads(context)) return
        Log.w(TAG, "$seen new activit(y/ies) arrived already uploaded elsewhere; switching Wi-Fi uploads off again")
        when (val outcome = stopWifiUploads()) {
            is Outcome.Failed -> Log.w(TAG, "could not re-block Wi-Fi uploads: ${outcome.reason}")
            else -> Log.i(TAG, "Wi-Fi uploads blocked again")
        }
    }

    /** Let the watch reclaim a file whose bytes are now safely elsewhere. */
    /**
     * Ask the next pull for the user's own courses and saved places too.
     *
     * Vendor-specific and therefore routed through the integration: only Garmin
     * has a file table that marks these as things a companion app skips.
     * Devices without the notion ignore it.
     */
    fun setPullSavedItems(wanted: Boolean) {
        (integration as? com.tracks.device.garmin.GarminIntegration)?.setPullSavedItems(wanted)
    }

    suspend fun confirmPulled(file: PulledFile) = integration.confirmPulled(file)

    /**
     * Whether this is something the user put on the watch rather than something
     * it recorded.
     *
     * The distinction decides whether the watch is told it may drop its copy.
     * A recording exists to be uploaded and the phone taking custody of it is
     * the point; a course or a saved place is the user's own content, and the
     * watch's copy is the one they use.
     */
    private fun PulledFileKind.isTheUsers(): Boolean =
        this == PulledFileKind.COURSE || this == PulledFileKind.PLACES ||
            // Read-only evidence, and the most destructive thing to confirm:
            // telling the watch it may reclaim its schedule would delete the
            // calendar the read exists to measure. [uploadPulled] returns
            // before reaching the confirm, so this is the second lock on the
            // same door rather than the only one.
            this == PulledFileKind.SCHEDULE

    /**
     * Seal each pulled file, send it, and only then let the watch discard it.
     *
     * The ordering is the safety property and it is worth stating plainly: a
     * file is confirmed *after* the server says it holds the bytes, never
     * before. Anything that fails stays on both the phone and the watch and is
     * retried on the next sync, which is the behaviour you want when the failure
     * is "no signal on a mountain" rather than "corrupt file".
     *
     * Files are handled one at a time rather than in parallel. A phone on a bad
     * connection uploading six activities at once mostly produces six timeouts;
     * serially, each one that lands is one that is safe.
     */
    suspend fun uploadPulled(files: List<PulledFile>, uploader: WatchUploader): UploadResult {
        var delivered = 0
        var failed = 0

        for (file in files) {
            if (file.kind == PulledFileKind.SCHEDULE) {
                // Neither delivered nor failed: nothing was owed to the server
                // for this one. See [PulledFileKind.SCHEDULE] for why it goes
                // nowhere. Size alone answers the question it was fetched for —
                // an empty calendar is a 110-byte Schedule.fit with no schedule
                // messages, a populated one several hundred — and the bytes stay
                // on the phone for a decode when the answer is "not empty, but
                // is it *ours*".
                Log.i(TAG, "watch calendar: ${file.name}, ${file.sizeBytes} bytes " +
                    "at ${file.localPath} (110 bytes means an empty calendar)")
                continue
            }

            val bytes = try {
                withContext(Dispatchers.IO) { File(file.localPath).readBytes() }
            } catch (e: Exception) {
                Log.w(TAG, "could not read ${file.localPath}", e)
                failed++
                continue
            }

            val outcome = try {
                // Kind decides the destination for two cases. Everything else
                // goes to the activity ingest, which reads the real type out of
                // the file itself and is right far more often than a guess made
                // from a name — but a course and a set of saved places are not
                // recordings at all, and sent there they would be imported as
                // workouts that never happened.
                when (file.kind) {
                    PulledFileKind.COURSE -> uploader.uploadCourse(
                        filename = file.name,
                        bytes = bytes,
                        deviceSerial = _paired.value?.serialNumber,
                    )

                    PulledFileKind.PLACES -> uploader.uploadLocations(
                        bytes = bytes,
                        deviceSerial = _paired.value?.serialNumber,
                    )

                    else -> uploader.upload(
                        filename = file.name,
                        bytes = bytes,
                        deviceSerial = _paired.value?.serialNumber,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload of ${file.name} failed", e)
                failed++
                continue
            }

            if (outcome.delivered) {
                delivered++
                // Duplicate counts as delivered here, deliberately: it means the
                // server already has these bytes, which is exactly the state we
                // were trying to reach. Treating it as failure would pin the
                // file on the watch forever, re-uploaded on every sync.
                //
                // Not for the user's own files. Confirming tells the watch it
                // may reclaim the file, which is right for a recording that has
                // been safely uploaded and catastrophic for a course somebody
                // loaded on purpose — reading their routes would delete them.
                // The cost of not confirming is that a later sync may offer the
                // same course again, and the ingest is idempotent.
                if (file.kind.isTheUsers()) {
                    Log.d(TAG, "keeping ${file.name} on the watch — it is the user's own")
                    // A course is immutable on the watch (an edit is a new
                    // file), so remembering its id is safe. Saved places are
                    // not: the watch rewrites that file as waypoints change,
                    // and remembering it would freeze it — so only the phone's
                    // copy goes, and it is fetched again next sync.
                    runCatching {
                        if (file.kind == PulledFileKind.COURSE) integration.keepPulledOnDevice(file)
                        else integration.keepPulledOnDevice(file.copy(deviceFileId = null))
                    }.onFailure { Log.w(TAG, "could not settle ${file.name}", it) }
                } else {
                    runCatching { confirmPulled(file) }
                        .onFailure { Log.w(TAG, "could not confirm ${file.name}", it) }
                }
            } else {
                Log.w(TAG, "server rejected ${file.name}: $outcome")
                failed++
            }
        }

        return UploadResult(delivered = delivered, failed = failed)
    }

    data class UploadResult(val delivered: Int, val failed: Int)

    /**
     * Push what the server has waiting — planned workouts, courses, the training
     * schedule — onto the watch.
     *
     * The reverse of [uploadPulled] and the same ordering rule: a file is
     * reported to the server as delivered only after the watch has accepted it.
     * Getting that backwards would be worse here than on the pull side, because
     * the server *stops offering* a file it believes was delivered, so a
     * mistakenly-marked workout never reaches the watch and never retries.
     *
     * Unlike pulling, this needs a live session: the upload list is per-user
     * data behind the JWT, not the agent token. That is the right trade — a
     * planned workout the user cannot fetch is a nuisance, whereas an activity
     * that cannot be uploaded is lost data, and only the second one gets the
     * session-free path.
     */
    suspend fun pushFromServer(client: TracksClient): PushResult {
        // No early return on an empty queue: the schedule still has to go. The
        // steady state of a working install is exactly this — every workout for
        // the next week already pushed and marked, nothing new to send — and it
        // is also when the calendar changes daily as workouts roll off the back
        // of it. Returning here would mean the schedule was only ever sent on
        // the runs that happened to carry a new file.
        // Workouts are left to [pushSchedule], which sends the whole scheduled
        // set alongside the schedule itself. Pushing them here as well would
        // send every one of them twice in a single run — minutes of BLE for no
        // gain — and the copy that matters is the one that travels with the
        // schedule.
        // The one part of a push that still needs the server: race-plan files
        // are built there and have no on-device encoder. Everything else below
        // — courses, saved places, the training calendar — is built here, so a
        // server that cannot be reached costs exactly those items and not the
        // rest of the push.
        val pending = try {
            client.pushList(bluetooth = true).items
        } catch (e: CancellationException) {
            // Never swallowed: the run was abandoned, and carrying on would push
            // files on a coroutine the caller has already given up on.
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "no upload list (offline?); pushing what the phone can build", e)
            emptyList()
        }.filterNot { it.type == "workout" }

        val delivered = mutableListOf<MarkItem>()
        var failed = 0

        for (item in pending) {
            val encoded = item.dataB64
            if (encoded == null) {
                Log.w(TAG, "server offered ${item.filename} with no bytes")
                failed++
                continue
            }
            try {
                integration.pushFile(item.folder, item.filename, Base64.decode(encoded))
                delivered += MarkItem(type = item.type, id = item.id,
                    filename = item.filename, ids = item.ids)
            } catch (e: Exception) {
                Log.w(TAG, "could not push ${item.filename}", e)
                failed++
            }
        }

        if (delivered.isNotEmpty()) {
            // Queued when there is no signal, like every other write the phone
            // makes: the bytes are already on the watch, and the server hearing
            // about it later says the same thing.
            // Server-built files (race plans) are server rows, told directly;
            // a failure here only means the file is offered again next time.
            try {
                client.markUploaded(delivered)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pushed files but could not record it", e)
            }
        }

        // Courses and waypoints, unlike everything above, never needed the
        // server to reach the watch at all — see [pushMapItems]. Folded into
        // every full sync, not just a user's explicit "send now", so a
        // course flagged from across the house is on the watch by the time a
        // background sync next finds it in range.
        val mapPushed = try {
            pushMapItems(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not push courses/waypoints", e)
            null
        }

        // Never allowed to take the rest of the run down with it: a calendar
        // the phone could not build is a calendar the watch keeps from last
        // time, not a failed sync.
        val scheduled = try {
            pushSchedule(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not push the training calendar", e)
            null
        }
        return PushResult(
            delivered = delivered.size + (mapPushed?.delivered ?: 0),
            failed = failed + (mapPushed?.failed ?: 0),
            scheduled = scheduled,
        )
    }

    /**
     * Send one thing the user just asked for, now, without waiting for a sync.
     *
     * The queue is still the mechanism — [wanted] picks out of the same
     * `/device-sync/upload-list` a full sync would drain, and the same mark
     * call records it — so this cannot deliver something the server does not
     * already believe is owed. That is deliberate: a "send now" that built its
     * own file would be a second encoder to keep in step with the first.
     *
     * [Outcome.Nothing] is a real answer rather than an error. Asking to send a
     * track that is already on the watch is a reasonable thing to do and the
     * honest reply is "it is already there", not a failure.
     */
    suspend fun pushNow(client: TracksClient, wanted: (PushItem) -> Boolean): Outcome {
        val pending = try {
            client.pushList(bluetooth = true).items.filter(wanted)
        } catch (e: Exception) {
            Log.w(TAG, "could not read the upload list", e)
            return Outcome.Failed(e.message ?: "the server could not be reached")
        }
        if (pending.isEmpty()) return Outcome.Nothing

        val delivered = mutableListOf<MarkItem>()
        for (item in pending) {
            val encoded = item.dataB64
            if (encoded == null) {
                Log.w(TAG, "server offered ${item.filename} with no bytes")
                continue
            }
            try {
                integration.pushFile(item.folder, item.filename, Base64.decode(encoded))
                delivered += MarkItem(
                    type = item.type, id = item.id,
                    filename = item.filename, ids = item.ids,
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not push ${item.filename}", e)
                return Outcome.Failed(e.message ?: "the watch refused the file")
            }
        }

        if (delivered.isEmpty()) return Outcome.Nothing
        // Marked only after every file landed, for the same reason the full
        // sync does it: the server stops offering what it believes was
        // delivered, so marking optimistically loses the file for good.
        return try {
            client.markUploaded(delivered)
            Outcome.Sent(delivered.size)
        } catch (e: Exception) {
            Log.w(TAG, "pushed but could not record it", e)
            Outcome.Failed("It reached the watch, but the server was not told")
        }
    }

    /** What [pushNow] managed. */
    sealed interface Outcome {
        data class Sent(val files: Int) : Outcome

        /** Nothing was owed — most often because it is already on the watch. */
        data object Nothing : Outcome

        data class Failed(val reason: String) : Outcome
    }

    // ── Courses and waypoints: pushed with no server asked at all ───────────
    //
    // Everything above this point — workouts, race plans, the schedule —
    // still reads `/device-sync/upload-list` and fetches bytes the server
    // already built. Courses and waypoints do not: the desired state (what
    // is flagged) and the phone's own belief about what the watch already
    // holds both live in the cached library [OfflineRepository] already
    // keeps offline-capable, and the FIT bytes are built right here — see
    // [com.tracks.device.garmin.CourseFitEncoder]/[LocationsFitEncoder].
    // Only the follow-up call that tells the server what happened
    // ([OfflineRepository.markCourseUploaded]/[markWaypointsUploaded]) ever
    // touches the network, and that one queues like any other offline write.

    /** Push one flagged course now, if it needs pushing at all. */
    suspend fun pushCourseNow(client: TracksClient, courseId: Int): Outcome {
        val items = containerProvider().mapItems
        val courses = items.courses()
        val job = coursesToPush(courses).firstOrNull { it.id == courseId } ?: return Outcome.Nothing
        return pushCourseJob(job, courses)
    }

    /** Rebuild and push the saved-places file now, if the flagged set disagrees with what is believed on the watch. */
    suspend fun pushWaypointsNow(client: TracksClient): Outcome =
        pushWaypointPlan(waypointPushPlan(containerProvider().mapItems.waypoints()))

    /**
     * Switch off the watch's own Wi-Fi uploads to Garmin.
     *
     * A watch once set up with Garmin Connect uploads each activity to Garmin
     * over Wi-Fi the moment it is saved, phone or no phone (see
     * [com.tracks.core.fit.SettingsFit]). This sends the one setting a phone
     * can change about that, as Gadgetbridge's "Send Connection Settings" does.
     * Wi-Fi itself is left on: it is also how some watches fetch maps and
     * firmware, and Auto Upload is the part that leaks.
     *
     * Over the legacy create-file path, which is how Gadgetbridge sends it and
     * how the calendar reaches `GARMIN/NewFiles`; the watch applies a settings
     * file it finds there. "Sent" means the watch took the file, not that the
     * switch moved — this watch says nothing back about settings, so the
     * screen asks the person to check.
     */
    suspend fun stopWifiUploads(): Outcome {
        val bytes = com.tracks.core.fit.SettingsFit.encode(
            wifiAutoUpload = false, nowMillis = System.currentTimeMillis(),
        ) ?: return Outcome.Nothing
        val garmin = integration as? GarminIntegration
            ?: return Outcome.Failed("Only Garmin watches upload on their own")
        return try {
            if (!connect()) return Outcome.Failed("No watch is paired")
            garmin.setForceLegacyPush(true)
            try {
                integration.pushFile("GARMIN/NewFiles", WIFI_SETTINGS_FILENAME, bytes)
            } finally {
                garmin.setForceLegacyPush(false)
            }
            Outcome.Sent(1)
        } catch (e: Exception) {
            Log.w(TAG, "watch refused the Wi-Fi settings", e)
            Outcome.Failed(e.message ?: "The watch refused the setting")
        }
    }

    /**
     * Install the Tracks music app on the watch, over Bluetooth.
     *
     * The `.prg` is bundled as an app asset rather than fetched: it is signed
     * with the operator's own developer key at build time, so the phone
     * shipping it is the only party that ever needs to hold it. There is one
     * build per watch model, chosen by the product number the watch reports —
     * see [WatchAppBundle]. A watch with no build, and an app built without a
     * bundle, are both build-time facts the UI should state rather than
     * runtime failures to retry.
     *
     * [config] is stored before the transfer so the app can pair the first
     * time it runs — it asks the phone for it over the watch's HTTP proxy, and
     * this is what answers.
     *
     * Vendor-specific: only Garmin has a Connect IQ file type to push into.
     */
    suspend fun installMusicApp(config: WatchAppConfig.Config): Outcome {
        val garmin = integration as? GarminIntegration
            ?: return Outcome.Failed("This watch does not take Connect IQ apps")

        // Connected first: the build is chosen by what the watch says it is,
        // and it only says so in its handshake.
        if (!connect()) return Outcome.Failed("No watch is paired")
        val product = garmin.productNumber
            ?: return Outcome.Failed("The watch has not said which model it is yet — try again in a moment")

        val bytes = when (val build = musicApps.forProduct(product)) {
            is WatchAppBundle.Lookup.Found -> {
                Log.i(TAG, "installing the ${build.device} build for product $product")
                build.bytes
            }
            is WatchAppBundle.Lookup.Unsupported -> return Outcome.Failed(
                "Tracks Music has no build for this watch (Garmin product $product). " +
                    "It runs on watches that take Connect IQ music apps.",
            )
            WatchAppBundle.Lookup.Missing -> return Outcome.Failed(
                "This build of Tracks has no watch app bundled — build it with watchapp/build.sh all",
            )
        }

        // The old copy is deliberately left alone. Deleting it first did make
        // every send land the new build, but deleting a Connect IQ app takes
        // its media store with it — so updating the app silently wiped every
        // song the user had waited through a sync to download. An update that
        // costs someone their library is worse than an update that needs a
        // manual reinstall, so the watch is left to replace the app itself and
        // "Manage music apps" stays there for the rare case it will not.
        return try {
            garmin.installWatchApp(MUSIC_APP_FILENAME, bytes, config)
            Outcome.Sent(1)
        } catch (e: Exception) {
            Log.w(TAG, "watch refused the music app", e)
            Outcome.Failed(e.message ?: "The watch refused the app")
        }
    }


    /**
     * Keep the phone's answer to the watch's config request current. Null: no
     * music server, as of [revision] — see [GarminIntegration.setWatchAppConfig].
     */
    fun setMusicAppConfig(config: WatchAppConfig.Config?, revision: Long) {
        (integration as? GarminIntegration)?.setWatchAppConfig(config, revision)
    }

    /**
     * The Connect IQ apps installed on the watch, for the manage-and-remove UI.
     * Empty when the watch is not a Garmin or is not connected.
     */
    suspend fun installedWatchApps(): List<GarminIntegration.InstalledApp> {
        val garmin = integration as? GarminIntegration ?: return emptyList()
        return garmin.listInstalledApps()
    }

    /** Remove one app from the watch; true when the watch confirms the delete. */
    suspend fun removeWatchApp(app: GarminIntegration.InstalledApp): Boolean {
        val garmin = integration as? GarminIntegration ?: return false
        return garmin.deleteInstalledApp(app)
    }

    /**
     * Take a course off the watch over Bluetooth, by the name the watch shows.
     *
     * By name rather than by filename or byte size because the watch names a
     * course by a UUID of its own and the only thing joining the two is the
     * name it displays — see `ExploreSyncHandler`. False means the removal was
     * not recorded at all: no watch connected, no Explore sync has run yet on
     * this connection so no UUID is known, or the integration is not Garmin's.
     * True means it is recorded and a sync has started.
     */
    suspend fun deleteCourseFromWatch(courseName: String): Boolean {
        val garmin = integration as? GarminIntegration ?: return false
        return try {
            garmin.removeCourse(courseName)
        } catch (e: Exception) {
            Log.w(TAG, "could not ask the watch to remove \"$courseName\"", e)
            false
        }
    }

    /**
     * Rebuild the saved-places file after a waypoint that was on the watch
     * has just been deleted from the phone, unconditionally — not gated on
     * [waypointPushPlan]'s usual "does the flagged set already match" check.
     *
     * That check cannot see a delete coming. It compares [Waypoint.loadToDevice]
     * against [Waypoint.onWatch] across the *current* cached list, and a
     * deleted waypoint is not in that list at all any more — it drops out of
     * both sides of the comparison at once, so a genuinely stale watch copy
     * looks like nothing changed. This skips straight to a rebuild with
     * whatever remains flagged, which is the correct file whether or not the
     * plan would have noticed on its own.
     */
    suspend fun syncWaypointsAfterDelete(client: TracksClient): Outcome {
        val desired = containerProvider().mapItems.waypoints().filter { it.loadToDevice }
        val plan = if (desired.isEmpty()) WaypointPushJob.Clear else WaypointPushJob.Rebuild(desired)
        return pushWaypointPlan(plan)
    }

    /** Every course/waypoint push a full sync owes, folded into [pushFromServer]. */
    private suspend fun pushMapItems(client: TracksClient): MapPushCounts {
        val items = containerProvider().mapItems
        var delivered = 0
        var failed = 0
        val courses = items.courses()
        for (job in coursesToPush(courses)) {
            when (pushCourseJob(job, courses)) {
                is Outcome.Sent -> delivered++
                is Outcome.Failed -> failed++
                Outcome.Nothing -> {}
            }
        }
        when (pushWaypointPlan(waypointPushPlan(items.waypoints()))) {
            is Outcome.Sent -> delivered++
            is Outcome.Failed -> failed++
            Outcome.Nothing -> {}
        }
        return MapPushCounts(delivered, failed)
    }

    private data class MapPushCounts(val delivered: Int, val failed: Int)

    private suspend fun pushCourseJob(job: CoursePushJob, courses: List<CourseSummary>): Outcome {
        val course = courses.firstOrNull { it.id == job.id } ?: return Outcome.Nothing
        val items = containerProvider().mapItems
        val coordinates = courseCoordinatesFromGeoJson(items.coursesGeoJson(), job.id)
        if (coordinates == null || coordinates.size < 2) {
            Log.w(TAG, "no line for course ${job.id} — cannot build its course file")
            return Outcome.Failed("That track has no line to send")
        }
        val bytes = CourseFitEncoder.encode(
            name = course.name, sport = course.sport, courseId = course.id,
            coordinates = coordinates, distanceMetres = course.distanceMetres,
            ascentMetres = course.ascentMetres,
        )
        if (bytes == null) {
            Log.w(TAG, "could not encode a course file for ${job.id}")
            return Outcome.Failed("That track could not be turned into a course file")
        }
        return try {
            integration.pushFile("GARMIN/Courses", job.filename, bytes)
            items.markCourseUploaded(job.id, job.filename, nowIso())
            Outcome.Sent(1)
        } catch (e: Exception) {
            Log.w(TAG, "could not push course ${job.filename}", e)
            Outcome.Failed(e.message ?: "the watch refused the file")
        }
    }

    private suspend fun pushWaypointPlan(plan: WaypointPushJob): Outcome {
        val (points, ids) = when (plan) {
            is WaypointPushJob.Rebuild -> plan.waypoints.map { it.toLocation() } to plan.waypoints.map { it.id }
            WaypointPushJob.Clear -> emptyList<WaypointLocation>() to emptyList()
            WaypointPushJob.NothingToDo -> return Outcome.Nothing
        }
        val bytes = LocationsFitEncoder.encode(points)
        return try {
            integration.pushFile("GARMIN/Locations", "Locations.fit", bytes)
            containerProvider().mapItems.markWaypointsUploaded(ids, "Locations.fit", nowIso())
            Outcome.Sent(1)
        } catch (e: Exception) {
            Log.w(TAG, "could not push Locations.fit", e)
            Outcome.Failed(e.message ?: "the watch refused the file")
        }
    }

    private fun nowIso(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()

    private fun Waypoint.toLocation() =
        WaypointLocation(name = name, lat = lat, lng = lng, elevationMetres = elevationMetres, icon = icon)

    /**
     * Send the training calendar, which is what actually puts the workouts on
     * the watch's schedule rather than merely in its file system.
     *
     * A pushed workout is a file the watch owns and will never surface on its
     * own; the type-7 schedule is the thing that says "this one, on Tuesday".
     * Pushing workouts without it — which is what this did until now — leaves a
     * watch holding a fortnight of training it will not offer the user, and the
     * server marking them delivered on the way past, so they are never offered
     * again either.
     *
     * Deliberately after [TracksClient.markUploaded] and outside the
     * `delivered.isNotEmpty()` branch, for two different reasons. After,
     * because the server builds the schedule from workouts it believes the
     * watch already holds, so fetching first yields one pointing at files that
     * are not there. Outside, because a run with nothing new to push is exactly
     * when the schedule still needs sending — yesterday's workout has rolled
     * off and the calendar has changed shape even though no file did.
     *
     * Failures are logged rather than thrown: the workouts themselves are
     * already on the watch and already recorded, so losing the schedule costs
     * the user a calendar entry until the next sync rather than a lost file.
     * Nothing is marked here — the schedule is rebuilt and resent every time,
     * because it is a statement of the whole calendar rather than a queued item.
     */
    /**
     * The calendar the watch should be holding, built here rather than fetched.
     *
     * The server can still build this — `/device-sync/schedule-bundle` is
     * unchanged and the browser's sync path still uses it — but the phone no
     * longer asks. Two reasons, and the second is the one that decided it:
     *
     * 1. **A push must not need a network.** That is the whole point; see
     *    [com.tracks.core.sync.buildScheduleBundle].
     * 2. **One encoder, or the calendar churns.** The schedule matches an entry
     *    to a workout by `(serial, time_created)`, and the two sides keep that
     *    timestamp in different places — the server in a column, the phone in
     *    its own preferences. Alternating between them would produce different
     *    bytes for an unchanged plan, so the fingerprint would differ, so the
     *    bundle would be resent, so **the watch would reboot every time the
     *    signal came and went**. Encoding here always is what keeps the steady
     *    state quiet.
     *
     * The server is still the source of the *plan*: the cache is refreshed from
     * it whenever there is a network, and read as it stands when there is not.
     */
    private suspend fun scheduleBundle(client: TracksClient): WatchPushBundle? {
        val container = containerProvider()
        val today = java.time.LocalDate.now()
        val workouts = container.sources.plannedWorkouts(
            today.toString(), today.plusDays(SCHEDULE_CACHE_DAYS.toLong()).toString(),
        )
        if (workouts.isEmpty()) {
            Log.i(TAG, "no plan on this phone; nothing to put on the watch's calendar")
            return null
        }
        val coaching = localCoachingContext(container.sources)
        val stamps = loadFitTimestamps().toMutableMap()
        val now = System.currentTimeMillis()

        val bundle = buildScheduleBundle(
            workouts = workouts,
            today = java.time.LocalDate.now().toString(),
            context = coaching,
            // Assigned once and kept. A fresh timestamp per run would change
            // every file, and so reboot the watch, on every sync.
            fitTimeCreated = { id -> stamps.getOrPut(id) { now } },
            nowMillis = now,
        )
        saveFitTimestamps(stamps, keep = workouts.map { it.id }.toSet())
        return bundle
    }

    /**
     * Put the training calendar on the watch.
     *
     * Returns how many workouts the calendar places, or null if it did not go.
     */
    private suspend fun pushSchedule(client: TracksClient): Int? {
        val bundle = scheduleBundle(client) ?: return null

        // Nothing to do if the watch already has exactly this calendar.
        //
        // This is not an optimisation, it is the difference between a sync that
        // disturbs the watch and one that does not. The schedule is a statement
        // of the whole calendar rather than a queued item, so it used to be
        // rebuilt and resent on every single run -- and now that it travels
        // through GARMIN/NewFiles, every single run rebooted the watch. In the
        // steady state, which is most runs, there is nothing new to say.
        //
        // Compared against what the watch last *accepted*, and written only
        // after it accepts: the same ordering rule the upload side follows, for
        // the same reason. A digest recorded for a push that failed would mean
        // the calendar silently stopped being sent.
        val digest = scheduleDigest(bundle.schedule.bytes, bundle.workouts)
        if (digest == prefs().getString(KEY_SCHEDULE_DIGEST, null)) {
            Log.i(TAG, "training calendar unchanged (${bundle.count} workout(s)); not pushing")
            return bundle.count
        }

        // Schedule first, then its workouts. That is Garmin Connect's order and
        // it is deliberate on their side: in a captured sync where one workout
        // had changed, Connect sent the schedule and then re-sent all eighteen
        // workouts it named. Sending a workout the watch already holds looks
        // like waste right up until you notice the calendar only ever fills in
        // when the two arrive together.
        //
        // The whole bundle goes down the cable's route: schedule and every
        // workout it names into GARMIN/NewFiles, in one run, so the watch's own
        // importer sees them as one batch the way it does over USB. See
        // GarminIntegration.setForceLegacyPush.
        val garmin = integration as? com.tracks.device.garmin.GarminIntegration
        garmin?.setForceLegacyPush(!SCHEDULE_OVER_FILE_SYNC)
        try {
            // Which workout ends the burst. -1 when there are none, in which
            // case the schedule is the whole batch and closes it itself.
            val lastIndex = bundle.workouts.size - 1

            try {
                integration.pushFile(
                    bundle.schedule.folder, bundle.schedule.filename, bundle.schedule.bytes,
                    lastInBatch = lastIndex < 0,
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not push the training schedule", e)
                return null
            }

            val delivered = mutableListOf<MarkItem>()
            var pushed = 0
            for ((index, workout) in bundle.workouts.withIndex()) {
                try {
                    integration.pushFile(
                        workout.folder, workout.filename, workout.bytes,
                        lastInBatch = index == lastIndex,
                    )
                    pushed++
                    // Only what the server can actually record. A workout
                    // written on this phone and not yet synced has no row to
                    // mark, and its own create will carry it up shortly.
                    workout.id?.let { id ->
                        delivered += MarkItem(
                            type = workout.type, id = id,
                            filename = workout.filename, ids = workout.ids,
                        )
                    }
                } catch (e: Exception) {
                    // Keep going. A schedule missing one of its workouts is a
                    // calendar with a hole in it; abandoning here would leave every
                    // later day empty as well.
                    Log.w(TAG, "could not push ${workout.filename} for the schedule", e)
                }
            }

            if (delivered.isNotEmpty()) {
                // Queued rather than sent, so a push made on a hillside is still
                // recorded once there is a signal. The call is a blind append
                // server-side, so replaying it late says the same thing.
                // Recorded on the planned workout itself, locally, and synced
                // like any other field — no server needed to know it is there.
                try {
                    val at = nowIso()
                    for (item in delivered) {
                        val id = item.id ?: continue
                        containerProvider().sources.setValues(
                            "planned_workout", id,
                            mapOf("watch_filename" to item.filename, "watch_uploaded_at" to at, "watch_deleted_at" to null),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pushed the schedule's workouts but could not record it", e)
                }
            }

            Log.i(TAG, "pushed a training schedule covering ${bundle.count} workout(s), " +
                "with $pushed of ${bundle.workouts.size} workout file(s)")
            // Only once everything has been sent. A partial push -- a workout
            // refused, or the link lost midway -- must be sent again, because a
            // schedule naming a file that never arrived builds a calendar with a
            // hole in it.
            // Counted from what was pushed, not from what the server was told
            // about: a workout the server has not seen yet is delivered to the
            // watch just the same, and gating the digest on the report would
            // mean re-pushing -- and so rebooting -- on every sync until it
            // synced.
            if (pushed == bundle.workouts.size) {
                prefs().edit().putString(KEY_SCHEDULE_DIGEST, digest).apply()
            } else {
                Log.w(TAG, "not recording the calendar as delivered: " +
                    "$pushed of ${bundle.workouts.size} workout(s) landed")
            }
            return bundle.count
        } finally {
            // In a `finally` because the flag is process-wide, not
            // run-scoped. Any throw between here and the end -- a
            // malformed payload, or the coroutine being cancelled when
            // WatchSyncService is destroyed mid-push -- would otherwise
            // leave it stuck true and silently move every later push in
            // this process onto the legacy path.
            garmin?.setForceLegacyPush(false)
        }
    }

    /**
     * The `file_id.time_created` this phone has assigned to each workout.
     *
     * Half of the key a calendar entry matches its workout file by, and the
     * reason an unchanged plan produces unchanged bytes. Stored as one small
     * `id:millis` list rather than a key each, so it can be pruned in one go.
     */
    private fun loadFitTimestamps(): Map<Int, Long> {
        val raw = prefs().getString(KEY_FIT_TIMESTAMPS, null) ?: return emptyMap()
        return raw.split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val millis = parts[1].toLongOrNull() ?: return@mapNotNull null
            id to millis
        }.toMap()
    }

    /**
     * Keep the stamps for workouts still in the plan and drop the rest.
     *
     * Pruned against the whole cached plan rather than the pushed window: a
     * workout moved a fortnight out would otherwise lose its stamp, and be
     * given a new one — and a new file — when it came back into range.
     */
    private fun saveFitTimestamps(stamps: Map<Int, Long>, keep: Set<Int>) {
        val kept = stamps.filterKeys { it in keep }
        prefs().edit()
            .putString(KEY_FIT_TIMESTAMPS, kept.entries.joinToString(",") { "${it.key}:${it.value}" })
            .apply()
    }

    data class PushResult(
        val delivered: Int,
        val failed: Int,
        /** Workouts placed on the watch's calendar, or null if the schedule did not go. */
        val scheduled: Int? = null,
    )

    suspend fun pushFile(folder: String, filename: String, bytes: ByteArray) =
        integration.pushFile(folder, filename, bytes)

    // ── The four feeds ───────────────────────────────────────────────────────

    /**
     * Point the phone's feeds at the now-connected watch.
     *
     * Order matters slightly: the sink is installed before the buffer is
     * drained, so a notification arriving in between is delivered rather than
     * appended to a buffer nobody will read again.
     *
     * Calendar is conspicuously absent, and that is correct — it is pull-shaped
     * rather than push-shaped. The watch asks for the agenda when it wants one,
     * and the vendored protocol handler answers it directly from Tracks'
     * calendar reader. There is nothing for this class to wire.
     *
     * Detaching first is what makes this safe to call twice, and it needs to be:
     * [connect] now returns straight away when the link is already up — a sync
     * arriving while the link supervisor holds the connection — so this runs
     * again on a connection that already has feeds. Registering on top of them
     * would leave two collectors on each flow and an orphaned media-session
     * subscription, and every notification would reach the wrist twice.
     *
     * Tearing down and rebuilding rather than returning early, because the other
     * caller is a genuine reconnect, where re-draining the notification buffer
     * and re-pushing weather and music is exactly right for a watch that has
     * just come back into range. Both drains are destructive, so the redundant
     * case costs nothing.
     */
    private fun attachFeeds() {
        detachFeeds()

        val sink = object : TracksNotificationListener.Sink {
            override fun onNotification(notification: DeviceNotification) =
                send("relay notification") { integration.sendNotification(notification) }

            override fun onDismissed(id: Int) =
                send("dismiss notification") { integration.dismissNotification(id) }
        }
        TracksNotificationListener.sink = sink
        TracksNotificationListener.drain(sink)

        // The other direction: the user clearing, replying to, or pressing a
        // button on a notification from the wrist. Done on the phone by the
        // listener, which is the one place holding the notification's actions.
        feedJobs += scope.launch {
            integration.notificationResponses.collect { response ->
                if (!TracksNotificationListener.respond(context, response)) {
                    Log.d(TAG, "watch acted on a notification the phone no longer has")
                }
            }
        }
        // Sent on every attach as well as on change: cheap, and it means a list
        // edited while the watch was away is on the wrist the moment it is back.
        send("set quick replies") {
            integration.setQuickReplies(NotificationRelayPreferences.quickReplies(context))
        }
        stopObservingReplies = NotificationRelayPreferences.observeQuickReplies(context) { replies ->
            send("set quick replies") { integration.setQuickReplies(replies) }
        }

        WeatherReceiver.listener = { report ->
            send("send weather") { integration.sendWeather(report) }
        }
        // Send whatever we already have rather than waiting for the next
        // broadcast, which on most weather apps is an hour away.
        sendLatestWeather()

        feedJobs += scope.launch {
            integration.weatherRequests.collect { sendLatestWeather() }
        }

        feedJobs += scope.launch {
            integration.musicCommands.collect { command ->
                music.handle(command)
                // Volume answers for itself immediately: adjustStreamVolume has
                // already taken effect by the time it returns, and unlike a
                // track change nothing else is guaranteed to announce it. The
                // content observer in [MusicMonitor] does catch it, but only
                // once the system gets around to persisting the new level, and
                // a watch waiting half a second to redraw a bar it just moved
                // looks exactly like a watch whose button does nothing.
                if (command == MusicCommand.VOLUME_UP || command == MusicCommand.VOLUME_DOWN) {
                    pushMusic()
                }
            }
        }

        // The watch asking what is playing, rather than being told. Same shape
        // as weatherRequests above and for the same reason: it knows when its
        // music screen has just been opened and the phone does not.
        feedJobs += scope.launch {
            integration.musicRequests.collect { pushMusic() }
        }

        feedJobs += scope.launch {
            integration.findPhone.collect { request -> finder.handle(request) }
        }

        musicSubscription = music.observe { pushMusic() }
        pushMusic()
    }

    /**
     * Note what is *not* stopped here: the ringing.
     *
     * This runs on disconnect, and walking out of Bluetooth range is exactly
     * what happens while somebody is hunting for a phone in another room. A
     * find-my-phone that goes quiet the moment the watch loses the link would
     * fail in precisely the case it exists for. [PhoneFinder] has its own
     * timeout for the watch that never comes back.
     */
    private fun detachFeeds() {
        TracksNotificationListener.sink = null
        stopObservingReplies?.invoke()
        stopObservingReplies = null
        WeatherReceiver.listener = null
        musicSubscription?.cancel()
        musicSubscription = null
        feedJobs.forEach { it.cancel() }
        feedJobs.clear()
    }

    /**
     * Answer the watch's weather request from whatever source we have.
     *
     * A broadcasting weather app is preferred and usually better — it is the
     * user's chosen provider, already located wherever they are. But relying on
     * it alone meant the glance stayed empty forever on a phone where no app
     * was configured to broadcast, with nothing on either screen to say why;
     * the watch asks about once a minute and every one of those was dropped.
     *
     * So Tracks' own forecast is the fallback, not the primary. It is located at
     * the user's most recent activity with GPS, which needs no location
     * permission and is very nearly the right answer for "where does this
     * person train", and asked of Open-Meteo directly — see [directForecast].
     */
    private fun sendLatestWeather() {
        WeatherReceiver.latest?.let { report ->
            send("send weather") { integration.sendWeather(report) }
            return
        }
        send("send weather") {
            val report = directForecast()
            if (report == null) {
                Log.i(TAG, "no weather app is broadcasting, and Tracks had no forecast of its own either")
            } else {
                integration.sendWeather(report)
            }
        }
    }

    /**
     * A forecast fetched from Open-Meteo by the phone, cached for [WEATHER_TTL_MS].
     *
     * Direct rather than through the server, which used to make this request
     * on the phone's behalf: the location comes from the phone's own activity
     * files and the forecast from the internet, so the server added nothing but
     * a second thing that had to be reachable. Now a watch on a trip, out of
     * reach of a home server, still gets a forecast.
     *
     * The watch asks every minute and the weather does not change that fast, so
     * without a cache this would be a network round trip per minute for the
     * whole time a watch is connected — on an app whose entire premise is
     * battery discipline.
     *
     * Privacy: the Weather switch is checked before every request, and turning
     * it off drops the cached copy too — "off" should mean the watch stops
     * showing a forecast Tracks fetched, not that it keeps the last one.
     */
    private suspend fun directForecast(): WeatherReport? {
        val container = containerProvider()
        if (!container.weatherAllowed()) {
            cachedForecast = null
            return null
        }
        val cached = cachedForecast
        if (cached != null && SystemClock.elapsedRealtime() - cachedForecastAt < WEATHER_TTL_MS) {
            return cached
        }
        val point = runCatching { container.sources.recentStartPoint() }.getOrNull()
        if (point == null) {
            Log.i(TAG, "no activity with GPS on this phone, so there is nowhere to forecast for")
            return cached
        }
        val fetched = container.openMeteo.watch(point.first, point.second, System.currentTimeMillis() / 1000)
            ?.toReport()
        if (fetched == null) {
            Log.w(TAG, "could not fetch a forecast from Open-Meteo")
            // The stale copy beats nothing: a forecast an hour old is still
            // roughly today's weather, and the alternative is an empty glance.
            return cached
        }
        cachedForecast = fetched
        cachedForecastAt = SystemClock.elapsedRealtime()
        com.tracks.app.feeds.WeatherSources.recordServerForecast(context)
        Log.i(TAG, "forecast for ${fetched.location}: ${fetched.currentTempC}C ${fetched.currentCondition}")
        return fetched
    }

    private var cachedForecast: WeatherReport? = null
    private var cachedForecastAt = 0L

    private fun pushMusic() {
        send("send music") { integration.sendMusic(music.currentTrack(), music.currentState()) }
    }

    /**
     * Every outbound feed message goes through here, and the mutex is the point.
     *
     * The feeds are genuinely concurrent — a notification arrives on the
     * system's listener thread while a weather broadcast lands on the main one —
     * but the protocol layer underneath keeps per-message state (fragment
     * offsets, in-flight notification uploads) that assumes one sender. Two
     * overlapping sends would interleave fragments on the wire, and the failure
     * would look like a firmware bug rather than a race.
     *
     * Failures are logged and swallowed: none of these are worth taking down a
     * sync for. A forecast that did not arrive is a stale watch face.
     */
    private fun send(what: String, block: suspend () -> Unit) {
        // Not tracked in feedJobs: these are short-lived and self-completing,
        // and a list that only ever grows would be a leak in the one place that
        // runs for as long as the watch is connected.
        scope.launch {
            sendLock.withLock {
                runCatching { block() }.onFailure { Log.w(TAG, "could not $what", it) }
            }
        }
    }

    // ── Remembering the pairing ──────────────────────────────────────────────

    /**
     * The address is remembered, not the association.
     *
     * The companion association lives in the system and outlives the app's
     * data; this is only a note of which associated device is *ours*, so a
     * cleared-storage user is asked to pick again rather than being connected to
     * whatever else they had associated.
     */
    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadPairedDevice(): PairedDevice? {
        val prefs = prefs()
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        return PairedDevice(
            address = address,
            name = prefs.getString(KEY_NAME, address) ?: address,
            vendorId = prefs.getString(KEY_VENDOR, "garmin") ?: "garmin",
            serialNumber = prefs.getString(KEY_SERIAL, null),
        )
    }

    private fun savePairedDevice(device: PairedDevice) {
        prefs().edit()
            .putString(KEY_ADDRESS, device.address)
            .putString(KEY_NAME, device.name)
            .putString(KEY_VENDOR, device.vendorId)
            .putString(KEY_SERIAL, device.serialNumber)
            .apply()
    }

    fun forgetPairedDevice() {
        // The link service first: it would otherwise keep trying to reconnect
        // to a watch the user has just told us to forget, and its own stop
        // condition (nothing paired) is only noticed on its next attempt.
        WatchLinkService.stop(context)
        prefs().edit().clear().apply()
        _paired.value = null
    }

    internal companion object {
        const val TAG = "TracksWatch"

        private const val WIFI_SETTINGS_FILENAME = "TRACKS_WIFI.fit"
        private const val MUSIC_APP_FILENAME = "TracksMusic.prg"

        /**
         * Which transport carries the training-calendar bundle.
         *
         * True sends it over FileSyncService, ending the burst with the empty
         * TransferCompleteResponse Garmin Connect sends (see
         * FileSyncServiceHandler.handleTransferComplete). False forces the whole
         * bundle down the legacy CREATE_FILE path into GARMIN/NewFiles, which is
         * measured to build the calendar but reboots the watch on import.
         *
         * **False, and measured.** Tested 2026-08-28 with the burst terminator in
         * place: two syncs, both pushing all 21 files over FileSyncService, and
         * the watch's Schedule.fit read back unchanged both times -- a manual
         * reboot afterwards did not help either. The terminator itself works (the
         * watch answers 20 {1:1} in 103 ms instead of timing out after 30 s), so
         * the session close was not what the calendar was missing.
         *
         * Left as a named constant because the question is not closed: newer
         * watches may not offer the legacy CREATE_FILE path at all, and whatever
         * FileSyncService still wants is the thing that would let this be true.
         */
        private const val SCHEDULE_OVER_FILE_SYNC = false

        /** Fingerprint of the calendar the watch last accepted in full. */
        private const val KEY_SCHEDULE_DIGEST = "schedule_digest"

        /** Per-workout `file_id.time_created`, as `id:millis` pairs. */
        private const val KEY_FIT_TIMESTAMPS = "fit_time_created"

        /**
         * How much plan to keep cached. Wider than the calendar's own week so
         * a workout that moves stays recognisable, and so the phone has
         * something to show when it has been offline for a while.
         */
        internal const val SCHEDULE_CACHE_DAYS = 90

        /** Enough for two sessions a day across that window, and then some. */
        private const val SCHEDULE_CACHE_LIMIT = 250

        const val PREFS = "tracks_watch"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_VENDOR = "vendor"
        const val KEY_SERIAL = "serial"
    }
}

/** Half an hour: shorter than the weather changes, longer than the watch asks. */
private const val WEATHER_TTL_MS = 30 * 60 * 1000L

/**
 * Tracks' forecast in the shape the watch protocol carries.
 *
 * A straight field rename — the mapping that actually needed judgement (WMO
 * codes to the OpenWeatherMap ids these broadcasts speak) is done in
 * [com.tracks.core.weather.OpenMeteo], beside the provider.
 */
private fun WatchWeather.toReport() = WeatherReport(
    location = location,
    lat = lat,
    lon = lon,
    timestamp = timestamp,
    currentTempC = currentTempC,
    todayMinTempC = todayMinTempC,
    todayMaxTempC = todayMaxTempC,
    currentCondition = currentCondition,
    currentConditionCode = currentConditionCode,
    windSpeedKmh = windSpeedKmh,
    windDirectionDegrees = windDirectionDegrees,
    humidityPercent = humidityPercent,
    hourly = hourly.map {
        HourlyForecast(
            timestamp = it.timestamp,
            tempC = it.tempC,
            conditionCode = it.conditionCode,
            precipProbability = it.precipProbability,
            humidityPercent = it.humidityPercent,
        )
    },
    forecasts = forecasts.map {
        DailyForecast(
            minTempC = it.minTempC,
            maxTempC = it.maxTempC,
            conditionCode = it.conditionCode,
        )
    },
)

/**
 * A fingerprint of everything a calendar push would send.
 *
 * Covers the workouts as well as the schedule, because the schedule names them
 * by `file_id` and says nothing about their contents: a workout edited without
 * moving days leaves the schedule byte-identical, and skipping on that alone
 * would leave the watch running last week's intervals under this week's name.
 *
 * Top-level and internal rather than a private method, so it can be tested
 * without standing up a WatchManager and an Android Context — it is the guard
 * that decides whether the watch gets rebooted, and it is worth a test.
 */
internal fun scheduleDigest(schedule: ByteArray, workouts: List<WatchPushFile>): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    md.update(schedule)
    // Sorted so a reordering cannot masquerade as a change.
    for (w in workouts.sortedBy { it.filename }) {
        md.update(w.filename.toByteArray())
        md.update(w.bytes)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
