// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context
import com.tracks.app.AppContainer
import com.tracks.app.ui.health.HYDRATION_GOAL_ML
import com.tracks.device.garmin.WatchHydration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

/**
 * The watch's hydration widget, kept on the day's `hydration_ml`.
 *
 * A drink logged on the wrist is added to the day's total through the same
 * synced write the Health page's water field makes, so it reaches the server
 * and the web like one typed on the phone. Tracks keeps a total per day, not
 * each drink, so the one thing the widget shows that has no home in it — when
 * the last drink was — is kept here on the phone, per day.
 *
 * Read and written from the watch's request thread (WatchHydration), which may
 * block, hence [runBlocking] around the database.
 */
class WatchHydrationStore(private val container: AppContainer) : WatchHydration.Store {

    private val prefs = container.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val goalMl: Double get() = HYDRATION_GOAL_ML

    override var watchUsesIt: Boolean
        get() = prefs.getBoolean(KEY_USES, false)
        set(value) { if (value != watchUsesIt) prefs.edit().putBoolean(KEY_USES, value).apply() }

    override fun totalMl(date: LocalDate): Double = runBlocking {
        container.sources.numberOf("daily_entry", "date", date.toString(), "hydration_ml") ?: 0.0
    }

    /**
     * Read, add, write. Requests are answered one at a time (HttpHandler's
     * executor), so two drinks logged back to back add up rather than race; a
     * web edit to the same day that has not synced down yet is the one thing
     * this can overwrite, as any edit on the phone can.
     */
    override fun addMl(at: LocalDateTime, ml: Double): Double = runBlocking {
        val day = at.toLocalDate()
        val total = Math.round(totalMl(day) + ml).toDouble()
        container.sources.createValues(
            "daily_entry",
            mapOf("date" to day.toString(), "hydration_ml" to total.toInt()),
            keyValues = mapOf("date" to day.toString()),
        )
        prefs.edit().putString(KEY_LAST, at.toString()).apply()
        total
    }

    override fun lastEntry(date: LocalDate): LocalDateTime? =
        prefs.getString(KEY_LAST, null)
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            ?.takeIf { it.toLocalDate() == date }

    /** A drink logged on the phone: the widget's "last drink" moves with it. */
    fun loggedOnPhone(at: LocalDateTime = LocalDateTime.now()) {
        prefs.edit().putString(KEY_LAST, at.toString()).apply()
    }

    private companion object {
        const val PREFS = "tracks_watch_hydration"
        const val KEY_USES = "watch_uses_hydration"
        const val KEY_LAST = "last_entry"
    }
}
