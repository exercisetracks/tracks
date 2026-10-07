// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.core.api.DailyMetricFull
import com.tracks.core.api.DailyMetricPatch
import com.tracks.core.api.Injury
import com.tracks.core.api.InjuryActivity
import com.tracks.core.api.InjuryCreate
import com.tracks.core.api.InjuryUpdate
import com.tracks.core.api.Meal
import com.tracks.core.api.MealLog
import com.tracks.core.api.MealIn
import com.tracks.core.api.MealLogCreate
import com.tracks.app.meds.DoseSlot
import com.tracks.app.meds.MedicationReminders
import com.tracks.app.meds.dosesOn
import com.tracks.core.api.Medication
import com.tracks.core.api.MedicationIn
import com.tracks.core.api.SleepNight
import com.tracks.core.api.StressDay
import com.tracks.core.api.MedicationLog
import com.tracks.core.api.MedicationLogCreate
import android.util.Log
import com.tracks.app.device.WeightFit
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.tracks.core.api.MedicationSchedule
import com.tracks.core.api.MedicationScheduleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How far back the trend charts look.
 *
 * The web page offers a Lifetime range and fetches everything up front, which
 * is fine for a browser and not for a phone on a mountain: the whole history is
 * one large response, and the common case is wanting to see the last month. So
 * the window is a *request* parameter here rather than a client-side filter,
 * and Lifetime is the one that costs a big download — clearly labelled, and not
 * the default.
 */
enum class HealthRange(val label: String, val short: String, val days: Long?) {
    // Longest first, matching [com.tracks.app.ui.dashboard.Period]. The two
    // rows of pills sit in the same place on adjacent tabs, and having them
    // count in opposite directions would make one of them feel wrong without
    // anyone being able to say why.
    Lifetime("Lifetime", "All", null),
    Year("1 year", "1Y", 365),
    Month("30 days", "30D", 30),
    Week("7 days", "7D", 7);

    fun afterDate(): String? = days?.let { LocalDate.now().minusDays(it).toString() }

    /** The day this window opens on — the chart's left edge, data or not. */
    fun startDate(): LocalDate? = days?.let { LocalDate.now().minusDays(it) }

    /**
     * Whether the stress *curve* is worth fetching for this window.
     *
     * Stress moves all day, and a chart with one point per day cannot say
     * whether an average of 40 was a steady 40 or a calm morning and a
     * shattering afternoon. So the short windows draw every reading the watch
     * took — some five hundred a day.
     *
     * The long ones cannot: a year of three-minute samples is a solid block of
     * ink that answers nothing and megabytes to fetch for the privilege. There
     * the daily averages already on the metric rows are both the readable
     * answer and free. The server enforces its own cap on top of this — see
     * `/health/stress` — so a client asking for a lifetime of curve gets a
     * month of it rather than a timeout.
     */
    val intraday: Boolean get() = days != null && days <= INTRADAY_MAX_DAYS

    companion object {
        val default = Month

        /**
         * Past a month, one point per day is the readable chart.
         *
         * Not a fetch limit — a legibility one. Thirty days of three-minute
         * samples is already fourteen thousand readings across a phone's
         * width; a year would be a fifth of a pixel per reading.
         */
        const val INTRADAY_MAX_DAYS = 31L
    }
}

/**
 * Everything the Health screen shows.
 *
 * Five independent sections, and — as on the dashboard — a failure in any one
 * of them costs that section rather than the screen. They genuinely can fail
 * separately: the daily metrics come out of encrypted columns and 401 on a
 * locked vault, while medications and meals keep answering normally.
 *
 * Every one of them is mirrored locally, so the page opens with no server at
 * all — see [com.tracks.core.sync.OfflineRepository].
 */
/**
 * One metric's trend: the values, and the day each was measured on.
 *
 * Two parallel lists rather than a list of pairs because that is the shape both
 * consumers want — Vico takes a series of numbers and the axis takes a list of
 * dates — and zipping them apart again at every chart would be work for a
 * tidier-looking type.
 */
data class Trend(val dates: List<String> = emptyList(), val values: List<Double> = emptyList()) {
    val size: Int get() = values.size

    /** The day the newest reading was taken on, which is not today's date. */
    val latestDate: String? get() = dates.lastOrNull()

