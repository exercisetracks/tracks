// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device

import kotlinx.coroutines.flow.Flow

/**
 * What a watch can do, independent of who made it.
 *
 * Garmin is the first implementation and Coros is the reason this interface
 * exists rather than a Garmin class: Gadgetbridge has no Coros support at all,
 * so that work is real reverse engineering, and it should slot in underneath
 * without anything above it changing.
 *
 * The shape follows what the server already offers. Tracks builds FIT files
 * itself — workouts, courses, the training schedule — and exposes them through
 * `/device-sync` endpoints as bytes with a destination folder. So this layer is a
 * *transport*: it moves opaque files on and off a watch and never authors or
 * parses their contents.
 *
 * ## Why the pull side is a flow rather than list-then-read
 *
 * An earlier draft of this interface had `listFiles()` and `pullFile()`, which
 * is how a filesystem behaves and is not how any of these watches behave. On
 * Garmin's protocol there is no addressable read: you ask the watch to begin a
 * sync, it streams a directory, and files arrive one at a time as the transfer
 * state machine works through them. Bytes land on disk as a side effect rather
 * than being returned to a caller.
 *
 * Layering a `pullFile(): ByteArray` over that would mean re-reading a file we
 * had just written and pretending we chose the moment. So the pull side is
 * modelled as it really is — start a run, receive files as they come — which
 * also matches Coros, and every other watch that decides for itself what it is
 * willing to hand over.
 */
interface DeviceIntegration {

    /** Stable identifier for this vendor, e.g. "garmin". */
    val vendorId: String

    /** What this implementation can actually do — see [DeviceCapabilities]. */
    val capabilities: DeviceCapabilities

    /** Connection state, for the UI and for deciding whether to queue work. */
    val connection: Flow<ConnectionState>

    /**
     * Files pulled off the watch, as they arrive.
     *
     * Hot: emissions happen during a [pullAll] run whether or not anyone is
     * collecting. Subscribe before starting a run.
     */
    val pulledFiles: Flow<PulledFile>

    /** Battery percentage as the device last reported it, null before it has. */
    val battery: Flow<Int?>

    /**
     * The device asking for a fresh forecast.
     *
     * Watches ask rather than being told, because they know when their own
     * weather screen is stale and the phone does not.
     */
    val weatherRequests: Flow<Unit>

    /** Media keys pressed on the device — the one feed that runs device-to-phone. */
    val musicCommands: Flow<MusicCommand>

    /**
     * The device asking what is playing, for the same reason as
     * [weatherRequests]: it knows when its music screen has just been opened
     * and the phone does not. Without answering this, a watch that opens the
     * screen after the last track change is shown nothing and its buttons have
     * nothing to act on.
     */
    val musicRequests: Flow<Unit>

    /**
     * The device asking the phone to make a noise so it can be found.
     *
     * Device-to-phone like [musicCommands], and the one feed whose whole value
     * is that it works when nobody is looking at either screen: it is used
     * precisely when the phone is lost, which is to say face-down under
     * something with the app long since swapped out of memory.
     */
    val findPhone: Flow<FindPhone>

    /**
     * The user acting on a relayed notification from the device — clearing it,
     * replying, or pressing one of its buttons. Empty when
     * [DeviceCapabilities.notificationActions] is false.
     */
    val notificationResponses: Flow<NotificationResponse>

    /**
     * Associate a device.
     *
     * Implementations should prefer the platform's companion-device flow over a
     * raw BLE scan: it hands back an association that survives reboots and lets
     * the OS wake the app when the watch appears, which is a large battery
     * saving over scanning ourselves. [picker] is how the system dialog gets on
     * screen; it may be omitted when an association already exists, which is the
     * common case after the first run.
     */
    suspend fun pair(picker: CompanionPairing.DevicePicker? = null): PairedDevice

    suspend fun connect(device: PairedDevice)
    suspend fun disconnect()

    /**
     * Ask the watch to offer everything it has recorded, suspend until the run
     * finishes, and return everything it handed over.
     *
     * Files *also* surface on [pulledFiles] as they arrive, because a multi-hour
     * ride can take minutes to transfer and a progress display should not have
     * to wait for the last one. But the return value is the authoritative set
     * and the one an uploader must use.
     *
     * That distinction is not pedantry, it is a bug that shipped: the caller
     * used to read the files off a snapshot of the flow's collected state at the
     * moment this returned. Nothing orders a flow collector against this
     * function returning, so on a 116-file backlog the collector was 54 files
     * behind when the snapshot was taken — and those 54 were pulled, written to
     * the phone, and then never uploaded, while the sync reported success. The
     * return value has the ordering the flow cannot: every file is emitted on
     * the transport's own thread before that same thread ends the run.
     */
    suspend fun pullAll(): List<PulledFile>

    /**
     * Tell the watch a pulled file is durably held elsewhere, so it may reclaim
     * the space.
     *
     * Deliberately separate from pulling, and deliberately the caller's
     * decision. Until Tracks has the bytes somewhere that survives losing the
     * phone, the watch holds the only copy — and on an expedition that
     * distinction is the whole ballgame. Gadgetbridge archives immediately after
     * a successful transfer; we wait to be told.
     */
    suspend fun confirmPulled(file: PulledFile)

    /**
     * The opposite settlement for a file that must stay on the watch — a
     * course or a saved place, which is the user's own content rather than a
     * recording: remember it is delivered, so later syncs stop fetching it,
     * and drop the phone's copy, without telling the watch anything.
     *
     * Leaving these alone entirely was the earlier answer, and it cost every
     * sync: the phone's copy of each course stayed in the export directory,
     * the next sync found it there and uploaded all of them again — 35 files,
     * about half a minute per sync on the fenix 6X (2026-09-30).
     *
     * The default only drops the local copy; an integration that can tell its
     * device's files apart should also remember the id.
     */
    suspend fun keepPulledOnDevice(file: PulledFile) {
        java.io.File(file.localPath).takeIf { it.exists() }?.delete()
    }

