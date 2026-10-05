// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * When the phone should sync the watch without being asked.
 *
 * The hourly worker ([com.tracks.app.WatchSyncScheduler]) is a safety net, not
 * a product: an hour is far too slow for the two moments a person actually
 * notices. One is waking up — last night's sleep is on the watch and is the
 * first thing the Health page is opened for. The other is editing the plan —
 * the calendar on the wrist should agree with the one on the phone before the
 * next workout, not at some later hour. This is the decision half of both; the
 * Android half (what to call, in which process state) lives beside
 * `WatchSyncRunner`, because none of *this* needs a device to test.
 */
object SmartSyncPolicy {

    /**
     * When one "day" ends and the next begins, for the once-a-morning sync.
     *
     * Not midnight. Somebody up past 00:00 has not had their morning yet, and
     * midnight would count a 00:30 glance at the phone as the day's first open
     * — spending the sync on a watch that has recorded no sleep, then declining
     * to sync again when they wake. 04:00 is after nearly everyone's bedtime
     * and before nearly everyone's alarm.
     */
    const val DAY_STARTS_AT_HOUR = 4

    /**
     * How long the plan must sit still before it is pushed.
     *
     * Editing a plan is rarely one edit: move a run, drop a swim, regenerate
     * the week, nudge the long ride. A sync per change would reboot the watch
     * (a changed calendar goes down through `GARMIN/NewFiles`, which restarts
     * it) several times in a minute, and every one but the last would be
     * sending a calendar the person is about to change again. Ninety seconds is
     * long enough to cover the gap between edits in one sitting and short
     * enough that someone who is done and pockets the phone still has the
     * watch current by the time they reach the door.
     */
    const val PLAN_SETTLE_MS = 90_000L

    /**
     * Writes land in bursts — a regenerate rewrites dozens of rows one at a
     * time — and fingerprinting the plan after each would read the same rows
     * dozens of times to learn one thing. This coalesces the burst; it is not
     * the debounce a person perceives, that is [PLAN_SETTLE_MS].
     */
    const val WRITE_BURST_MS = 1_000L

    /**
     * The number of the sync-day [nowMs] falls in.
     *
     * Days are counted rather than named so that the comparison in
     * [morningSyncDue] is arithmetic on a plain integer with no calendar, locale
     * or formatting in it. [utcOffsetSeconds] is the phone's offset *at that
     * instant*, so a day that is 23 or 25 hours long because of daylight saving
     * is still one day.
     */
    fun syncDay(nowMs: Long, utcOffsetSeconds: Int): Long {
        val localSeconds = nowMs.floorDiv(1000L) + utcOffsetSeconds - DAY_STARTS_AT_HOUR * 3600L
        return localSeconds.floorDiv(86_400L)
    }

    /**
     * Whether opening the app now is the first open of a new sync-day.
     *
     * [lastSyncedDay] is the day of the last sync this rule *counted* — null if
     * there has never been one. `!=` and not `>`: a phone whose clock is
     * corrected backwards, or that flew west, would otherwise be locked out of
     * its morning sync until the calendar caught up with a day it had already
     * recorded. One redundant sync costs a minute; a missed one costs the
     * sleep data the feature exists for.
     */
    fun morningSyncDue(nowMs: Long, utcOffsetSeconds: Int, lastSyncedDay: Long?): Boolean =
        lastSyncedDay == null || lastSyncedDay != syncDay(nowMs, utcOffsetSeconds)

    /**
     * One emission each time the plan has *changed* and then stopped changing.
     *
     * [writes] ticks on every local write of any kind — health imports and
     * activity files bump it as readily as a plan edit — so it says only "look
     * again". [fingerprint] reads the plan and says whether it is different
     * from last time; only a different answer counts.
     *
     * The first reading is the baseline and is dropped: the plan the app
     * started with is not a change. Anything that changed while the process
     * was not running is the job of the morning sync and the hourly worker,
     * which do not need to have watched it happen.
     */
    /**
     * How long the watch must have been out of reach for its return to be
     * worth a sync.
     *
     * The case this exists for is a run with the phone left at home: the watch
     * is gone for the length of the activity and comes back holding it. Ten
     * minutes is shorter than anything worth recording and longer than the
     * link's ordinary hiccups — a fenix drops and recovers BLE in under a
     * minute when it is merely in another room — so walking around the house
     * does not turn into a sync each time.
     */
    const val RETURN_ABSENCE_MS = 10 * 60_000L

    /**
     * One emission each time the watch comes back after at least [absenceMs]
     * away.
     *
     * [linkUp] is true while connected and false while the link is down;
     * intermediate states (connecting, handshaking) are the caller's to leave
     * out, so an attempt that never completes is neither a return nor a new
     * departure. The absence is measured from the moment the link *went* down,
     * so a connection the process starts with — when nobody saw it leave — is
     * never a return; the morning sync and the hourly worker cover a phone
     * that was off or had nothing running.
     *
     * This adds no radio work of its own. It only listens to connections that
     * something else (the link supervisor, a sync, the app being opened)
     * already made, which is the whole battery argument for doing it here
     * rather than by looking for the watch.
     */
    fun returnedAfterAbsence(
        linkUp: Flow<Boolean>,
        nowMs: () -> Long,
        absenceMs: Long = RETURN_ABSENCE_MS,
    ): Flow<Unit> = flow {
        var leftAt: Long? = null
        var up: Boolean? = null
        linkUp.collect { now ->
            if (now == up) return@collect
            val wasUp = up
            up = now
            if (!now) {
                if (wasUp == true) leftAt = nowMs()
                return@collect
            }
            val left = leftAt ?: return@collect
            leftAt = null
            if (nowMs() - left >= absenceMs) emit(Unit)
        }
    }

    @OptIn(FlowPreview::class)
    fun planSettled(
        writes: Flow<Long>,
        fingerprint: suspend () -> Int,
        settleMs: Long = PLAN_SETTLE_MS,
        burstMs: Long = WRITE_BURST_MS,
    ): Flow<Unit> = writes
        .debounce(burstMs)
        .map { fingerprint() }
        .distinctUntilChanged()
        .drop(1)
        .debounce(settleMs)
        .map { }
}
