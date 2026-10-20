// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import com.tracks.app.WatchSyncNotifications

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.tracks.device.garmin.live.LiveActivity
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
 * Push went from first to last on 2026-08-28, on the belief that importing a
 * calendar batch restarts the watch and so ends the run. That belief was wrong
 * (the watch never restarts; corrected 2026-10-12), but push-last stands on its
 * own: recordings are the irreplaceable data, so they come off first.
 *
 * Upload also has to precede push, for a reason that is easy to tidy back into a
 * bug: a pulled file is confirmed — the watch told it may reclaim its copy —
 * only once the server holds the bytes, so confirming needs the link *and* the
 * network.
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

    /** Whether a sync holds the watch right now. */
    val isRunning: Boolean get() = mutex.isLocked

    /** Where a running watch sync is, for the progress pill; null when none is. */
    data class Progress(val label: String, val fraction: Float? = null)

    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<Progress?>(null)

    /**
     * Shown in the app's one status pill (see StatusHost), so a watch
     * sync shows what it is doing while it does it rather than a line of text
     * once it is over.
     */
    val progress: kotlinx.coroutines.flow.StateFlow<Progress?> = _progress

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
     * come off the watch first, always, so a long or failing push can never
     * stand between a recording and the server.
     *
     * **Upload before push**, for the same reason and a subtler one:
     * [WatchManager.uploadPulled] only lets the watch reclaim a file once the
     * server has the bytes, so confirming needs the link *and* the network. A
     * link lost during a push would strand every pulled file unconfirmed, to be
     * pulled again next run.
     *
     * Push therefore goes last.
     */
    private suspend fun runLocked(): Outcome = try {
        runPhases()
    } finally {
        _progress.value = null
    }

    private suspend fun runPhases(): Outcome {
        val phases = mutableListOf<PhaseReport>()
        _progress.value = Progress("Connecting to the watch")

        if (!container.watch.connect()) {
            return Outcome(paired = false, phases = phases + PhaseReport(Phase.CONNECT, SKIPPED, "nothing paired"))
        }
        phases += PhaseReport(Phase.CONNECT, RAN)

        // ── pull ── needs the link
        _progress.value = Progress("Reading the watch")
        var attempt = pullOnce()
        // A failed pull, or one the watch refused outright, gets one more try
        // on a link built from nothing. That is what force-stopping the app
        // did, and it was the only thing that worked: state left on a
        // connection that stays up for days (a stuck BLE write, transfer
        // channels never released) fails every sync over it, and only a new
        // connection clears it. Not while the watch records -- its refusals
        // then are the recording's, and a reconnect would not change them.
        if (attempt.needsFreshLink && !LiveActivity.recording) {
            Log.w(TAG, "pull ${attempt.report}; rebuilding the link and trying once more")
            SyncTrace.note(container.appContext, "pull ${attempt.report}; rebuilding the link")
            _progress.value = Progress("Reconnecting to the watch")
            runCatching {
                container.watch.disconnect()
                container.watch.connect()
            }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "reconnect failed", it) }
            _progress.value = Progress("Reading the watch")
            attempt = pullOnce()
        }
        val recording = LiveActivity.recording
        val refused = attempt.refused
        val pulled = attempt.files
        phases += attempt.report.let { report ->
            // During a recording a refusal or a failure is expected, not a
            // fault: the watch will not hand over what it is still writing.
            // Said so plainly, and the rest follows once it is saved.
            if (recording && report.status == FAILED) {
                PhaseReport(Phase.PULL, SKIPPED, "the watch is recording; ${pulled.size} file(s) now, the rest once it is saved")
            } else {
                report
            }
        }
        WatchSyncNotifications.watchRefusing(
            container.appContext, if (pulled.isEmpty() && !recording) refused else 0,
        )
        if (recording) syncAfterRecording()

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
            else -> uploadResult = runCatching {
                container.watch.uploadPulled(pulled, uploader) { done, total ->
                    _progress.value = Progress("Uploading watch files", done.toFloat() / total.coerceAtLeast(1))
                }
            }
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
        // Last on purpose: see the class doc.
        _progress.value = Progress("Sending to the watch")
        val pushed = runCatching { container.watch.pushFromServer(container.client()) }
            .onFailure { Log.w(TAG, "push to watch failed", it) }
            .also {
                phases += if (it.isSuccess) {
                    PhaseReport(Phase.PUSH, RAN)
                } else {
                    // A disconnect here is a lost link, not a sign of success.
                    // It used to be reported as "watch restarted after the
                    // import", on the belief that a calendar import restarts the
                    // watch; it does not (corrected 2026-10-12).
                    val cause = it.exceptionOrNull()
                    if (cause is CancellationException) throw cause
                    PhaseReport(Phase.PUSH, FAILED, cause?.message)
                }
            }
            .getOrNull()

        Log.i(TAG, "sync finished — " + phases.joinToString("; "))
        return Outcome(
            pushed = pushed, pulled = pulled, imported = imported, health = health,
            notRegistered = notRegistered, uploadResult = uploadResult, phases = phases,
            refusedByWatch = if (pulled.isEmpty() && !recording) refused else 0,
            recordingOnWatch = recording,
        ).also { SyncTrace.record(container.appContext, it) }
    }

    /** One pull, with what the watch refused and how it ended. */
    private class PullAttempt(val files: List<PulledFile>, val refused: Int, val report: PhaseReport) {
        val needsFreshLink: Boolean get() = report.status == FAILED
    }

    private suspend fun pullOnce(): PullAttempt = try {
        val files = container.watch.pullAll()
        val refused = container.watch.takeRefusedDownloads()
        Log.i(TAG, "pulled ${files.size} file(s): ${files.groupingBy { f -> f.kind }.eachCount()}" +
            if (refused > 0) "; the watch refused $refused" else "")
        PullAttempt(files, refused, pullReport(files.size, refused))
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
        PullAttempt(emptyList(), 0, PhaseReport(Phase.PULL, FAILED, e.message))
    }

    private var afterRecording: Job? = null

    /**
     * Sync again once the watch has saved what it is recording.
     *
     * The activity being recorded is the one file a sync during it cannot
     * have, and it is the one the person most wants. Rather than leave it
     * for the next hourly run, ask the watch every couple of minutes whether
     * it is still recording, and sync as soon as it says it is not.
     */
    private fun syncAfterRecording() {
        if (afterRecording?.isActive == true) return
        afterRecording = detached.launch {
            while (true) {
                delay(AFTER_RECORDING_POLL_MS)
                runCatching { container.watch.requestActivityStatus() }
                // The answer arrives on the BLE thread; give it a moment.
                delay(5_000)
                if (!LiveActivity.recording) break
            }
            Log.i(TAG, "the watch has finished recording; syncing what it saved")
            runOnce()
        }
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
        /**
         * Files the watch listed and then refused to send, when it refused
         * every one — the state only a restart of the watch clears. 0 otherwise.
         */
        val refusedByWatch: Int = 0,
        /** The watch was recording an activity during this sync; nothing about it is a fault. */
        val recordingOnWatch: Boolean = false,
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

    internal companion object {
        const val TAG = "TracksWatchSync"

        /** How often [syncAfterRecording] asks whether the recording has ended. */
        const val AFTER_RECORDING_POLL_MS = 2 * 60 * 1000L

        /**
         * The PULL step, given what came off and what the watch refused.
         *
         * Refusing *everything* it listed is a failure, not "0 files": measured
         * 2026-10-13, the fenix answered every file request with status 3 for
         * hours — no activity, no sleep, no steps — while each sync reported a
         * clean pull of nothing. A restart of the watch cleared it. Some
         * refused among some delivered is the watch's ordinary hiccup, and the
         * next sync asks again.
         */
        fun pullReport(pulled: Int, refused: Int): PhaseReport = when {
            pulled == 0 && refused > 0 ->
                PhaseReport(Phase.PULL, FAILED, "the watch refused all $refused file(s); restart the watch")
            refused > 0 -> PhaseReport(Phase.PULL, RAN, "$pulled file(s), $refused refused")
            else -> PhaseReport(Phase.PULL, RAN, "$pulled file(s)")
        }

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
