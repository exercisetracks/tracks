// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * The config the phone hands the watch, and — more importantly — the two
 * matchers that decide which requests get an answer at all.
 *
 * Tracks refuses to fetch anything on the watch's behalf except for the one
 * music server the user configured. These matchers are a security boundary:
 * matching too loosely would turn a deliberate allow-list back into an open
 * proxy.
 *
 * Robolectric for a real `org.json`: the stub android.jar on the unit-test
 * classpath throws from every JSONObject method.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class WatchAppConfigTest {

    private val config = WatchAppConfig.Config(
        serverUrl = "https://music.example.com",
        username = "alex",
        password = "hunter2",
    )

    @AfterTest
    fun tearDown() {
        WatchAppConfig.clear()
        WatchAppConfig.onPlaylistsServed = null
    }

    @Test
    fun `the config round-trips as json`() {
        WatchAppConfig.set(config)

        val json = JSONObject(WatchAppConfig.asJson()!!)

        assertEquals("https://music.example.com", json.getString("url"))
        assertEquals("alex", json.getString("user"))
        assertEquals("hunter2", json.getString("password"))
        assertFalse(json.has("playlists"))
    }

    @Test
    fun `a trailing slash is trimmed`() {
        // Otherwise the watch builds "https://host//rest/…", which some reverse
        // proxies answer with a redirect that Connect IQ will not follow.
        WatchAppConfig.set(config.copy(serverUrl = "https://music.example.com/"))

        assertEquals("https://music.example.com", JSONObject(WatchAppConfig.asJson()!!).getString("url"))
    }

    @Test
    fun `nothing is served before the phone has been configured`() {
        assertNull(WatchAppConfig.asJson())
        assertNull(WatchAppConfig.get())
    }

    @Test
    fun `clearing revokes what the phone will answer`() {
        WatchAppConfig.set(config)

        WatchAppConfig.clear()

        assertNull(WatchAppConfig.asJson())
    }

    @Test
    fun `a playlist selection goes over once`() {
        // The watch has its own list the user edits on the wrist. Re-sending a
        // stale selection on every visit would keep overwriting it.
        var notified = 0
        WatchAppConfig.onPlaylistsServed = { notified++ }
        WatchAppConfig.set(config.copy(playlists = listOf("~starred", "abc")))

        val first = JSONObject(WatchAppConfig.asJson()!!)
        WatchAppConfig.served()
        val second = JSONObject(WatchAppConfig.asJson()!!)

        assertEquals(listOf("~starred", "abc"), List(first.getJSONArray("playlists").length()) { first.getJSONArray("playlists").getString(it) })
        assertFalse(second.has("playlists"))
        assertEquals("hunter2", second.getString("password"))
        assertEquals(1, notified)
    }

    @Test
    fun `picked songs travel as id-album pairs and go over once too`() {
        WatchAppConfig.set(config.copy(songs = listOf("s1" to "a1", "s2" to null)))

        val json = JSONObject(WatchAppConfig.asJson()!!)
        val songs = json.getJSONArray("songs")
        assertEquals("s1", songs.getJSONArray(0).getString(0))
        assertEquals("a1", songs.getJSONArray(0).getString(1))
        assertTrue(songs.getJSONArray(1).isNull(1))

        WatchAppConfig.served()
        assertFalse(JSONObject(WatchAppConfig.asJson()!!).has("songs"))
    }

    @Test
    fun `serving a config without a selection notifies nobody`() {
        var notified = 0
        WatchAppConfig.onPlaylistsServed = { notified++ }
        WatchAppConfig.set(config)

        WatchAppConfig.served()

        assertEquals(0, notified)
    }

    @Test
    fun `every answer carries the revision`() {
        // The watch applies an answer only when this is newer than what it last
        // applied. Without it, the watch could not tell a change on the phone
        // from the same settings asked for again.
        WatchAppConfig.set(config.copy(revision = 42L))

        val json = JSONObject(WatchAppConfig.asJson()!!)

        assertEquals(42L, json.getLong("revision"))
        assertTrue(json.getBoolean("server"))
    }

    @Test
    fun `once the window closes the phone says only which revision it is on`() {
        // The password is handed out only for a while after the user did
        // something on the phone. The revision is not secret, and still
        // answering it lets a watch that signed in on the wrist learn what the
        // phone is on without the password leaving the phone.
        WatchAppConfig.set(config.copy(revision = 42L))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))

        val json = JSONObject(WatchAppConfig.asJson()!!)

        assertEquals(42L, json.getLong("revision"))
        assertTrue(json.getBoolean("server"))
        assertFalse(json.has("password"))
        assertFalse(json.has("user"))
    }

    @Test
    fun `an answer without the password does not use up the selection`() {
        // The selection goes over once. An unarmed answer does not carry it,
        // so it must not count as delivered, or the watch would never get it.
        var notified = 0
        WatchAppConfig.onPlaylistsServed = { notified++ }
        WatchAppConfig.set(config.copy(playlists = listOf("abc"), revision = 1L))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))

        WatchAppConfig.respond()
        WatchAppConfig.set(config.copy(playlists = listOf("abc"), revision = 1L))
        val armed = JSONObject(WatchAppConfig.respond()!!)

        assertTrue(armed.has("playlists"))
        assertEquals(1, notified)
    }

    @Test
    fun `a phone whose music server was removed tells the watch to sign out`() {
        WatchAppConfig.set(config.copy(revision = 1L))

        WatchAppConfig.signOut(2L)

        val json = JSONObject(WatchAppConfig.respond()!!)
        assertEquals(2L, json.getLong("revision"))
        assertFalse(json.getBoolean("server"))
        assertFalse(json.has("password"))
        // And the proxy goes with it: nothing to reach on the watch's behalf.
        assertFalse(WatchAppConfig.isProxyable("https://music.example.com/api"))
    }

    @Test
    fun `a phone that never had a music server says nothing at all`() {
        // Revision 0 is a phone that was never set up, not one that changed.
        // Announcing it would sign out a watch using an account typed on it.
        WatchAppConfig.signOut(0L)

        assertNull(WatchAppConfig.respond())
    }

    @Test
    fun `connecting a server again replaces the sign-out answer`() {
        WatchAppConfig.signOut(2L)

        WatchAppConfig.set(config.copy(revision = 3L))

        val json = JSONObject(WatchAppConfig.respond()!!)
        assertTrue(json.getBoolean("server"))
        assertEquals("hunter2", json.getString("password"))
    }

    @Test
    fun `only the reserved config url matches`() {
        assertTrue(WatchAppConfig.matches("https://tracks.invalid/ciq/config"))
    }

    @Test
    fun `every other url is refused as the local answer`() {
        // Each of these is a request the watch genuinely makes. Answering any
        // of them from memory would be wrong in a different way each time.
        assertFalse(WatchAppConfig.matches("https://api.gcs.garmin.com/ephemeris/cpe/sony/lle"))
        assertFalse(WatchAppConfig.matches("https://services.garmin.com/weather"))
        assertFalse(WatchAppConfig.matches("https://tracks.invalid/something-else"))
        assertFalse(WatchAppConfig.matches("https://evil.example.com/ciq/config/../../"))
        assertFalse(WatchAppConfig.matches(null))
    }

    @Test
    fun `only the configured music server may be proxied`() {
        WatchAppConfig.set(config)

        assertTrue(WatchAppConfig.isProxyable("https://music.example.com/auth/login"))
        assertTrue(WatchAppConfig.isProxyable("https://MUSIC.example.com/api/playlist?_start=0"))

        // Not Garmin, not a look-alike, not a different port or scheme.
        assertFalse(WatchAppConfig.isProxyable("https://api.gcs.garmin.com/ephemeris/cpe/sony/lle"))
        assertFalse(WatchAppConfig.isProxyable("https://music.example.com.evil.net/api"))
        assertFalse(WatchAppConfig.isProxyable("https://music.example.com:8443/api"))
        assertFalse(WatchAppConfig.isProxyable("http://music.example.com/api"))
        assertFalse(WatchAppConfig.isProxyable(null))
    }

    @Test
    fun `nothing is proxied before the phone has been configured`() {
        assertFalse(WatchAppConfig.isProxyable("https://music.example.com/api"))
    }

    @Test
    fun `the host is a reserved name that cannot resolve`() {
        // RFC 2606 reserves .invalid precisely so it can never be registered,
        // so a request that somehow escaped the phone would fail rather than
        // reach a real server.
        assertTrue(WatchAppConfig.HOST.endsWith(".invalid"))
    }
}
