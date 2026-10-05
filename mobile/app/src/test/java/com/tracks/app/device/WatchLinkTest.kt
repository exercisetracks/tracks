// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import com.tracks.device.ConnectionState
import com.tracks.device.PairedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconnect supervisor's behaviour, without a watch or a clock.
 *
 * [WatchLink] takes its sleep function as a parameter precisely so this can
 * record the delays instead of waiting them out — the property worth checking
 * is the *shape* of the backoff, and a test that actually slept through it
 * would take five minutes to assert one number.
 *
 * The recorded sleep still calls `delay`, which under [runTest] costs no real
 * time but does advance the virtual clock. That matters: without a suspension
 * point the retry loop spins, the scheduler never becomes idle, and the
 * enclosing `withTimeoutOrNull` can never fire — the test hangs rather than
 * failing. Recording alone is not enough; the loop has to actually yield.
 */
// Robolectric only for a working android.util.Log, and explicitly *not* the
// real TracksApplication — its onCreate schedules WorkManager, which has no
// business booting for a test of a retry loop.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WatchLinkTest {

    private val watch = PairedDevice(address = "AA:BB", name = "fenix", vendorId = "garmin")

    /**
     * Ten minutes of *virtual* time, which costs no real time under [runTest].
     * Long enough for the backoff to climb past its cap, which is the thing
     * being asserted.
     */
    private val VIRTUAL_BUDGET_MS = 10 * 60 * 1000L

    @Test
    fun `retries with growing delays while the watch is unreachable`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val waits = mutableListOf<Long>()
        var attempts = 0

        val link = WatchLink(
            connection = state,
            connect = {
                attempts++
                throw IllegalStateException("out of range")
            },
            sleep = { waits += it; kotlinx.coroutines.delay(it) },
            jitter = { 0 },
        )

        // The loop never returns on its own here, so cap it.
        withTimeoutOrNull(VIRTUAL_BUDGET_MS) { link.keepConnected() }

        assertTrue(attempts > 3, "expected repeated attempts, got $attempts")
        // Strictly increasing until the cap, then flat.
        val growth = waits.take(5)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L), growth)
        assertTrue(
            waits.all { it <= WatchLink.MAX_BACKOFF_MS },
            "backoff exceeded the cap: ${waits.maxOrNull()}",
        )
    }

    @Test
    fun `a successful connection resets the backoff`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val waits = mutableListOf<Long>()
        var attempt = 0

        val link = WatchLink(
            connection = state,
            connect = {
                attempt++
                when (attempt) {
                    // Fail twice so the delay has grown, then connect, then
                    // drop again — the next delay must start from the floor
                    // rather than continuing the previous climb.
                    1, 2 -> throw IllegalStateException("out of range")
                    3 -> {
                        state.value = ConnectionState.Connected(watch)
                        state.value = ConnectionState.Disconnected
                        true
                    }
                    else -> throw IllegalStateException("out of range")
                }
            },
            sleep = { waits += it; kotlinx.coroutines.delay(it) },
            jitter = { 0 },
        )

        withTimeoutOrNull(VIRTUAL_BUDGET_MS) { link.keepConnected() }

        assertEquals(2_000L, waits[0])
        assertEquals(4_000L, waits[1])
        // The delay after the reconnect-then-drop is back at the floor.
        assertEquals(2_000L, waits[2])
    }

    @Test
    fun `stops entirely when no watch is paired`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        var attempts = 0

        val link = WatchLink(
            connection = state,
            // false, not an exception: "nothing paired" is not a failure to
            // retry, and a supervisor that kept retrying would wake the radio
            // every five minutes forever on a phone with no watch.
            connect = { attempts++; false },
            sleep = { error("should not sleep") },
            jitter = { 0 },
        )

        val finished = withTimeoutOrNull(VIRTUAL_BUDGET_MS) { link.keepConnected(); true }

        assertEquals(true, finished, "the supervisor should return, not spin")
        assertEquals(1, attempts)
    }

    @Test
    fun `does not reconnect while the link is already up`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected(watch))
        var attempts = 0

        val link = WatchLink(
            connection = state,
            connect = { attempts++; true },
            sleep = {},
            jitter = { 0 },
        )

        withTimeoutOrNull(VIRTUAL_BUDGET_MS) { link.keepConnected() }

        assertEquals(0, attempts, "a connected watch should not be reconnected")
    }

    /** Initializing is mid-handshake — connecting again would race it. */
    @Test
    fun `does not reconnect during the handshake`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Initializing(watch))
        var attempts = 0

        val link = WatchLink(
            connection = state,
            connect = { attempts++; true },
            sleep = {},
            jitter = { 0 },
        )

        withTimeoutOrNull(VIRTUAL_BUDGET_MS) { link.keepConnected() }

        assertEquals(0, attempts)
    }

    /**
     * Bluetooth back on is the one change the backoff cannot see. Without a
     * wake, someone who switched it off and on again waited up to five minutes
     * for the watch to come back.
     */
    @Test
    fun `bluetooth coming back on retries at once with a fresh backoff`() = runTest {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val wake = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val attemptsAt = mutableListOf<Long>()
        val waits = mutableListOf<Long>()

        val link = WatchLink(
            connection = state,
            connect = {
                attemptsAt += currentTime
                throw IllegalStateException("bluetooth off")
            },
            sleep = { waits += it; kotlinx.coroutines.delay(it) },
            jitter = { 0 },
            wake = wake,
        )
        val job = backgroundScope.launch { link.keepConnected() }

        advanceTimeBy(70_000)                 // backoff has climbed to 64 s
        val before = attemptsAt.size
        wake.emit(Unit)                       // Bluetooth on
        advanceTimeBy(100)
        assertEquals(before + 1, attemptsAt.size, "no immediate retry after the wake")
        assertEquals(2_000L, waits.last(), "backoff did not start over")
        job.cancel()
    }
}

