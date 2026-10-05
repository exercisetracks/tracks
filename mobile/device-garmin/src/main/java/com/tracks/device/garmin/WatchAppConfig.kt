// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the on-watch music app needs to know: which music server, as whom, and
 * — optionally — which playlists to keep on the watch.
 *
 * ## Why this is served over the watch's HTTP proxy
 *
 * The watch app has to learn where its music server is, and every obvious way
 * of telling it is bad. The Connect IQ Store would mean publishing the app and
 * involving Garmin's servers in an install that has no other reason to touch
 * them. Garmin Connect's app-settings screen means the user typing a URL and a
 * password on a phone keyboard. `AppConfigService` can write app settings over
 * BLE, but its payload encoding is undocumented.
 *
 * The proxy needs none of that. The watch already asks the phone to fetch URLs
 * for it; the app asks for one known URL, and the phone answers from values it
 * already holds. Nothing is published, nothing is typed, and the request never
 * leaves the phone — see [nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.http.HttpHandler].
 *
 * ## Why the password
 *
 * The watch talks to Navidrome directly, with no Tracks server in the path, and
 * Navidrome's own API — which the watch needs, because Subsonic's cannot page a
 * playlist into pieces small enough for a watch — is only reachable through a
 * login. So the watch holds the password. It goes to exactly one server, over
 * TLS, and it is the credential to one music library.
 *
 * ## What else the proxy may reach
 *
 * The same [serverUrl] is the one host the phone will proxy requests to, so
 * the watch can list playlists over Bluetooth when it is not on Wi-Fi. That is
 * the whole allow-list: the music server the user configured, and nothing
 * else.
 *
 * ## Why a revision
 *
 * The watch asks every time its app opens and before every sync, and it may
 * also have an account typed on the watch itself. Obeying every answer would
 * overwrite that account each time. So each answer carries the phone's
 * [Config.revision] — a number that only moves when the phone's music settings
 * change — and the watch applies an answer only when the number is newer than
 * the last one it applied. The number is kept by the app (it has to outlive
 * this process); this object only serves it.
 *
 * ## Why a process-wide holder
 *
 * The class that answers the proxy is deep in vendored Gadgetbridge code,
 * reached from the BLE thread with no route back to a Tracks object. One
 * volatile holder set by [GarminIntegration] is the smaller change.
 */
object WatchAppConfig {

    /** Host the watch app asks for. `.invalid` is reserved (RFC 2606) and can
     * never resolve, so a request that somehow escapes the phone fails rather
     * than reaching a real server. */
    const val HOST = "tracks.invalid"

    /** Full path the app requests. Matched exactly; nothing else is served. */
    const val PATH = "/ciq/config"

    data class Config(
        val serverUrl: String,
        val username: String,
        val password: String,
        /** Playlist ids to keep on the watch, or null to leave the watch's own choice alone. */
        val playlists: List<String>? = null,
        /** Single songs to add to the watch's picked list, as (songId, albumId). */
        val songs: List<Pair<String, String?>>? = null,
        /** The phone's music-settings revision; see "Why a revision" above. */
        val revision: Long = 0L,
    )

    /**
     * How long the phone will answer the config request after being armed.
     *
     * The request carries no proof of who is asking. A Connect IQ web request
     * reaches the phone as a URL and nothing else — the protocol has no field
     * identifying the app — so any app on the watch that asks for this URL
     * gets whatever is handed back, and what is handed back is the music
     * server's username and password. Sandboxing keeps apps out of each
     * other's storage; it does not stop them making web requests.
     *
     * There is no way to authenticate the caller, so the exposure is bounded
     * in time instead: the phone answers only for a short while after the user
     * did something that implies a watch is being set up — installing the app,
     * or opening the music screen. Outside that window the watch keeps the
     * configuration it already stored, so the cost of being wrong is a
     * password change that needs the music screen opened once. Only the
     * revision is answered outside the window — a number, not a secret — and
     * the watch does not count it as applied until an armed answer brings the
     * settings that go with it.
     */
    private const val ARMED_FOR_MS = 10 * 60 * 1000L

