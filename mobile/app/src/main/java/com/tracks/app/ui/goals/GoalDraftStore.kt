// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The new-goal form as it was left, so closing it is not losing it.
 *
 * Someone setting up a plan leaves the form to look something up — the race's
 * date, how many days they can train — or the system takes the app away from
 * them, and the form used to come back blank. Now every change to a *new*
 * goal is written here and the form reopens on it, until the goal is created
 * or the user starts over. Cancel keeps it too: it closes the form, which is
 * all leaving does.
 *
 * Only a new goal. A goal being edited reopens on what is saved, which is the
 * right answer for it: its half-made edits were never asked to outlive the
 * sheet, and a stale copy reopening over newer synced values would be worse.
 *
 * In the app's own prefs rather than the replica: an unsaved form is this
 * phone's, not the account's, and does not belong on a server or the web.
 */
class GoalDraftStore(private val context: Context) {

    fun load(): GoalDraft? =
        prefs().getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }

    /** Kept while it says something; the untouched form is the same as none. */
    fun save(draft: GoalDraft) {
        if (!draft.isNew) return
        if (isBlank(draft)) clear() else prefs().edit().putString(KEY, encode(draft)).apply()
    }

    fun clear() {
        prefs().edit().remove(KEY).apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "tracks_app"
        private const val KEY = "new_goal_draft"

        /**
         * Nothing changed from a fresh form. The recommended date is left out
         * of the comparison: it fills itself in on open, so it is not something
         * the user entered.
         */
        fun isBlank(draft: GoalDraft): Boolean =
            draft.copy(eventDate = "", eventDateAuto = false, dateAdvice = null) == GoalDraft()

        /**
         * The fields the user set. [GoalDraft.dateAdvice] is not among them —
         * it is recomputed from this phone's training when the form opens — and
         * neither is the id, which a new goal does not have.
         */
        fun encode(d: GoalDraft): String = JSONObject().apply {
            put("goalType", d.goalType)
            put("eventName", d.eventName)
            put("eventSport", d.eventSport)
            put("eventDate", d.eventDate)
            d.eventDistanceMeters?.let { put("eventDistanceMeters", it) }
            put("ctlRampPerWeek", d.ctlRampPerWeek)
            put("fitnessSports", JSONArray(d.fitnessSports))
            d.targetWeeklyKm?.let { put("targetWeeklyKm", it) }
            put("volumeSport", d.volumeSport)
            put("daysPerWeek", d.daysPerWeek)
            put("planIntensity", d.planIntensity)
            put("mtbDiscipline", d.mtbDiscipline)
            put("cyclingDiscipline", d.cyclingDiscipline)
            put("scheduleTests", d.scheduleTests)
            put("includeStrength", d.includeStrength)
            put("strengthTier", d.strengthTier)
            d.strengthDaysPerWeek?.let { put("strengthDaysPerWeek", it) }
            put("notes", d.notes)
            d.presetId?.let { put("presetId", it) }
            put("eventDateAuto", d.eventDateAuto)
        }.toString()

        /** Missing fields take the fresh form's value, so a draft from an older build still opens. */
        fun decode(text: String): GoalDraft {
            val o = JSONObject(text)
            val blank = GoalDraft()
            fun str(k: String, default: String) = if (o.has(k)) o.getString(k) else default
            fun dbl(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
            fun int(k: String) = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            fun bool(k: String, default: Boolean) = if (o.has(k)) o.getBoolean(k) else default
            val sports = o.optJSONArray("fitnessSports")
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
            return GoalDraft(
                goalType = str("goalType", blank.goalType),
                eventName = str("eventName", blank.eventName),
                eventSport = str("eventSport", blank.eventSport),
                eventDate = str("eventDate", blank.eventDate),
                eventDistanceMeters = dbl("eventDistanceMeters"),
                ctlRampPerWeek = (dbl("ctlRampPerWeek") ?: blank.ctlRampPerWeek)
                    .coerceIn(GoalOptions.RAMP_MIN, GoalOptions.RAMP_MAX),
                fitnessSports = sports ?: blank.fitnessSports,
                targetWeeklyKm = dbl("targetWeeklyKm"),
                volumeSport = str("volumeSport", blank.volumeSport),
                daysPerWeek = int("daysPerWeek") ?: blank.daysPerWeek,
                planIntensity = dbl("planIntensity") ?: blank.planIntensity,
                mtbDiscipline = str("mtbDiscipline", blank.mtbDiscipline),
                cyclingDiscipline = str("cyclingDiscipline", blank.cyclingDiscipline),
                scheduleTests = bool("scheduleTests", blank.scheduleTests),
                includeStrength = bool("includeStrength", blank.includeStrength),
                strengthTier = int("strengthTier") ?: blank.strengthTier,
                strengthDaysPerWeek = int("strengthDaysPerWeek"),
                notes = str("notes", blank.notes),
                presetId = if (o.has("presetId")) o.getString("presetId") else null,
                eventDateAuto = bool("eventDateAuto", blank.eventDateAuto),
            )
        }
    }
}
