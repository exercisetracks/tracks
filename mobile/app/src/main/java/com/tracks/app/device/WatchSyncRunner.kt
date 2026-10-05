// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.util.Log
import com.tracks.app.AppContainer
import com.tracks.device.PulledFile
import com.tracks.device.PulledFileKind
import com.tracks.app.device.WatchSyncRunner.Status.FAILED
import com.tracks.app.device.WatchSyncRunner.Status.RAN
import com.tracks.app.device.WatchSyncRunner.Status.SKIPPED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that knows the order: pull, import, upload, then push.
 *
 * Pulled out of what used to be [com.tracks.app.MainViewModel.syncWatch] so a
 * background sync ([com.tracks.app.WatchSyncService]) and a user-triggered one
 * run the exact same sequence rather than risking two independently-maintained
 * copies drifting apart on ordering — which is the class of bug this session
 * spent most of its time on: a pull-list read from the wrong place, a cache
 * scoped to the wrong lifetime, both invisible until a real watch produced
 * enough files to expose them. Two call sites sharing one function cannot
 * drift by definition.
 *
 * ## The order, and why each step is where it is
 *
 * Import sits before upload. Uploading is the step that needs a server, and on
 * the trips this app exists for it is the step that fails — so anything
 * downstream of it is invisible for as long as there is no signal. Reading the
 * files the phone is already holding costs nothing, needs nobody, and is what
 * puts today's ride in the activity list from a hut. Every file goes through
 * [com.tracks.core.local.LocalFiles.add], the same importer the server runs, so
 * what the phone shows is final, not a placeholder for the server's copy.
 *
 * Push went from first to last on 2026-08-28. It was first on the argument that
 * a missing workout is more noticeable than a missing upload, and the pull would
 * retry next time. That stopped being true when the training calendar began
 * going out through `GARMIN/NewFiles`: importing that batch reboots the watch,
 * so *every* run died at the same point and the pull never happened at all.
 *
 * Upload also has to precede push, for a reason that is easy to tidy back into a
 * bug: a pulled file is confirmed — the watch told it may reclaim its copy —
 * only once the server holds the bytes, so confirming needs the link *and* the
 * network. Push last means a reboot costs nothing, because nothing follows it.
 *
 * Each phase says what it needs and is skipped rather than failed when it cannot
 * have it, so a sync with no signal still empties the watch.
 *
 * [mutex] is the actual safety property, not a formality. [GarminSupport]
 * assumes one conversation with the watch at a time — its BLE callback thread
 * carries per-message state (fragment offsets, an in-flight download) with no
 * concept of a second caller. A manual sync from the UI and a scheduled
 * background one racing each other would interleave two BLE conversations on
 * one connection, and the failure would look like a firmware bug rather than
 * what it was.
 */
class WatchSyncRunner(private val container: AppContainer) {

    private val mutex = Mutex()

    /**
     * Run a sync, or report that one is already running rather than queue
     * behind it.
     *
     * Queueing would be the wrong default here: the caller that lost the race
     * either already has an [Outcome] on the way (the UI, watching the same
     * flows the winning caller is driving) or is a scheduled background run
     * that will simply try again next interval. Waiting in line would only
     * hold a coroutine open for however long the winner's BLE transfer takes.
     */
    suspend fun runOnce(): Outcome {
        if (mutex.isLocked) {
            return Outcome(alreadyRunning = true)
        }
        return mutex.withLock { runLocked() }
    }

    /**
     * [runOnce], outliving the caller.
     *
     * For syncs a person started from a screen. Those used to run in the
     * screen's own scope, so leaving the screen mid-sync cancelled the push
     * half-way ("rememberCoroutineScope left the composition" in the log) and
     * left the watch with part of a calendar. Cancelling the caller now only
     * stops the waiting. The service keeps [runOnce] on purpose: when the
     * system destroys it, the run must stop with it.
     */
    suspend fun runOnceDetached(): Outcome {
        if (mutex.isLocked) {
            return Outcome(alreadyRunning = true)
        }
        return detached.async { mutex.withLock { runLocked() } }.await()
    }

    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Sync, and this time also fetch what the user put on the watch.
     *
     * Courses and saved places are excluded from an ordinary sync on purpose:
     * they do not change on their own, so pulling them every time would move
     * the same unchanging files on every run and slow down the transfer that
     * actually matters — today's ride. But it also means a watch loaded with
     * routes from Garmin Connect is invisible to Tracks, which is only
     * defensible if there is *some* way to ask.
     *
     * This is that way, and it is a user action rather than a schedule. The
     * widening is cleared in a `finally` so a failure partway cannot leave
     * every future background sync dragging courses back.
     */
    suspend fun readSavedItems(): Outcome {
        if (mutex.isLocked) {
            return Outcome(alreadyRunning = true)
        }
        return mutex.withLock {
            container.watch.setPullSavedItems(true)
            try {
                runLocked()
            } finally {
                container.watch.setPullSavedItems(false)
            }
        }
    }