    @Volatile
    private var current: Config? = null

    @Volatile
    private var armedAt: Long = 0L

    /** Set while the phone has no music server: the revision at which it went. */
    @Volatile
    private var signedOutAt: Long? = null

    /**
     * Called (on the BLE thread) after a config carrying a playlist selection
     * was handed to the watch. The selection is one-shot — see [served] — and
     * this is how the app learns it has been delivered.
     */
    @Volatile
    var onPlaylistsServed: (() -> Unit)? = null

    fun set(config: Config) {
        current = config.copy(serverUrl = config.serverUrl.trimEnd('/'))
        signedOutAt = null
        armedAt = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * The phone has no music server, as of [revision]. The watch is told so —
     * and signs out, deleting its downloads — if it last applied an older
     * revision. Revision 0 means the phone never had a server at all, and is
     * not announced: a phone that simply has not been set up is not a reason
     * for a watch to drop an account typed on it.
     */
    fun signOut(revision: Long) {
        current = null
        armedAt = 0L
        signedOutAt = revision.takeIf { it > 0L }
    }

    /** Forget everything; the watch gets no answer at all. */
    fun clear() {
        current = null
        armedAt = 0L
        signedOutAt = null
    }

    /** True while the phone is still willing to hand the config over. */
    fun armed(): Boolean {
        if (current == null || armedAt == 0L) return false
        return android.os.SystemClock.elapsedRealtime() - armedAt < ARMED_FOR_MS
    }

    fun get(): Config? = current

    /**
     * The response body, or null when the phone has nothing to say.
     *
     * Three shapes. Armed: the whole config. Configured but not armed: only
     * `{"revision", "server": true}` — which carries nothing secret, so it is
     * answered to anyone at any time; it lets the watch learn what revision
     * the phone is on without the password leaving the phone. No server:
     * `{"revision", "server": false}`, which is what signs a watch out.
     */
    fun asJson(): String? {
        val config = current
        if (config == null) {
            val revision = signedOutAt ?: return null
            return JSONObject().put("revision", revision).put("server", false).toString()
        }
        val json = JSONObject()
            .put("revision", config.revision)
            .put("server", true)
        if (!armed()) return json.toString()
        json.put("url", config.serverUrl)
            .put("user", config.username)
            .put("password", config.password)
        config.playlists?.let { json.put("playlists", JSONArray(it)) }
        config.songs?.let { songs ->
            json.put("songs", JSONArray(songs.map { (id, album) -> JSONArray(listOf(id, album ?: JSONObject.NULL)) }))
        }
        return json.toString()
    }

    /**
     * What to answer the watch with, now — and, if that answer carried the
     * selection, mark it delivered. One call rather than [asJson] then
     * [served], so an answer given just as the arming window closed cannot
     * mark a selection delivered that it did not carry.
     */
    @Synchronized
    fun respond(): String? {
        val handedOver = armed()
        val json = asJson()
        if (json != null && handedOver) served()
        return json
    }

    /**
     * The config has just been handed to the watch. A playlist selection goes
     * over once: the watch has its own list the user can edit on the wrist,
     * and a stale selection re-sent on every visit would keep overwriting it.
     */
    fun served() {
        val config = current ?: return
        if (config.playlists == null && config.songs == null) return
        current = config.copy(playlists = null, songs = null)
        onPlaylistsServed?.invoke()
    }

    /** True when [url] is the one request this phone will answer locally. */
    fun matches(url: String?): Boolean {
        if (url == null) return false
        return url.contains(HOST) && url.contains(PATH)
    }

    /** True when [url] points at the configured music server: same scheme,
     * host and port, nothing looser. */
    fun isProxyable(url: String?): Boolean {
        if (url == null) return false
        val target = Uri.parse(url)
        val server = Uri.parse(current?.serverUrl ?: return false)
        return target.scheme != null &&
            target.scheme.equals(server.scheme, ignoreCase = true) &&
            target.host != null &&
            target.host.equals(server.host, ignoreCase = true) &&
            target.port == server.port
    }
}
