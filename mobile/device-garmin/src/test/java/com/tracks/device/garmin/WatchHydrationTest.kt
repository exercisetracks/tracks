// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch's hydration widget answered from Tracks: the Connect calls it
 * makes through the phone (as Gadgetbridge 0.95's HydrationInterceptor
 * established them) and the push that brings it up to date. A wrong answer
 * here is a drink that never reaches Tracks, or a widget stuck on a number
 * from this morning. Robolectric for the platform's org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WatchHydrationTest {

    private class FakeStore : WatchHydration.Store {
        val days = HashMap<LocalDate, Double>()
        var last: LocalDateTime? = null
        override fun totalMl(date: LocalDate) = days[date] ?: 0.0
        override fun addMl(at: LocalDateTime, ml: Double): Double {
            last = at
            return (totalMl(at.toLocalDate()) + ml).also { days[at.toLocalDate()] = it }
        }
        override fun lastEntry(date: LocalDate) = last?.takeIf { it.toLocalDate() == date }
        override val goalMl = 2000.0
        override var watchUsesIt = false
    }

    private lateinit var store: FakeStore
    private val today = LocalDate.of(2026, 10, 20)
    private val base = "https://connectapi.garmin.com/usersummary-service/usersummary/hydration/"

    @BeforeTest
    fun setUp() {
        store = FakeStore()
        WatchHydration.store = store
    }

    @AfterTest
    fun tearDown() {
        WatchHydration.store = null
    }

    @Test
    fun `a drink logged on the watch is added to the day`() {
        store.days[today] = 500.0
        // The watch's own timestamp shape: no zero padding, a fraction.
        val body = """{"timestampLocal":"2026-10-20T9:05:03.0","valueInML":250.0,"calendarDate":"2026-10-20"}"""
        val reply = JSONObject(WatchHydration.answer("PUT", base + "log", body.toByteArray())!!)
        assertEquals(750.0, store.days[today])
        assertEquals(750.0, reply.getDouble("valueInML"))
        assertEquals(2000.0, reply.getDouble("goalInML"))
        assertEquals("2026-10-20T09:05:03.0", reply.getString("lastEntryTimestampLocal"))
        assertTrue(store.watchUsesIt, "the phone pushes only to a watch whose widget has synced")
    }

    @Test
    fun `the day's summary is the phone's total`() {
        store.days[today] = 1200.0
        val reply = JSONObject(WatchHydration.answer("GET", base + "daily/2026-10-20", ByteArray(0))!!)
        assertEquals("2026-10-20", reply.getString("calendarDate"))
        assertEquals(1200.0, reply.getDouble("valueInML"))
        assertTrue(reply.isNull("lastEntryTimestampLocal"), "no drink logged here yet today")
    }

    @Test
    fun `settings name the containers in millilitres`() {
        val url = "https://connectapi.garmin.com/userprofile-service/userprofile/user-settings/hydration"
        val reply = JSONObject(WatchHydration.answer("GET", url, ByteArray(0))!!)
        assertEquals("milliliter", reply.getString("hydrationMeasurementUnit"))
        assertEquals(3, reply.getJSONArray("hydrationContainers").length())
    }

    @Test
    fun `only the widget's calls are taken`() {
        assertTrue(WatchHydration.handles(base + "log"))
        assertTrue(WatchHydration.handles("https://connectapi.garmin.com/pushnotification-service/ciq/hydration/register"))
        assertFalse(WatchHydration.handles("https://connectapi.garmin.com/weather-service/forecast"))
        assertFalse(WatchHydration.handles("https://evil.example/usersummary-service/usersummary/hydration/log"))
    }

    @Test
    fun `the push carries the day's total and goal`() {
        store.days[today] = 900.0
        val data = JSONObject(WatchHydration.updatePayload(today)!!).getJSONObject("data")
        assertEquals("hydration_update", data.getString("ciqPayloadType"))
        assertEquals(900.0, data.getDouble("dailyHydrationTotal"))
        assertEquals(2000, data.getInt("dailyHydrationGoal"))
        assertEquals("2026-10-20", data.getString("calendarDate"))
    }

    @Test
    fun `nothing is answered before the app provides the day`() {
        WatchHydration.store = null
        assertNull(WatchHydration.answer("GET", base + "daily/2026-10-20", ByteArray(0)))
    }
}
