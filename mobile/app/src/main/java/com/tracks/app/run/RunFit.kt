// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.run

import com.tracks.core.run.RunFix
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.FileType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.FitFile
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.RecordData
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.Event
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.enums.EventType
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitActivity
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitEvent
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileCreator
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitFileId
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.FitLap
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.AbstractFitRecord
// Kotlin does not resolve a nested class through a supertype the way Java does,
// so the builders come from the generated abstract messages rather than from the
// hand-written FitRecord/FitSession that extend them.
import nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.messages.AbstractFitSession

/**
 * A run recorded on the phone, written as a Garmin FIT activity.
 *
 * ## Why FIT, and not a simpler shape
 *
 * There is exactly one way into the activity table: a sealed FIT file through
 * `POST /sync/ingest`. Everything the server knows how to do with an activity —
 * the training load it contributes, the pace and elevation curves, the map, the
 * effect on fitness and form — is done by the FIT importer. A phone-recorded run
 * that arrived by some other route would be an activity the rest of Tracks does
 * not understand, and every one of those features would have to be built a
 * second time for a second kind of activity.
 *
 * Writing FIT costs almost nothing here because the encoder is already in the
 * tree: the vendored Gadgetbridge stack builds FIT files to send courses *to* a
 * watch, and the same builders write one for a run. So a run recorded with no
 * watch present lands in the database indistinguishable from one recorded on a
 * fenix — same importer, same metrics, same everything.
 *
 * ## What is in the file
 *
 * The minimum an importer needs to call this a run, and nothing invented. There
 * is no heart rate because a phone has no heart-rate sensor, and no cadence
 * because this does not read the step counter; leaving those fields out is the
 * honest encoding, and every consumer already handles their absence — a watch
 * without a chest strap produces the same gap.
 *
 * Manufacturer 1 is Garmin's own id and would be a lie. [MANUFACTURER_DEVELOPMENT]
 * is the value the FIT profile reserves for exactly this case, which keeps the
 * file's provenance readable: this run came from an app, not from a device.
 */
object RunFit {

    /**
     * The FIT profile's `development` manufacturer.
     *
     * Not Garmin (1), and not a made-up number: a file claiming to be from a
     * device it was not from is a file whose origin can never be recovered.
     */
    private const val MANUFACTURER_DEVELOPMENT = 255

    /** `sport` in the FIT profile. 1 is running. */
    const val SPORT_RUNNING = 1

    /**
     * A Tracks sport name as FIT's (sport, sub_sport), from the profile's own
     * tables -- what the importer reads back, so a recorded ride is a ride.
     */
    fun fitSport(sport: String?): Pair<Int, Int> = when ((sport ?: "running").lowercase()) {
        "running", "run" -> 1 to 0
        "trail_running" -> 1 to 3
        "cycling", "road_biking", "road_cycling" -> 2 to 7
        "mountain_biking" -> 2 to 8
        "gravel_cycling" -> 2 to 0
        "walking", "walk" -> 11 to 0
        "hiking" -> 17 to 0
        "cross_country_skiing", "nordic_skiing", "backcountry_skiing" -> 12 to 0
        "rowing" -> 15 to 0
        "kayaking" -> 41 to 0
        "paddling" -> 19 to 0
        "stand_up_paddleboarding" -> 37 to 0
        "rock_climbing", "climbing", "bouldering" -> 31 to 0
        "strength_training", "strength" -> 10 to 20
        "flexibility_training", "flexibility", "mobility" -> 10 to 19
        "yoga" -> 10 to 43
        else -> 0 to 0
    }

    /** `event_type`: 0 starts the timer, 4 stops everything. */
    private val EVENT_TIMER = Event.TIMER
    private val EVENT_TYPE_START = EventType.START
    private val EVENT_TYPE_STOP_ALL = EventType.STOP_ALL

    /**
     * Local message numbers.
     *
     * FIT interleaves definition and data records, and each definition claims one
     * of sixteen local slots. One slot per message type here, assigned up front,
     * because a slot reused by two message types mid-file means every reader has
     * to have seen the redefinition — and the ones that stream do not always.
     */
    private const val LOCAL_FILE_ID = 0
    private const val LOCAL_CREATOR = 1
    private const val LOCAL_EVENT = 2
    private const val LOCAL_RECORD = 3
    private const val LOCAL_LAP = 4
    private const val LOCAL_SESSION = 5
    private const val LOCAL_ACTIVITY = 6