    /**
     * The newest reading, but only while it is still a reading about *now*.
     *
     * ## Why a dial may not simply show the last thing it heard
     *
     * Because "the last value in the list" and "the current value" are the
     * same sentence only while the watch is still reporting. SpO₂ stops the
     * day the setting is turned off or the watch comes off at night; the last
     * figure stays in the window for as long as the window is open, and a dial
     * showing it says "your blood oxygen is 94" about a week that has no blood
     * oxygen in it at all. That is not a stale cache — it is the app reporting
     * history as if it were current.
     *
     * So a reading expires. [freshDays] is how long it stays current: one day
     * for anything the watch measures nightly, because last night's figure is
     * genuinely this morning's answer and the day's row does not exist until
     * something is written to it. Null for the readings that are a *state*
     * rather than an event — a body mass does not stop being true because
     * nobody stood on the scales today.
     */
    fun current(freshDays: Long?, today: LocalDate = LocalDate.now()): Double? =
        values.lastOrNull()?.takeIf { isFresh(latestDate, freshDays, today) }
}

/**
 * Whether a reading taken on [date] is still a reading about now.
 *
 * [freshDays] null means it never expires — the readings that are a *state*
 * rather than an event, of which weight is the only one here: a body mass does
 * not stop being true because nobody stood on the scales this morning.
 *
 * One day for everything the watch measures, and one rather than zero because
 * the day's row does not exist until something has been written to it. At nine
 * in the morning last night's sleep and resting heart rate are the current
 * answer and today has produced nothing yet.
 *
 * A date in the future is a phone whose clock is ahead of the server's. That
 * is not staleness and must not blank a dial.
 */
/**
 * The local calendar day a UTC stamp falls on, as `yyyy-MM-dd` — or null for a
 * stamp that does not parse. Offset-less stamps are read as local already.
 */
