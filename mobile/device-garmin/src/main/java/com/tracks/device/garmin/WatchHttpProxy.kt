// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Performs an HTTP request on the watch's behalf.
 *
 * The watch cannot reach the network by itself except during a Wi-Fi sync; the
 * rest of the time a Connect IQ `makeWebRequest` is a message to the phone
 * asking it to make the call. This is the call. It runs on its own thread
 * because the request arrives on the BLE thread, which must not block, and
 * reports back through a callback the caller turns into a protobuf reply.
 *
 * Deliberately plain `HttpURLConnection`: this module has no HTTP client
 * dependency, and one sequential request at a time needs none.
 */
object WatchHttpProxy {
    private const val TAG = "WatchHttpProxy"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Headers that describe the BLE hop, not the request; never forwarded. */
    private val HOP_BY_HOP = setOf("host", "content-length", "connection", "accept-encoding", "transfer-encoding")

    class Result(
        val status: Int,
        /** Lower-cased names. */
        val headers: Map<String, String>,
        val body: ByteArray,
        /** The body exceeded what the watch said it could take; [body] is empty. */
        val tooLarge: Boolean = false,
    )

    /** Most hops a redirect chain may take before it is judged a loop. */
    private const val MAX_REDIRECTS = 3

    /**
     * A URL safe to write to the log: scheme, host, port and path only.
     *
     * Never the query string. The watch signs its requests in the query —
     * `jwt=` for Navidrome's own API, `t=` and `s=` for Subsonic — so logging a
     * whole URL wrote the user's music-server credentials into logcat, where
     * adb, a bug report or anything holding READ_LOGS can read them. Found by
     * reading back a capture taken while debugging this proxy and seeing a
     * complete JWT in it.
     */
    private fun redacted(url: String): String = try {
        val u = URL(url)
        val port = if (u.port == -1) "" else ":${u.port}"
        "${u.protocol}://${u.host}$port${u.path}"
    } catch (_: Exception) {
        "(unparseable url)"
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "watch-http-proxy").apply { isDaemon = true }
    }

    /**
     * Fetch [url] and hand the outcome to [onDone] on the proxy thread; null
     * means the request could not be made at all.
     */
    fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        maxBody: Int,
        onDone: (Result?) -> Unit,
    ) {
        executor.execute {
            onDone(
                try {
                    perform(method, url, headers, body, maxBody)
                } catch (e: Exception) {
                    Log.w(TAG, "watch request failed: $method ${redacted(url)}", e)
                    null
                },
            )
        }
    }

    private fun perform(method: String, url: String, headers: Map<String, String>, body: ByteArray, maxBody: Int): Result {
        var target = url
        var hops = 0
        while (true) {
            val result = attempt(method, target, headers, body, maxBody)
            val location = result.redirectTo
            if (location == null) {
                return result.value!!
            }
            // Follow by hand, checking each hop, because the allow-list was
            // applied to the URL the watch asked for and nothing else. Letting
            // HttpURLConnection chase a Location meant one redirect from the
            // music server — or from anyone able to answer as it over plain
            // http — could point this at a router's admin page or a metadata
            // service and hand the body back to the watch. That is exactly the
            // open proxy this class refuses to be.
            if (++hops > MAX_REDIRECTS || !WatchAppConfig.isProxyable(location)) {
                Log.w(TAG, "refusing redirect to ${redacted(location)}")
                throw IllegalStateException("redirect outside the configured music server")
            }
            target = location
        }
    }

    /** One hop. Either a finished [Result], or where the server sent us. */
    private class Attempt(val value: Result?, val redirectTo: String?)

    private fun attempt(method: String, url: String, headers: Map<String, String>, body: ByteArray, maxBody: Int): Attempt {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            for ((name, value) in headers) {
                if (name.lowercase(Locale.ROOT) !in HOP_BY_HOP) connection.setRequestProperty(name, value)
            }
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                if (location != null) {
                    return Attempt(null, URL(URL(url), location).toString())
                }
            }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val out = ByteArrayOutputStream()
            var tooLarge = false
            if (stream != null) {
                stream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > maxBody) {
                            tooLarge = true
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            }
            val responseHeaders = LinkedHashMap<String, String>()
            for ((name, values) in connection.headerFields) {
                if (name != null && values.isNotEmpty()) responseHeaders[name.lowercase(Locale.ROOT)] = values[0]
            }
            Log.d(TAG, "$method ${redacted(url)} -> $status, ${out.size()} bytes${if (tooLarge) " (too large)" else ""}")
            return Attempt(Result(status, responseHeaders, if (tooLarge) ByteArray(0) else out.toByteArray(), tooLarge), null)
        } finally {
            connection.disconnect()
        }
    }
}