    /**
     * Encode a finished run.
     *
     * [fixes] must be in time order and non-empty; a run with no fixes is one
     * where the GPS never got a lock, and there is nothing to write.
     */
    fun encode(
        fixes: List<RunFix>,
        distanceM: Double,
        ascentM: Double,
        elapsedMs: Long,
        movingMs: Long,
        startedAtMs: Long,
        /**
         * The counted distance at each fix ([RunTrack.cumulativeM]); null
         * sums every step. A guided run's walk is not in it, and the records
         * must agree with the session's total or an importer reading them
         * would put the walk back.
         */
        cumulativeM: List<Double>? = null,
        /** Moving time less the walk, for average speed; null uses [movingMs]. */
        runningMs: Long? = null,
        /**
         * FIT `sport` and `sub_sport` ([fitSport]). Running unless said: every
         * recording used to be filed as a run, which is why only runs could be
         * recorded at all.
         */
        sport: Int = SPORT_RUNNING,
        subSport: Int = 0,
    ): ByteArray? {
        if (fixes.isEmpty()) return null

        val startSec = startedAtMs / 1000L
        val endSec = startSec + (elapsedMs / 1000L)
        val movingSec = movingMs / 1000.0
        val runningSec = (runningMs ?: movingMs) / 1000.0
        val records = mutableListOf<RecordData>()

        records += FitFileId.Builder().apply {
            setType(FileType.FILETYPE.ACTIVITY)
            setManufacturer(MANUFACTURER_DEVELOPMENT)
            setProduct(1)
            // Derived from the start time so the same run encoded twice is the
            // same file: the ingest endpoint de-duplicates on a content hash, and
            // a random serial would let a retried upload land twice.
            setSerialNumber(startSec)
            setTimeCreated(startSec)
        }.build(LOCAL_FILE_ID)

        records += FitFileCreator.Builder().apply {
            setSoftwareVersion(1)
        }.build(LOCAL_CREATOR)

        records += timerEvent(startSec, EVENT_TYPE_START)

        var travelled = 0.0
        var previous: RunFix? = null
        for ((i, fix) in fixes.withIndex()) {
            val counted = cumulativeM?.getOrNull(i)
            if (counted != null) {
                travelled = counted
            } else {
                previous?.let {
                    travelled += com.tracks.core.run.haversineMetres(it.lat, it.lng, fix.lat, fix.lng)
                }
            }
            records += AbstractFitRecord.Builder().apply {
                setTimestamp(fix.timestampMs / 1000L)
                setPositionLat(fix.lat)
                setPositionLong(fix.lng)
                setDistance(travelled)
                fix.altitudeM?.let { setAltitude(it.toFloat()) }
                fix.speedMps?.let { setSpeed(it.toFloat()) }
                fix.accuracyM?.let { setGpsAccuracy(it.toInt()) }
            }.build(LOCAL_RECORD)
            previous = fix
        }

        records += timerEvent(endSec, EVENT_TYPE_STOP_ALL)

        val first = fixes.first()
        val last = fixes.last()
        // One lap covering the whole run. Per-kilometre laps would be nicer, but
        // the importer derives splits from the record stream anyway, and a lap
        // list that disagreed with it would be worse than no lap list.
        records += FitLap.Builder().apply {
            setTimestamp(endSec)
            setMessageIndex(0)
            setStartTime(startSec)
            setSport(sport)
            setSubSport(subSport)
            setStartPositionLat(first.lat)
            setStartPositionLong(first.lng)
            setEndPositionLat(last.lat)
            setEndPositionLong(last.lng)
            setTotalDistance(distanceM)
            setTotalElapsedTime(elapsedMs / 1000.0)
            setTotalTimerTime(movingSec)
            setTotalAscent(ascentM.toInt())
            if (runningSec > 0) setAvgSpeed((distanceM / runningSec).toFloat())
        }.build(LOCAL_LAP)

        records += AbstractFitSession.Builder().apply {
            setTimestamp(endSec)
            setMessageIndex(0)
            setStartTime(startSec)
            setSport(sport)
            setSubSport(subSport)
            setStartPositionLat(first.lat)
            setStartPositionLong(first.lng)
            setTotalDistance(distanceM)
            // The session's elapsed field is whole seconds; the lap's is not.
            setTotalElapsedTime(elapsedMs / 1000L)
            setTotalTimerTime(movingSec)
            setTotalAscent(ascentM.toInt())
            setFirstLapIndex(0)
            setNumLaps(1)
            if (runningSec > 0) setAvgSpeed((distanceM / runningSec).toFloat())
        }.build(LOCAL_SESSION)

        records += FitActivity.Builder().apply {
            setTimestamp(endSec)
            setTotalTimerTime(movingMs / 1000.0)
            setNumSessions(1)
            setType(0)
            setEvent(Event.ACTIVITY)
            setEventType(EventType.STOP)
            setLocalTimestamp(endSec)
        }.build(LOCAL_ACTIVITY)

        return FitFile(records).outgoingMessage
    }

