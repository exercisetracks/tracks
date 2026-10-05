// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two rules behind syncing the watch unprompted: once on the first open of
 * each morning, and once after the plan has stopped changing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartSyncPolicyTest {

    private val hour = 3_600_000L

    // 2026-09-30T00:00:00Z
    private val midnightUtc = 1_790_726_400_000L

    // ── The morning sync ─────────────────────────────────────────────────────

    @Test
    fun `the very first open ever is a morning sync`() {
        assertTrue(SmartSyncPolicy.morningSyncDue(midnightUtc + 7 * hour, 0, lastSyncedDay = null))
    }

    @Test
    fun `a second open on the same morning does not sync again`() {
        val day = SmartSyncPolicy.syncDay(midnightUtc + 7 * hour, 0)
        assertFalse(SmartSyncPolicy.morningSyncDue(midnightUtc + 9 * hour, 0, day))
    }

    @Test
    fun `the next morning is due again`() {
        val yesterday = SmartSyncPolicy.syncDay(midnightUtc - 17 * hour, 0)
        assertTrue(SmartSyncPolicy.morningSyncDue(midnightUtc + 7 * hour, 0, yesterday))
    }

    @Test
    fun `opening the app after midnight but before four still belongs to the previous day`() {
        // A 00:30 glance at the phone is the tail of yesterday, not a morning:
        // it must not use up the sync the person wants when they wake.
        val evening = SmartSyncPolicy.syncDay(midnightUtc - 2 * hour, 0)
        assertFalse(SmartSyncPolicy.morningSyncDue(midnightUtc + 30 * 60_000L, 0, evening))
        assertTrue(SmartSyncPolicy.morningSyncDue(midnightUtc + 4 * hour, 0, evening))
    }

    @Test
    fun `the day rolls over at four local time, not four UTC`() {
        // 03:30 in UTC+10 is 17:30 UTC the evening before.
        val tenHours = 10 * 3600
        val beforeFour = midnightUtc - 6 * hour - 30 * 60_000L    // 17:30Z = 03:30 local
        val afterFour = midnightUtc - 6 * hour + 30 * 60_000L     // 18:30Z = 04:30 local
        assertEquals(
            SmartSyncPolicy.syncDay(beforeFour, tenHours) + 1,
            SmartSyncPolicy.syncDay(afterFour, tenHours),
        )
    }

    @Test
    fun `a clock moved backwards does not lock the morning sync out`() {
        // A phone that flew west reads an earlier day than the one recorded.
        val recordedTomorrow = SmartSyncPolicy.syncDay(midnightUtc + 30 * hour, 0)
        assertTrue(SmartSyncPolicy.morningSyncDue(midnightUtc + 7 * hour, 0, recordedTomorrow))
    }

    // ── The plan-change sync ─────────────────────────────────────────────────

    /** Collects triggers, recording the virtual time each one arrived. */
    private fun kotlinx.coroutines.test.TestScope.collect(
        writes: MutableStateFlow<Long>,
        plan: () -> Int,
        into: MutableList<Long>,
    ) = backgroundScope.launch {
        SmartSyncPolicy.planSettled(writes, { plan() }, settleMs = 90_000, burstMs = 1_000)
            .collect { into += currentTime }
    }

    @Test
    fun `starting up with a plan already in place is not a change`() = runTest {
        val writes = MutableStateFlow(0L)
        val fired = mutableListOf<Long>()
        collect(writes, { 7 }, fired)

        advanceTimeBy(10 * 60_000)
        assertTrue(fired.isEmpty(), "the baseline reading fired a sync: $fired")
    }

    @Test
    fun `an edit fires once, after the plan has been still for the settle time`() = runTest {
        val writes = MutableStateFlow(0L)
        var plan = 1
        val fired = mutableListOf<Long>()
        collect(writes, { plan }, fired)
        advanceTimeBy(5_000)                 // baseline taken

        plan = 2
        writes.value++                       // the edit, at t = 5 s
        advanceTimeBy(60_000)
        assertTrue(fired.isEmpty(), "fired before the plan had settled: $fired")

        advanceTimeBy(40_000)
        assertEquals(1, fired.size)
    }

    @Test
    fun `several edits in one sitting make one sync, after the last`() = runTest {
        val writes = MutableStateFlow(0L)
        var plan = 1
        val fired = mutableListOf<Long>()
        collect(writes, { plan }, fired)
        advanceTimeBy(5_000)

        // Four edits, thirty seconds apart: each restarts the clock.
        repeat(4) {
            plan++
            writes.value++
            advanceTimeBy(30_000)
        }
        assertTrue(fired.isEmpty(), "a sync ran mid-session: $fired")

        advanceTimeBy(120_000)
        assertEquals(1, fired.size, "expected exactly one sync for the whole session: $fired")
    }

    @Test
    fun `writes that do not touch the plan never fire a sync`() = runTest {
        // A watch sync writes sleep and activity rows; if that counted as a plan
        // change every sync would schedule the next one, forever.
        val writes = MutableStateFlow(0L)
        val fired = mutableListOf<Long>()
        collect(writes, { 7 }, fired)
        advanceTimeBy(5_000)

        repeat(5) {
            writes.value++
            advanceTimeBy(10_000)
        }
        advanceTimeBy(10 * 60_000)
        assertTrue(fired.isEmpty(), "unrelated writes fired a sync: $fired")
    }

    @Test
    fun `a burst of writes from one regenerate is read once, not once per row`() = runTest {
        val writes = MutableStateFlow(0L)
        var reads = 0
        val fired = mutableListOf<Long>()
        backgroundScope.launch {
            SmartSyncPolicy.planSettled(writes, { reads++; 1 }, settleMs = 90_000, burstMs = 1_000)
                .collect { fired += currentTime }
        }
        advanceTimeBy(5_000)
        val baselineReads = reads

        repeat(40) {
            writes.value++
            advanceTimeBy(10)
        }
        advanceTimeBy(5_000)
        assertEquals(1, reads - baselineReads)
    }

    // ── The watch coming back ────────────────────────────────────────────────

    private fun kotlinx.coroutines.test.TestScope.watchReturns(
        link: kotlinx.coroutines.flow.Flow<Boolean>,
        into: MutableList<Long>,
    ) = backgroundScope.launch {
        SmartSyncPolicy.returnedAfterAbsence(link, { currentTime }, absenceMs = 10 * 60_000)
            .collect { into += currentTime }
    }

    @Test
    fun `a watch back from a run syncs as it reconnects`() = runTest {
        val link = MutableStateFlow(true)
        val fired = mutableListOf<Long>()
        watchReturns(link, fired)
        advanceTimeBy(1_000)

        link.value = false                   // out the door
        advanceTimeBy(40 * 60_000)
        link.value = true                    // home again
        advanceTimeBy(1_000)

        assertEquals(1, fired.size)
    }

    @Test
    fun `a brief drop in the next room does not sync`() = runTest {
        val link = MutableStateFlow(true)
        val fired = mutableListOf<Long>()
        watchReturns(link, fired)
        advanceTimeBy(1_000)

        repeat(5) {
            link.value = false
            advanceTimeBy(60_000)
            link.value = true
            advanceTimeBy(60_000)
        }
        assertTrue(fired.isEmpty(), "short drops fired a sync: $fired")
    }

    @Test
    fun `the connection a process starts with is not a return`() = runTest {
        // Nobody saw the watch leave, so there is no absence to measure — the
        // morning sync and the hourly worker are what cover a cold start.
        val link = MutableStateFlow(false)
        val fired = mutableListOf<Long>()
        watchReturns(link, fired)
        advanceTimeBy(60 * 60_000)
        link.value = true
        advanceTimeBy(1_000)

        assertTrue(fired.isEmpty(), "a cold-start connect fired a sync: $fired")
    }

    @Test
    fun `failed reconnect attempts while away do not restart the absence`() = runTest {
        // The link supervisor retries every few minutes while the watch is out
        // of range. Each failure reports "down" again; if that reset the clock,
        // a long run would look like a string of short absences and never sync.
        // A SharedFlow, not a StateFlow: the repeated "down" has to reach the
        // policy for this to test anything.
        val link = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(replay = 1)
        val fired = mutableListOf<Long>()
        watchReturns(link, fired)
        link.emit(true)
        advanceTimeBy(1_000)

        link.emit(false)
        repeat(8) {
            advanceTimeBy(5 * 60_000)
            link.emit(false)
        }
        link.emit(true)
        advanceTimeBy(1_000)

        assertEquals(1, fired.size)
    }
}
