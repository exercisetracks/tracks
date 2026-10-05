// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tracks.device.WeatherReport
import com.tracks.device.feeds.WeatherBroadcast

/**
 * Receives forecasts from whatever weather app the user has.
 *
 * Registered in the manifest for a *custom* action, which matters: Android 8's
 * background limits block manifest receivers for implicit **system**
 * broadcasts, but app-defined actions are exempt. So this works without a
 * running service, which is the point — a forecast should reach the watch
 * without the user opening Tracks.
 *
 * Whether the broadcast arrives depends on how the sender addresses it. Sent
 * implicitly, every app with this filter receives it, including us. Sent with
 * `setPackage(...)`, only the named app does — and uninstalling that app does
 * not redirect it here, it simply goes nowhere.
 */
class WeatherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reports = WeatherBroadcast.parse(intent)
        if (reports.isEmpty()) {
            Log.w(TAG, "weather broadcast had nothing usable: action=${intent.action}")
            return
        }

        val primary = reports.first()
        Log.i(
            TAG,
            "weather: ${primary.location} ${primary.currentTempC}C " +
                "(${primary.currentCondition}), ${reports.size} location(s), " +
                "${primary.forecasts.size} day forecast",
        )

        latest = primary
        WeatherSources.recordAppForecast(context, primary.location)

        // Holding the most recent report is what lets the first connection send
        // something useful immediately rather than waiting for the next
        // broadcast, which may be an hour away. The listener is for the other
        // case: a watch already connected wants the new forecast now.
        listener?.let { notify ->
            try {
                notify(primary)
            } catch (e: Exception) {
                Log.w(TAG, "weather listener failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "TracksWeather"

        /**
         * The most recent forecast, in memory only.
         *
         * Deliberately not persisted: a stale forecast is worse than none, and
         * weather is re-broadcast often enough that a process restart costs
         * nothing.
         */
        @Volatile
        var latest: WeatherReport? = null
            private set

        /**
         * Notified when a fresh forecast lands, so a connected watch does not
         * have to wait for a reconnect to see it.
         *
         * Volatile and nullable rather than a list: exactly one thing — the
         * watch manager — is ever interested, and a receiver that runs on the
         * main thread should not be walking a subscriber list.
         */
        @Volatile
        var listener: ((WeatherReport) -> Unit)? = null
    }
}