    /**
     * A session with no track: yoga, climbing, anything timed and done in one
     * place. The same file as a run's, less the GPS -- start and stop, one lap,
     * one session in the right sport -- so it enters the activity list and the
     * fitness model by the same door, its load estimated from sport and time.
     */
    fun encodeTimed(
        startedAtMs: Long,
        elapsedMs: Long,
        movingMs: Long,
        sport: Int,
        subSport: Int = 0,
    ): ByteArray? {
        if (movingMs <= 0) return null
        val startSec = startedAtMs / 1000L
        val endSec = startSec + (elapsedMs / 1000L)
        val movingSec = movingMs / 1000.0
        val records = mutableListOf<RecordData>()
        records += FitFileId.Builder().apply {
            setType(FileType.FILETYPE.ACTIVITY)
            setManufacturer(MANUFACTURER_DEVELOPMENT)
            setProduct(1)
            setSerialNumber(startSec)
            setTimeCreated(startSec)
        }.build(LOCAL_FILE_ID)
        records += FitFileCreator.Builder().apply { setSoftwareVersion(1) }.build(LOCAL_CREATOR)
        records += timerEvent(startSec, EVENT_TYPE_START)
        records += timerEvent(endSec, EVENT_TYPE_STOP_ALL)
        records += FitLap.Builder().apply {
            setTimestamp(endSec)
            setMessageIndex(0)
            setStartTime(startSec)
            setSport(sport)
            setSubSport(subSport)
            setTotalElapsedTime(elapsedMs / 1000.0)
            setTotalTimerTime(movingSec)
        }.build(LOCAL_LAP)
        records += AbstractFitSession.Builder().apply {
            setTimestamp(endSec)
            setMessageIndex(0)
            setStartTime(startSec)
            setSport(sport)
            setSubSport(subSport)
            setTotalElapsedTime(elapsedMs / 1000L)
            setTotalTimerTime(movingSec)
            setFirstLapIndex(0)
            setNumLaps(1)
        }.build(LOCAL_SESSION)
        records += FitActivity.Builder().apply {
            setTimestamp(endSec)
            setTotalTimerTime(movingMs / 1000.0)
            setNumSessions(1)
            setType(0)
            setEvent(Event.ACTIVITY)
            setEventType(EventType.STOP)
            setLocalTimestamp(endSec)
        }.build(LOCAL_ACTIVITY)
        return FitFile(records).outgoingMessage
    }

    private fun timerEvent(timestampSec: Long, eventType: EventType): FitEvent =
        FitEvent.Builder().apply {
            setTimestamp(timestampSec)
            setEvent(EVENT_TIMER)
            setEventType(eventType)
            setEventGroup(0)
        }.build(LOCAL_EVENT)

    /**
     * The name the file is uploaded under.
     *
     * Only ever read by a human looking at a failed import, but that is enough
     * reason for it to say what the file is and when it happened.
     */
    fun filename(startedAtMs: Long): String = "phone-run-${startedAtMs / 1000L}.fit"
}