    /**
     * Write a file the server produced.
     *
     * [folder] is the on-device destination the server supplied (for Garmin,
     * things like `GARMIN/NewFiles`), so the server side stays protocol-dumb.
     * What an implementation does with it is its own business — Garmin's BLE
     * protocol has no folders at all and addresses files by type instead.
     */
    /**
     * @param lastInBatch whether this file ends the group being sent.
     *
     * Defaults true, which is right for a lone push. A caller sending several
     * files as one unit -- a schedule and the workouts it names -- passes false
     * for all but the last, so the transport can tell the device the group is
     * finished rather than leaving it to guess from a silence.
     */
    suspend fun pushFile(
        folder: String,
        filename: String,
        bytes: ByteArray,
        lastInBatch: Boolean = true,
    )

    /** Relay a phone notification. No-op when [DeviceCapabilities.notifications] is false. */
    suspend fun sendNotification(notification: DeviceNotification)

    /** Withdraw a notification the user dismissed on the phone. */
    suspend fun dismissNotification(id: Int)

    /**
     * The short answers the device offers when replying to a notification.
     * Kept by the implementation across connections, so it needs calling only
     * when the list changes; calling it again on connect is harmless.
     */
    suspend fun setQuickReplies(replies: List<String>)

    /** Push a forecast. No-op when [DeviceCapabilities.weather] is false. */
    suspend fun sendWeather(weather: WeatherReport)

    /**
     * Push what is playing. Either argument may be null when only one changed —
     * a track change and a play/pause are separate events on the phone and
     * sending both every time would be needless radio traffic.
     */
    suspend fun sendMusic(track: MusicTrack?, state: MusicState?)
}

/**
 * What an implementation supports.
 *
 * Declared rather than discovered so the UI can hide what a device cannot do
 * instead of offering it and failing. A Coros implementation that lands without
 * a write path says so here, and the sync flow skips pushing rather than
 * erroring per file.
 */
data class DeviceCapabilities(
    val canPull: Boolean = false,
    val canPush: Boolean = false,
    /** Whether [DeviceIntegration.confirmPulled] can actually free space on the device. */
    val canArchive: Boolean = false,
    val notifications: Boolean = false,
    /** Whether [DeviceIntegration.notificationResponses] ever emits. */
    val notificationActions: Boolean = false,
    val weather: Boolean = false,
    val music: Boolean = false,
    /** Whether the device can ask the phone to ring — see [DeviceIntegration.findPhone]. */
    val findPhone: Boolean = false,
    /** AGPS/ephemeris upload, which materially speeds up GPS lock. */
    val assistedGps: Boolean = false,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** Link is up but the device has not finished its handshake; do not send yet. */
    data class Initializing(val device: PairedDevice) : ConnectionState

    /** Ready for commands. */
    data class Connected(val device: PairedDevice) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

data class PairedDevice(
    val address: String,
    val name: String,
    val vendorId: String,
    /** Serial as the watch reports it; the server keys device claims on this. */
    val serialNumber: String? = null,
)

/**
 * A file that has come off the watch and is now on the phone.
 *
 * [localPath] rather than bytes: an activity file can be tens of megabytes and
 * the upload path seals and streams it, so holding the whole thing in memory
 * would be a needless allocation on the one device that cannot spare it.
 */
data class PulledFile(
    val localPath: String,
    val name: String,
    val sizeBytes: Long,
    val kind: PulledFileKind,
    /**
     * How the device addresses this file, for [DeviceIntegration.confirmPulled].
     * Null when the device offers no way to reclaim it.
     */
    val deviceFileId: String? = null,
)

enum class PulledFileKind {
    ACTIVITY, SLEEP, MONITORING, HRV, METRICS,

    /**
     * A route stored on the device rather than anything it recorded.
     *
     * The only kind here that changes where a file is *sent*. Everything else
     * goes to the activity ingest, which reads the real type out of the file
     * itself; a course sent there would be parsed as an activity that never
     * happened. It goes to the course endpoint instead, which turns it into a
     * saved track on the map — so a route loaded onto the watch from somewhere
     * else stops being invisible to Tracks.
     */
    COURSE,

    /**
     * The saved places a device is carrying, as one file.
     *
     * Like [COURSE] this is something the user put on the watch rather than
     * anything it recorded, and it goes to its own endpoint — the whole set is
     * reconciled at once, because a watch keeps every location in a single
     * file and "which ones are on it" is a question about that file.
     */
    PLACES,

    /**
     * The watch's own training calendar, read back to be looked at.
     *
     * The only kind that is never sent anywhere and never confirmed. It exists
     * as evidence: the watch rewrites `Schedule.fit` in its own format when it
     * ingests a schedule, so the file coming back off the watch is the one
     * direct answer to whether a pushed calendar became a calendar. Reading it
     * over BLE replaces unplugging the watch, freeing the MTP device from KDE
     * and mounting it over USB — which cannot be done without dropping the very
     * BLE link under test.
     *
     * Both of the usual dispositions would be wrong here. Sending it to the
     * activity ingest would file a training plan as a workout that never
     * happened, and confirming it would invite the watch to reclaim the exact
     * calendar being measured.
     */
    SCHEDULE,

    /** Recognised but not an activity record — settings, device metadata. */
    OTHER,
}
