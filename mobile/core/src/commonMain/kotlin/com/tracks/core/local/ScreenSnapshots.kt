// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.local

import com.tracks.core.api.ActivityCalendarPoint
import com.tracks.core.api.DailyCoaching
import com.tracks.core.api.DailyMetricFull
import com.tracks.core.api.Injury
import com.tracks.core.api.Meal
import com.tracks.core.api.MealLog
import com.tracks.core.api.Medication
import com.tracks.core.api.MedicationLog
import com.tracks.core.api.MetricsSummary
import com.tracks.core.api.PlannedWorkout
import com.tracks.core.api.ReadinessHistoryPoint
import com.tracks.core.api.SleepNight
import com.tracks.core.api.SportBreakdown
import com.tracks.core.api.StressDay
import com.tracks.core.api.TrainingLoadPoint
import com.tracks.core.api.Vo2MaxPoint
import com.tracks.core.api.WeeklyVolumePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the Dashboard and Health screens last showed, so a cold start can show
 * it again on the first frame.
 *
 * Every figure on those screens is computed on the phone from the mirror, and
 * on a cold start that takes long enough to see: the charts and gauges drew
 * empty for a moment and then filled in. Showing the last computed state
 * straight away, and swapping in the fresh one when it is ready, makes opening
 * the app look like returning to it. The data is at most as old as the last
 * time those screens were looked at, and it is replaced within a second.
 *
 * Only the codec lives here (`:core` has the serialisation runtime and the
 * tests); the app seals the text before it touches the disk — see
 * `SealedSnapshots` in `:app`. These are health records, so they are held to
 * the mirror's standard, not a cache's.
 *
 * A snapshot that does not decode — an older shape after an upgrade — is null,
 * and the screen simply loads the slow way once.
 */
object ScreenSnapshots {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The Dashboard: windowed slices tagged with their window, plus the all-time ones. */
    @Serializable
    data class Dashboard(
        /** The period the windowed fields were computed for (`Period.name`). */
        val period: String,
        val summary: MetricsSummary? = null,
        val extras: Extras = Extras(),
        val bySport: List<SportBreakdown> = emptyList(),
        val calendar: List<ActivityCalendarPoint> = emptyList(),
        val vo2max: List<Vo2MaxPoint> = emptyList(),
        val readiness: List<ReadinessHistoryPoint> = emptyList(),
        val weeklyVolume: List<WeeklyVolumePoint> = emptyList(),
        val trainingLoad: List<TrainingLoadPoint> = emptyList(),
        val coaching: DailyCoaching? = null,
        val upcoming: List<PlannedWorkout> = emptyList(),
    )

    /** The app's `DashboardExtras`, field for field. */
    @Serializable
    data class Extras(
        val totalAscentM: Double? = null,
        val totalCalories: Int? = null,
        val longestDistanceM: Double? = null,
        val longestDurationSec: Int? = null,
        val activeDays: Int = 0,
        val avgHeartRate: Int? = null,
        val busiestSport: String? = null,
    )

    /** The Health page, for the range it was showing (`HealthRange.name`). */
    @Serializable
    data class Health(
        val range: String,
        val days: List<DailyMetricFull> = emptyList(),
        val selectedNight: String? = null,
        val night: SleepNight? = null,
        val stress: List<StressDay> = emptyList(),
        val injuries: List<Injury> = emptyList(),
        val medications: List<Medication> = emptyList(),
        val medicationLog: List<MedicationLog> = emptyList(),
        val meals: List<Meal> = emptyList(),
        val mealLog: List<MealLog> = emptyList(),
    )

    fun encode(s: Dashboard): String = json.encodeToString(Dashboard.serializer(), s)
    fun decodeDashboard(raw: String?): Dashboard? =
        raw?.let { runCatching { json.decodeFromString(Dashboard.serializer(), it) }.getOrNull() }

    fun encode(s: Health): String = json.encodeToString(Health.serializer(), s)
    fun decodeHealth(raw: String?): Health? =
        raw?.let { runCatching { json.decodeFromString(Health.serializer(), it) }.getOrNull() }
}