    /**
     * One sync, as an ordered set of phases.
     *
     * Each phase states what it needs and is *skipped, not failed*, when it
     * cannot have it. That distinction is the whole point: a phone with no
     * signal should still empty the watch and read today's ride into the list,
     * and before this it did neither, because the run returned early the moment
     * the uploader was unavailable.
     *
     * Two orderings here are load-bearing and were both wrong before.
     *
     * **Pull before push.** Recordings are the only irreplaceable data in the
     * system; everything else can be rebuilt from the server or the phone. They
     * come off the watch first, always. This used to be the other way round,
     * which was defensible until the training calendar started going out through
     * `GARMIN/NewFiles` -- importing that batch reboots the watch, so a push
     * first meant the link was gone before the pull ever ran and activities
     * simply never left the device.
     *
     * **Upload before push**, for the same reason and a subtler one:
     * [WatchManager.uploadPulled] only lets the watch reclaim a file once the
     * server has the bytes, so confirming needs the link *and* the network. A
     * push that reboots the watch would strand every pulled file unconfirmed, to
     * be pulled again next run.
     *
     * Push therefore goes last, where losing the link costs nothing.
     */
    private suspend fun runLocked(): Outcome {
        val phases = mutableListOf<PhaseReport>()

        if (!container.watch.connect()) {
            return Outcome(paired = false, phases = phases + PhaseReport(Phase.CONNECT, SKIPPED, "nothing paired"))
        }
        phases += PhaseReport(Phase.CONNECT, RAN)

        // ── pull ── needs the link
        var pullRan = false
        val pulled = try {
            container.watch.pullAll().also {
                pullRan = true
                phases += PhaseReport(Phase.PULL, RAN, "${it.size} file(s)")
                Log.i(TAG, "pulled ${it.size} file(s): ${it.groupingBy { f -> f.kind }.eachCount()}")
            }
        } catch (e: CancellationException) {
            // Never swallowed. The service being destroyed mid-sync must stop the
            // run, not fall through and start pushing files to the watch on a
            // coroutine the system has already given up on.
            throw e
        } catch (e: Exception) {
            // Not fatal to the run. The push below may still be worth doing, and
            // a watch that dropped mid-pull has usually left its files in place
            // for the next attempt.
            Log.w(TAG, "pull failed", e)
            phases += PhaseReport(Phase.PULL, FAILED, e.message)
            emptyList()
        }

        // ── import ── needs nothing at all
        var imported = 0
        var health = 0
        val stored = mutableListOf<String>()
        if (pulled.isEmpty()) {
            phases += PhaseReport(Phase.IMPORT, SKIPPED, "nothing pulled")
        } else {
            runCatching {
                for (file in pulled.filter { it.kind == PulledFileKind.ACTIVITY || it.kind in HEALTH_KINDS }) {
                    val (sha, outcome) = container.files.add(java.io.File(file.localPath).readBytes())
                    stored += sha
                    if (outcome == com.tracks.core.local.LocalImporter.Outcome.Imported) {
                        if (file.kind == PulledFileKind.ACTIVITY) imported++ else health++
                    }
                }
            }.onFailure { Log.w(TAG, "local import failed", it) }
            phases += PhaseReport(Phase.IMPORT, RAN, "$imported activity, $health health file(s)")
        }

        // ── upload ── needs the link and the network
        val uploader = if (pulled.isEmpty()) null else container.watchUploader()
        var uploadResult: WatchManager.UploadResult? = null
        var notRegistered = false
        when {
            pulled.isEmpty() ->
                phases += PhaseReport(Phase.UPLOAD, SKIPPED, "nothing pulled")
            uploader == null -> {
                // Signed out, or no signal. The files stay on the phone *and* on
                // the watch -- nothing is confirmed, so nothing is lost -- and
                // the next run with a server retries them.
                notRegistered = true
                phases += PhaseReport(Phase.UPLOAD, SKIPPED, "no server session")
            }
            else -> uploadResult = runCatching { container.watch.uploadPulled(pulled, uploader) }
                .onFailure { Log.w(TAG, "upload failed", it) }
                .onSuccess { r ->
                    // Only when every file went: a partial result does not say
                    // which ones, and an unmarked file is merely sent again
                    // later, which the server answers "duplicate".
                    if (r.failed == 0) stored.forEach(container.library::markUploaded)
                }
                .also {
                    phases += if (it.isSuccess) {
                        PhaseReport(Phase.UPLOAD, RAN, "${it.getOrNull()?.delivered ?: 0} delivered")
                    } else {
                        PhaseReport(Phase.UPLOAD, FAILED, it.exceptionOrNull()?.message)
                    }
                }
                .getOrNull()
        }

        // ── push ── needs the link, and today a server to build the files
        //
        // Last on purpose: a schedule landing in NewFiles reboots the watch, so
        // this phase may end the connection. Nothing after it needs one.
        val pushed = runCatching { container.watch.pushFromServer(container.client()) }
            .onFailure { Log.w(TAG, "push to watch failed", it) }
            .also {
                phases += if (it.isSuccess) {
                    PhaseReport(Phase.PUSH, RAN)
                } else {
                    // A disconnect here is the signature of a *successful*
                    // NewFiles import, not a failure -- see AGENTS.md. Recorded
                    // as such so the UI stops calling a working sync broken.
                    val cause = it.exceptionOrNull()
                    if (cause is CancellationException) throw cause
                    // A disconnect here reads as success only if the link was
                    // healthy up to this point: importing a NewFiles batch
                    // reboots the watch, so losing it during the push is the
                    // signature of one that worked. If the pull had already
                    // failed the link was gone before we started, and calling
                    // that a successful push would be a lie the user acts on.
                    if (cause is com.tracks.device.garmin.GarminIntegration.DisconnectedException
                        && pullRan
                    ) {
                        PhaseReport(Phase.PUSH, RAN, "watch restarted after the import")
                    } else {
                        PhaseReport(Phase.PUSH, FAILED, cause?.message)
                    }
                }
            }
            .getOrNull()

        Log.i(TAG, "sync finished — " + phases.joinToString("; "))
        return Outcome(
            pushed = pushed, pulled = pulled, imported = imported, health = health,
            notRegistered = notRegistered, uploadResult = uploadResult, phases = phases,
        )
    }

