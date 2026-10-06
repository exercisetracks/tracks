// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Where the watch's weather comes from, and how to tell whether it is coming.
 *
 * A weather app on the phone is the preferred source: it sends its forecast in
 * Gadgetbridge's broadcast format, [WeatherReceiver] takes it, and the watch
 * gets it from there. When nothing has sent one, Tracks asks Open-Meteo itself
 * for where the phone last was (see `WatchManager.sendLatestWeather`) —
 * the "server" names below predate that request moving onto the phone, and
 * the preference key keeps its name so existing history survives. This holds the
 * apps that speak that format, the steps to switch it on in each, and a
 * small persisted record of what actually arrived — the in-memory forecast
 * alone was gone after every process restart, so Settings said "has not sent a
 * forecast yet" about an app that had sent one an hour ago.
 */
object WeatherSources {

    /** An app that can send forecasts in Gadgetbridge's format. */
    data class Provider(
        val name: String,
        val packageName: String,
        /** One line on what it is, for someone choosing. */
        val blurb: String,
        /** Where in the app the sending switch is, as the app labels it. */
        val enableSteps: String,
        val downloads: List<Pair<String, String>>,
        val recommended: Boolean = false,
    )

    /**
     * The apps Gadgetbridge documents as weather providers, Breezy first and
     * recommended: free software, worldwide, several data sources, and the one
     * whose menu wording here was read off a real install (Breezy 6.2.2).
     */
    val PROVIDERS: List<Provider> = listOf(
        Provider(
            name = "Breezy Weather",
            packageName = FeedPermissions.BREEZY_PACKAGE,
            blurb = "Free and open source, worldwide, no ads or tracking.",
            enableSteps = "In Breezy Weather: Settings › External modules › Data sharing › " +
                "Send Gadgetbridge data, then select Tracks.",
            downloads = listOf(
                "F-Droid" to "https://f-droid.org/packages/org.breezyweather/",
                "GitHub" to "https://github.com/breezy-weather/breezy-weather/releases",
            ),
            recommended = true,
        ),
        Provider(
            name = "QuickWeather",
            packageName = "com.ominous.quickweather",
            blurb = "Free and open source, needs your own weather API key.",
            enableSteps = "In QuickWeather's settings, turn on Gadgetbridge support.",
            downloads = listOf("F-Droid" to "https://f-droid.org/packages/com.ominous.quickweather/"),
        ),
        Provider(
            name = "Tiny Weather Forecast Germany",
            packageName = "de.kaffeemitkoffein.tinyweatherforecastgermany",
            blurb = "Free and open source, forecasts for Germany only.",
            enableSteps = "In Tiny Weather Forecast Germany's settings, turn on Gadgetbridge support.",
            downloads = listOf(
                "F-Droid" to "https://f-droid.org/packages/de.kaffeemitkoffein.tinyweatherforecastgermany/",
            ),
        ),
    )

    /** The providers installed on this phone. Each needs a `<queries>` entry in the manifest. */
    fun installed(context: Context): List<Provider> = PROVIDERS.filter { provider ->
        runCatching { context.packageManager.getPackageInfo(provider.packageName, 0); true }.getOrDefault(false)
    }

    fun open(context: Context, provider: Provider): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(provider.packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun openLink(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── What actually arrived ────────────────────────────────────────────────

    private const val PREFS = "tracks_weather"
    private const val KEY_APP_AT = "app_forecast_at"
    private const val KEY_APP_PLACE = "app_forecast_place"
    private const val KEY_SERVER_AT = "server_forecast_at"

    fun recordAppForecast(context: Context, place: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_APP_AT, System.currentTimeMillis())
            .putString(KEY_APP_PLACE, place)
            .apply()
    }

    fun recordServerForecast(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SERVER_AT, System.currentTimeMillis())
            .apply()
    }

    fun history(context: Context): History {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return History(
            appAt = prefs.getLong(KEY_APP_AT, 0L).takeIf { it > 0 },
            appPlace = prefs.getString(KEY_APP_PLACE, null),
            serverAt = prefs.getLong(KEY_SERVER_AT, 0L).takeIf { it > 0 },
        )
    }

    data class History(val appAt: Long?, val appPlace: String?, val serverAt: Long?)

    // ── The status a person reads ────────────────────────────────────────────

    /**
     * How old an app's forecast may be and still count as "receiving".
     *
     * Breezy refreshes in the background about hourly by default, and Android
     * defers that freely when the phone is idle; three hours is past any
     * ordinary gap and short of a forecast that has genuinely stopped.
     */
    const val FRESH_MS = 3 * 60 * 60 * 1000L

    enum class State { RECEIVING, STALE, SERVER_ONLY, WAITING, NO_APP }

    data class Status(val state: State, val provider: Provider?, val text: String) {
        val ok: Boolean get() = state == State.RECEIVING
    }

    /**
     * What to tell the person, from what is installed and what has arrived.
     * Pure, so it is tested without a phone.
     */
    fun status(installed: List<Provider>, history: History, nowMs: Long, timeLabel: (Long) -> String): Status {
        val provider = installed.firstOrNull { it.recommended } ?: installed.firstOrNull()
        val appAt = history.appAt
        val serverNote = history.serverAt?.takeIf { nowMs - it < FRESH_MS }
            ?.let { " The watch is getting Tracks' own forecast meanwhile (last at ${timeLabel(it)})." }
            ?: ""
        return when {
            appAt != null && nowMs - appAt < FRESH_MS -> Status(
                State.RECEIVING, provider,
                "Receiving forecasts" + (provider?.let { " from ${it.name}" } ?: "") +
                    " — last at ${timeLabel(appAt)}" + (history.appPlace?.let { " for $it" } ?: "") + ".",
            )
            appAt != null -> Status(
                State.STALE, provider,
                "No forecast since ${timeLabel(appAt)}. " +
                    (provider?.let { "Check that ${it.name} is still sending: ${it.enableSteps}" }
                        ?: "The app that was sending forecasts may have been removed.") + serverNote,
            )
            provider != null -> Status(
                State.WAITING, provider,
                "${provider.name} is installed but has not sent a forecast yet. ${provider.enableSteps}" + serverNote,
            )
            history.serverAt != null && nowMs - history.serverAt < FRESH_MS -> Status(
                State.SERVER_ONLY, null,
                "No weather app is installed, so the watch gets Tracks' own forecast for where your phone last was " +
                    "(last at ${timeLabel(history.serverAt)}). Install Breezy Weather for forecasts where you are.",
            )
            else -> Status(
                State.NO_APP, null,
                "No weather app is installed. Install Breezy Weather and let it send data to Tracks.",
            )
        }
    }
}
