// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin

import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch's hydration widget, answered from Tracks instead of Garmin Connect.
 *
 * The widget keeps the day's water in step with Connect by asking the phone
 * to make Connect API calls for it (the watch's HTTP proxy, see HttpHandler):
 * register, read its settings, log a drink, read the day's summary, save its
 * container sizes. Gadgetbridge 0.95 answers the same calls from its own
 * database (its HydrationInterceptor); the paths, bodies and replies here are
 * the ones it established. Tracks answers them from the day's `hydration_ml`
 * through [Store], which the app provides, so a drink logged on the wrist is
 * a drink in Tracks, synced to the server like one typed on the phone.
 * Nothing reaches Garmin: these requests are answered on the phone, as the
 * music app's config is, and the proxy's rule that nothing leaves for
 * Garmin's servers holds.
 *
 * The other direction is a push: [updatePayload] is the day's total and goal,
 * which the phone sends when the watch connects and when water is logged on
 * the phone, so the widget is not left showing this morning's number.
 */
object WatchHydration {

    /**
     * Where the day's water lives. Called off the BLE thread (HttpHandler runs
     * it on its own), so an implementation may block on its database.
     */
    interface Store {
        /** Millilitres on [date], 0 when nothing is logged. */
        fun totalMl(date: LocalDate): Double

        /** Add [ml] drunk at [at] (local time); the day's new total. */
        fun addMl(at: LocalDateTime, ml: Double): Double

        /** When water was last logged on [date], if it is known. */
        fun lastEntry(date: LocalDate): LocalDateTime?

        /** The day's goal, in millilitres. */
        val goalMl: Double

        /**
         * Set once the watch has asked for any of this, and kept: only a watch
         * whose widget syncs is sent updates (as upstream does), so a watch
         * without one is never pushed a payload for an app it lacks.
         */
        var watchUsesIt: Boolean
    }

    @Volatile
    var store: Store? = null

    /** The widget's app id: where a push for it is addressed. */
    const val APP_ID = "0b8d4130-e3c6-4217-bd6e-af3933cb1c47"

    private val HOSTS = setOf("connectapi.garmin.com", "connectapi.garmin.cn")
    private const val PATH_REGISTER = "/pushnotification-service/ciq/hydration/register"
    private const val PATH_SETTINGS = "/userprofile-service/userprofile/user-settings/hydration"
    private const val PATH_SUMMARY = "/usersummary-service/usersummary/hydration/"
    private const val PATH_LOG = PATH_SUMMARY + "log"
    private const val PATH_DAILY = PATH_SUMMARY + "daily/"
    private const val PATH_ALL_DATA = PATH_SUMMARY + "allData/"

    /** Tracks has no container settings; Garmin's own defaults, in millilitres. */
    private val CONTAINERS_ML = listOf(250, 500, 750)

    /** Whether [url] is one of the widget's calls, answered here rather than proxied or refused. */
    fun handles(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path ?: return false
        return uri.host in HOSTS &&
            (path == PATH_REGISTER || path == PATH_SETTINGS || path.startsWith(PATH_SUMMARY))
    }

    /**
     * The JSON reply to a call [handles] accepted, or null for one it does not
     * know or when the app has no [store] yet, which the caller turns into an
     * error reply the widget can retry.
     */
    fun answer(method: String, url: String, body: ByteArray): String? {
        val store = store ?: return null
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        store.watchUsesIt = true
        val reply: Any = when {
            method == "PUT" && path == PATH_REGISTER -> JSONObject()
            method == "GET" && path == PATH_SETTINGS -> settings()
            method == "PUT" && path == PATH_LOG -> {
                val entry = JSONObject(String(body, Charsets.UTF_8))
                val at = LocalDateTime.parse(entry.getString("timestampLocal"), TIMESTAMP_IN)
                store.addMl(at, entry.getDouble("valueInML"))
                summary(store, at.toLocalDate())
            }
            method == "GET" && path.startsWith(PATH_DAILY) ->
                summary(store, LocalDate.parse(path.removePrefix(PATH_DAILY)))
            // The widget's container sizes. Tracks keeps none, so they are not
            // stored; the summary it expects back is still owed.
            method == "PUT" && path.startsWith(PATH_ALL_DATA) ->
                summary(store, LocalDate.parse(path.removePrefix(PATH_ALL_DATA)))
            else -> return null
        }
        return reply.toString()
    }

    /** The push that brings the widget up to date: the day's total, goal and last drink. */
    fun updatePayload(date: LocalDate = LocalDate.now()): String? {
        val store = store ?: return null
        val data = JSONObject()
            .put("ciqPayloadType", "hydration_update")
            .put("dailyHydrationTotal", store.totalMl(date))
            .put("dailyHydrationGoal", store.goalMl.toInt())
            .put("calendarDate", date.toString())
            .put("lastEntryTimestamp", store.lastEntry(date)?.format(TIMESTAMP_OUT) ?: JSONObject.NULL)
        return JSONObject().put("data", data).put("gns", JSONObject()).toString()
    }

    private fun summary(store: Store, date: LocalDate): JSONObject = JSONObject()
        .put("calendarDate", date.toString())
        .put("valueInML", store.totalMl(date))
        .put("goalInML", store.goalMl)
        .put("dailyAverageinML", JSONObject.NULL)
        .put("lastEntryTimestampLocal", store.lastEntry(date)?.format(TIMESTAMP_OUT) ?: JSONObject.NULL)
        .put("sweatLossInML", JSONObject.NULL)
        .put("activityIntakeInML", 0.0)

    private fun settings(): JSONObject {
        val containers = JSONArray()
        for (ml in CONTAINERS_ML) containers.put(JSONObject().put("volume", ml).put("unit", "milliliter"))
        return JSONObject()
            .put("hydrationMeasurementUnit", "milliliter")
            .put("hydrationContainers", containers)
            .put("hydrationAutoGoalEnabled", false)
    }

    /** The watch's local time: no zero padding, an optional fraction (upstream's parser). */
    private val TIMESTAMP_IN: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4).appendLiteral('-')
        .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral('T')
        .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NOT_NEGATIVE).appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NOT_NEGATIVE)
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
        .toFormatter(Locale.ROOT)

    /** What the widget is answered with: one fraction digit, as Connect does. */
    private val TIMESTAMP_OUT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.S", Locale.ROOT)
}