    data class Outcome(
        val alreadyRunning: Boolean = false,
        /** False when nothing is paired yet — not an error, just nothing to do. */
        val paired: Boolean = true,
        val pushed: WatchManager.PushResult? = null,
        val pulled: List<PulledFile> = emptyList(),
        /** Recordings the phone read for itself and put straight in the list. */
        val imported: Int = 0,
        /** Sleep, monitoring and HRV files the phone read for itself. */
        val health: Int = 0,
        /** True when the phone pulled files but has no agent token to upload with. */
        val notRegistered: Boolean = false,
        val uploadResult: WatchManager.UploadResult? = null,
        /** What each phase did, in the order it happened. */
        val phases: List<PhaseReport> = emptyList(),
    ) {
        /** True when nothing outright failed — skipped phases are not failures. */
        val clean: Boolean get() = phases.none { it.status == FAILED }
    }

    /** The steps of a sync, in order. */
    enum class Phase { CONNECT, PULL, IMPORT, UPLOAD, PUSH }

    /**
     * What became of one phase.
     *
     * SKIPPED and FAILED are deliberately different. A phase with no server
     * session, or nothing to do, has not gone wrong — reporting those the same
     * way is what made an offline sync look broken when it had in fact emptied
     * the watch exactly as intended.
     */
    enum class Status { RAN, SKIPPED, FAILED }

    data class PhaseReport(
        val phase: Phase,
        val status: Status,
        val detail: String? = null,
    ) {
        override fun toString() = "$phase: $status" + (detail?.let { " ($it)" } ?: "")
    }

    private companion object {
        const val TAG = "TracksWatchSync"

        /**
         * Everything a watch records that is not an activity or a saved item.
         *
         * [PulledFileKind.OTHER] is in the list on purpose. It is the bucket
         * for a file type this build has never seen, and a firmware that files
         * its monitoring under a number nobody has mapped yet would otherwise
         * be silently unreadable — the exact failure this whole path exists to
         * remove. Reading one costs a parse that returns null: a settings file
         * carries no daily readings, so nothing but sleep, steps and body data
         * can come out of here whatever goes in.
         */
        val HEALTH_KINDS = setOf(
            PulledFileKind.SLEEP,
            PulledFileKind.MONITORING,
            PulledFileKind.HRV,
            PulledFileKind.METRICS,
            PulledFileKind.OTHER,
        )
    }
}