internal fun localDayOf(stamp: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? =
    runCatching { java.time.OffsetDateTime.parse(stamp).atZoneSameInstant(zone).toLocalDate().toString() }
        .recoverCatching { java.time.LocalDateTime.parse(stamp).toLocalDate().toString() }
        .recoverCatching { LocalDate.parse(stamp.take(10)).toString() }
        .getOrNull()

internal fun isFresh(
    date: String?,
    freshDays: Long?,
    today: LocalDate = LocalDate.now(),
): Boolean {
    if (freshDays == null) return date != null
    val day = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
    return java.time.temporal.ChronoUnit.DAYS.between(day, today) <= freshDays
}

/**
 * How long a watch reading stays current.
 *
 * The default for every dial that is not a standing fact about the body. See
 * [isFresh] for why it is a day and not none.
 */
internal const val FRESH_DAYS = 1L

data class HealthUiState(
    val loading: Boolean = true,
    val range: HealthRange = HealthRange.default,
    val days: List<DailyMetricFull> = emptyList(),
    /** Which night the sleep detail is showing; the most recent one by default. */
    val selectedNight: String? = null,
    /** That night's stage timeline, when the server has one for it. */
    val night: SleepNight? = null,
    /**
     * The stress curve, per day, for the windows where it is worth drawing.
     *
     * Empty on the long windows by design — see [HealthRange.intraday], where
     * the daily averages on [days] are both the readable chart and free.
     */
    val stress: List<StressDay> = emptyList(),
    val injuries: List<Injury> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val medicationLog: List<MedicationLog> = emptyList(),
    val meals: List<Meal> = emptyList(),
    val mealLog: List<MealLog> = emptyList(),
    val error: String? = null,
) {
    /**
     * The most recent day that actually carries a given reading.
     *
     * Not simply the last day. Gaps are routine — a watch off the wrist, a
     * night's sync that has not happened yet — and showing "—" for resting HR
     * because today's row exists but is empty would be worse than showing
     * yesterday's real number.
     */
    fun latestOf(select: (DailyMetricFull) -> Double?): Double? =
        days.asReversed().firstNotNullOfOrNull(select)

    /**
     * One metric's readings, with the days they were taken on.
     *
     * The dates used to be dropped here and the chart drew an unlabelled line,
     * which for a year of resting heart rate is a shape with no idea where it
     * starts. Gaps are why they have to travel *with* the values rather than
     * being derived from [days]: a metric missing on eleven days of a month has
     * eleven fewer points than there are days, so index n is not day n.
     */
    /**
     * Every night in the window that has a sleep total, oldest first.
     *
     * Nights rather than days: a day with no sleep recorded is not a night with
     * no sleep, and drawing it as a zero-height bar would claim otherwise.
     */
    val nights: List<SleepNightSummary>
        get() = days.mapNotNull { day ->
            val total = day.sleepHours?.takeIf { it > 0 } ?: return@mapNotNull null
            SleepNightSummary(
                date = day.date,
                total = total,
                deep = day.sleepDeepHours ?: 0.0,
                rem = day.sleepRemHours ?: 0.0,
                light = day.sleepLightHours ?: 0.0,
                awake = day.sleepAwakeHours,
                score = day.sleepScore,
                startAt = day.sleepStart,
                endAt = day.sleepEnd,
            )
        }

    fun series(select: (DailyMetricFull) -> Double?): Trend {
        val dates = ArrayList<String>(days.size)
        val values = ArrayList<Double>(days.size)
        days.forEach { day -> select(day)?.let { dates += day.date; values += it } }
        return Trend(dates, values)
    }

    val today: String get() = LocalDate.now().toString()

    /** Injuries still open, worst first — what the section leads with. */
    val activeInjuries: List<Injury>
        get() = injuries.filter { it.isActive }.sortedByDescending { it.severity }

    val healedInjuries: List<Injury>
        get() = injuries.filterNot { it.isActive }.sortedByDescending { it.startDate }

    /**
     * Today's scheduled doses, each with what has become of it.
     *
     * Worked out here rather than fetched from `/medications/due` — see
     * [com.tracks.app.meds.dosesOn], which also explains why the endpoint's own
     * answer is wrong for anyone not living on UTC.
     */
    val dosesToday: List<DoseSlot>
        get() = dosesOn(LocalDate.now(), medications, medicationLog)

    val mealsToday: List<MealLog>
        // By the local day it was eaten on. The stamp is UTC, and matching its
        // text against today's date filed an evening meal under tomorrow.
        get() = mealLog.filter { localDayOf(it.loggedAt) == today }

    /**
     * What the Eaten dial counts: each day's food entries added up.
     *
     * There used to be two calorie-eaten figures that never agreed — a typed
     * daily total on the metric row, which drove the dial, and the meal log,
     * which the dial ignored. The log is the source now. Days from before it,
     * which carry only a typed total, keep that total so their history is not
     * lost, but a day with any food logged counts the food and never both.
     */
    val eaten: Trend
        get() {
            val cutoff = range.afterDate()
            val food = mealLog.mapNotNull { e -> localDayOf(e.loggedAt)?.let { it to e.calories } }
                .filter { (day, _) -> cutoff == null || day >= cutoff }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, kcal) -> kcal.sum().toDouble() }
            val typed = days.mapNotNull { d -> d.caloriesIn?.let { d.date to it.toDouble() } }.toMap()
            val dates = (food.keys + typed.keys).sorted()
            return Trend(dates, dates.map { food[it] ?: typed.getValue(it) })
        }

    val caloriesToday: Int get() = mealsToday.sumOf { it.calories }

    /**
     * An id for a row the server has not seen.
     *
     * Negative and descending, so it cannot collide with a server-allocated id
     * and two rows queued before the next sync do not collide with each other.
     */
    fun nextLocalId(existing: List<Int>): Int =
        ((existing.minOrNull() ?: 0).coerceAtMost(0)) - 1
}

class HealthViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HealthUiState())
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    init {
        load()
        watchLocalReadings()
    }

    /**
     * Reload when the phone reads a day out of a watch file.
     *
     * This page is held for the life of the process — see the nav host — so a
     * watch sync started from Settings would otherwise write last night's sleep
     * into the mirror and leave this screen showing what it loaded at breakfast
     * until the app was killed. With a server that never showed, because
     * something always came along and refetched; with no server, nothing ever
     * does.
     *
     * The first value is dropped because it is the state this already loaded.
     */
    private fun watchLocalReadings() {
        viewModelScope.launch {
            container.localData.revision.drop(1).collect { loadMetrics() }
        }
    }

    fun setRange(range: HealthRange) {
        if (range == _state.value.range) return
        _state.update { it.copy(range = range) }
        loadMetrics()
    }

    fun load() {
        loadMetrics()
        loadEntered()
    }

    /**
     * The measured half: the daily metrics.
     *
     * ## Why the days are merged rather than replaced
     *
     * The range is a request parameter, so switching from 30d to a year asks
     * for a different window — and offline, the window asked for is not the one
     * on disk. Keeping one union of every day ever seen, and filtering it to
     * the range on the way out, means every range works from the mirror
     * instead of only the one last fetched. The cost is a document that grows,
     * bounded by [MAX_CACHED_DAYS].
     */
    private fun loadMetrics() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val days = withContext(Dispatchers.Default) {
                runCatching { container.metrics.dailyMetrics(after = null) }.getOrDefault(emptyList())
            }
            applyMetrics(days = days, stillLoading = false)
            loadStress()
        }
    }

    /**
     * The stress curve for the window, where the window is short enough to
     * draw one. A window with no curve — a year, a lifetime — clears what a
     * shorter window showed, since a month of readings drawn across a year of
     * axis is a smear at the right-hand edge pretending to be a year of data.
     */
    private fun loadStress() {
        val range = _state.value.range
        if (!range.intraday) {
            if (_state.value.stress.isNotEmpty()) _state.update { it.copy(stress = emptyList()) }
            return
        }
        viewModelScope.launch {
            val days = withContext(Dispatchers.Default) {
                runCatching { container.metrics.stress(range.afterDate()) }.getOrDefault(emptyList())
            }
            publishStress(range, days)
        }
    }

    /**
     * Hand a stress curve to the screen, unless the window moved under it.
     *
     * Two range taps in quick succession start two fetches, and the slower one
     * answering second would otherwise overwrite the newer window's chart with
     * the older one's readings.
     */
    private fun publishStress(range: HealthRange, days: List<StressDay>) {
        if (_state.value.range != range) return
        val cutoff = range.afterDate()
        _state.update {
            it.copy(stress = if (cutoff == null) days else days.filter { d -> d.date >= cutoff })
        }
    }

    /**
     * Show one night's detail.
     *
     * Cached per night, like an activity's own pieces: a year of stage
     * timelines is megabytes of spans for nights nobody has opened, and opening
     * one makes it readable offline forever afterwards.
     *
     * A night the server has no timeline for comes back with an empty list
     * rather than an error, and that is stored too — so the phone stops asking
     * every time the page is opened.
     */
    fun selectNight(date: String) {
        if (date == _state.value.selectedNight) return
        _state.update { it.copy(selectedNight = date, night = null) }
        viewModelScope.launch {
            val night = withContext(Dispatchers.Default) { runCatching { container.metrics.sleepNight(date) }.getOrNull() }
            if (night != null && _state.value.selectedNight == date) _state.update { it.copy(night = night) }
        }
    }

    private fun applyMetrics(days: List<DailyMetricFull>, stillLoading: Boolean) {
        val cutoff = _state.value.range.afterDate()
        val window = if (cutoff == null) days else days.filter { it.date >= cutoff }
        _state.update {
            it.copy(
                loading = stillLoading && window.isEmpty(),
                days = window.sortedBy { d -> d.date },
                error = if (!stillLoading && window.isEmpty()) {
                    "No health data on this phone yet. Sync your watch to read it."
                } else {
                    null
                },
            )
        }

        // The newest night, unless the user has already picked one. Done here
        // rather than in the screen so the fetch starts with the data instead
        // of one composition later.
        if (_state.value.selectedNight == null) {
            _state.value.nights.lastOrNull()?.let { selectNight(it.date) }
        }
    }

    /** The entered half: injuries, medications, meals — all in this phone's replica. */
    private fun loadEntered() {
        viewModelScope.launch {
            val src = container.sources
            val logFrom = LocalDate.now().minusDays(LOG_DAYS.toLong()).toString()
            val medications = MedicationReminders.loadMedications(src).sortedBy { it.name.lowercase() }
            _state.update {
                it.copy(
                    injuries = src.list("injury", Injury.serializer()).sortedByDescending { i -> i.startDate },
                    medications = medications,
                    medicationLog = src.list("medication_log", MedicationLog.serializer())
                        .filter { l -> l.loggedAt >= logFrom }.sortedBy { l -> l.loggedAt },
                    meals = src.list("meal", Meal.serializer()).sortedBy { m -> m.name.lowercase() },
                    // All of it, not a recent slice: the Eaten dial counts
                    // food, and its history reaches as far back as the window
                    // does. A few rows a day is nothing to hold.
                    mealLog = src.list("meal_log", MealLog.serializer()).sortedBy { l -> l.loggedAt },
                )
            }
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────────
    //
    // Every one is a local edit to the replica: it lands at once, needs no
    // server, and syncs field by field when there is one (spec/sync.yaml).
    // None of them can be refused for want of signal, because none of them is
    // a request — they are statements about what the user did.

    /**
     * What was happening around an injury — a week either side, server-picked.
     *
     * Fetched on demand rather than carried in the page state: it is one
     * injury's worth of context, opened deliberately, and loading it for every
     * injury on every page load would be a request per injury for something
     * nobody has asked to see yet.
     *
     * Not mirrored offline. The answer is a join across activities the phone
     * does not hold in this shape, and an empty list is an honest thing for the
     * sheet to show when there is no server.
     */
    suspend fun activitiesAround(injuryId: Int): List<InjuryActivity> =
        runCatching { container.client().injuryActivities(injuryId) }.getOrDefault(emptyList())

    fun logInjury(injury: InjuryCreate) = write {
        container.sources.create("injury", injury, InjuryCreate.serializer())
    }

    fun healInjury(injury: Injury) {
        val today = LocalDate.now().toString()
        updateInjury(injury.id, InjuryUpdate(endDate = today))
    }

    fun updateInjury(id: Int, update: InjuryUpdate) = write {
        container.sources.edit("injury", id, update, InjuryUpdate.serializer())
    }

    fun deleteInjury(id: Int) = write { container.sources.delete("injury", id) }

    /**
     * Record a dose — taken, skipped, or taken as needed.
     *
     * The schedule id and the time it was due travel with it, so a dose ticked
     * off at lunchtime is still filed against the morning slot rather than
     * appearing as an extra one. A log entry is written once and never edited
     * (spec/sync.yaml, `log`); "I didn't take that" is a delete and a new one.
     */
    fun logDose(entry: MedicationLogCreate) = write {
        container.sources.create(
            "medication_log", entry, MedicationLogCreate.serializer(),
            extra = mapOf("logged_at" to nowIso()),
        )
    }

    /**
     * Add or replace a medication, and make its schedules exactly the ones
     * given. Each schedule is a row of its own, so a time changed here and a
     * notification toggled on another phone both survive.
     */
    fun saveMedication(id: Int?, medication: MedicationIn) = write {
        val src = container.sources
        val medId = if (id == null) {
            src.create("medication", medication, MedicationIn.serializer())
        } else {
            src.edit("medication", id, medication, MedicationIn.serializer())
            id
        }
        src.replaceChildren("medication_schedule", medId, medication.schedules, MedicationScheduleIn.serializer())
    }

    fun deleteMedication(id: Int) = write { container.sources.delete("medication", id) }

    /**
     * Calories fall back to the template's when the entry does not name them —
     * the substitution the server makes, made here at the moment of logging so
     * every replica stores the same number rather than each deriving its own.
     */
    fun logMeal(entry: MealLogCreate) = write {
        val template = entry.mealId?.let { id -> _state.value.meals.firstOrNull { it.id == id } }
        val filled = entry.copy(
            calories = entry.calories ?: template?.calories ?: 0,
            proteinG = entry.proteinG ?: template?.proteinG,
            carbsG = entry.carbsG ?: template?.carbsG,
            fatG = entry.fatG ?: template?.fatG,
            loggedAt = entry.loggedAt ?: nowIso(),
        )
        container.sources.create("meal_log", filled, MealLogCreate.serializer())
    }

    fun deleteMealLog(id: Int) = write { container.sources.delete("meal_log", id) }

    /**
     * Log a food line from the form, and keep it as a saved meal if asked.
     *
     * Remembered only when no saved meal already has the name: the name is the
     * identity a person sees, and two chips called "Porridge" would be a puzzle.
     * A typed name that matches one is logged against it either way, so the
     * chip and the keyboard produce the same entry.
     */
    fun logFood(entry: MealLogCreate, remember: Boolean) = write {
        val src = container.sources
        val existing = _state.value.meals.firstOrNull { it.name.equals(entry.name, ignoreCase = true) }?.id
        val mealId = existing ?: if (remember) {
            src.create(
                "meal",
                MealIn(entry.name, entry.calories ?: 0, entry.proteinG, entry.carbsG, entry.fatG),
                MealIn.serializer(),
            )
        } else {
            null
        }
        src.create(
            "meal_log",
            entry.copy(mealId = mealId, calories = entry.calories ?: 0, loggedAt = entry.loggedAt ?: nowIso()),
            MealLogCreate.serializer(),
        )
    }

    /**
     * Correct a saved meal. Every field is sent explicitly: an edit leaves out
     * values still at their defaults, so a calorie count set to 0 or a macro
     * cleared would otherwise never be written.
     */
    fun saveMeal(id: Int, meal: MealIn) = write {
        container.sources.edit(
            "meal", id, meal, MealIn.serializer(),
            extra = mapOf(
                "calories" to meal.calories,
                "protein_g" to meal.proteinG,
                "carbs_g" to meal.carbsG,
                "fat_g" to meal.fatG,
            ),
        )
    }

    /** Past entries keep their own copy of the name and calories, so nothing logged is lost. */
    fun deleteSavedMeal(id: Int) = write { container.sources.delete("meal", id) }

    /**
     * Correct today's weight, hydration, or calories in.
     *
     * The date is fixed here rather than at send time. A weight typed on Sunday
     * night and synced on Monday morning belongs to Sunday, and an outbox that
     * resolved "today" when it finally drained would file it under the wrong
     * one.
     */
    fun patchToday(patch: DailyMetricPatch) {
        val date = LocalDate.now().toString()
        patch.weightKg?.let { pushWeightToWatch(it) }
        viewModelScope.launch {
            // One row per day, uid derived from the date: a weight typed here
            // and a hydration typed on the web the same day land on one row.
            container.sources.create(
                "daily_entry", patch, DailyMetricPatch.serializer(),
                keyValues = mapOf("date" to date), extra = mapOf("date" to date),
            )
            loadMetrics()
        }
    }

    /**
     * Tell the watch what somebody weighs, if it is listening.
     *
     * ## Why the watch is told at all
     *
     * Because it is the one entered figure the device has a use for. Weight
     * feeds its calorie model, so a watch carrying a body mass from last
     * spring quietly mis-estimates every burn until it is corrected on the
     * device — which is four levels of menu. A Garmin scale solves this by
     * dropping a `WEIGHT` file into the watch's import folder, and that is
     * what this sends. See [WeightFit], which also says why water and food get
     * no such treatment.
     *
     * ## Why a failure here is silent
     *
     * The weigh-in is already recorded — locally, and in the outbox for the
     * server. The watch is a *second* recipient and a nice-to-have: no watch
     * paired, out of range, in the middle of a sync, all of them mean "not
     * now" rather than "the weight was lost". Reporting them would train
     * somebody to ignore a warning that is nearly always noise, and the next
     * sync sends whatever the user last typed anyway.
     */
    private fun pushWeightToWatch(kilograms: Double) {
        viewModelScope.launch {
            runCatching {
                val at = System.currentTimeMillis() / 1000L
                val bytes = WeightFit.build(kilograms, at) ?: return@launch
                if (!container.watch.connect()) return@launch
                container.watch.pushFile(NEW_FILES, WeightFit.filename(at), bytes)
            }.onFailure { Log.i(TAG, "weight not delivered to the watch: ${it.message}") }
        }
    }

    /** Make a local edit, then reread what it changed. */
    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "local write failed", it) }
            loadEntered()
        }
    }

    /**
     * Null on any failure, keeping whatever was there.
     *
     * Same reasoning as the dashboard's: a locked vault is a weekly, designed
     * occurrence, and the section that cannot load should be absent rather than
     * turning the page into an error.
     */
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        runCatching { block() }.getOrNull()

    private companion object {
        /**
         * How much dose history to keep.
         *
         * Thirty days, matching the History tab and the server's own default.
         * It used to ask for fourteen and get thirty anyway — the parameter was
         * being sent under a name the endpoint does not read.
         */
        const val LOG_DAYS = 30

        /**
         * The folder a watch imports from on its own.
         *
         * The same route the training calendar takes — see
         * `WatchManager.pushSchedule` for why the legacy path is the one that
         * actually lands.
         */
        const val NEW_FILES = "GARMIN/NewFiles"

        const val TAG = "TracksHealth"

        /**
         * When something was logged: an aware UTC instant, the shape the
         * server stores and every replica compares, so "logged today" means
         * the same thing on every device.
         */
        fun nowIso(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
    }
}
