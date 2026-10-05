// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import com.tracks.app.feeds.WeatherSources.History
import com.tracks.app.feeds.WeatherSources.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Settings and onboarding say about the watch's weather, from what is
 * installed and what has actually arrived. Settings used to read only the
 * in-memory forecast, so every process restart turned "receiving" into "has
 * not sent a forecast yet".
 */
class WeatherSourcesTest {

    private val now = 1_790_830_000_000L
    private val hour = 3_600_000L
    private val breezy = WeatherSources.PROVIDERS.first { it.recommended }
    private val quick = WeatherSources.PROVIDERS.first { it.packageName == "com.ominous.quickweather" }
    private fun label(ms: Long) = "T-${(now - ms) / 60_000}m"

    @Test
    fun `a forecast in the last few hours is receiving, across restarts`() {
        val s = WeatherSources.status(listOf(breezy), History(now - hour, "Spokane", null), now, ::label)
        assertEquals(State.RECEIVING, s.state)
        assertTrue(s.text, s.text.contains("Breezy Weather") && s.text.contains("Spokane"))
    }

    @Test
    fun `a forecast that stopped says so and where to look`() {
        val s = WeatherSources.status(listOf(breezy), History(now - 10 * hour, null, null), now, ::label)
        assertEquals(State.STALE, s.state)
        assertTrue(s.text.contains("External modules"))
    }

    @Test
    fun `breezy installed but silent points at its sending switch, not at other apps`() {
        val s = WeatherSources.status(listOf(breezy), History(null, null, null), now, ::label)
        assertEquals(State.WAITING, s.state)
        assertEquals(breezy, s.provider)
        assertTrue(s.text.contains("Send Gadgetbridge data"))
    }

    @Test
    fun `breezy is preferred when several senders are installed`() {
        assertEquals(breezy, WeatherSources.status(listOf(quick, breezy), History(null, null, null), now, ::label).provider)
    }

    @Test
    fun `with no app the server's fallback is named, and breezy recommended`() {
        val s = WeatherSources.status(emptyList(), History(null, null, now - hour), now, ::label)
        assertEquals(State.SERVER_ONLY, s.state)
        assertTrue(s.text.contains("Breezy Weather"))
        assertEquals(State.NO_APP, WeatherSources.status(emptyList(), History(null, null, null), now, ::label).state)
    }
}
