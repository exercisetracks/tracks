// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracks.app.AppContainer
import com.tracks.app.ui.theme.Accent
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.core.plan.PlanStaleness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The account's settings row, as the profile forms see it.
 *
 * One view model behind two screens — onboarding and Settings — because they
 * edit the same fields, and two copies of "what a zone setting looks like"
 * would drift the way the phone and the web once did. Every write goes
 * straight to the replica as a stamped edit, so a change made here merges with
 * one made on the web field by field (spec/sync.yaml `settings`).
 */
class ProfileViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init { reload() }

    fun reload() = viewModelScope.launch {
        val values = runCatching { container.sources.settingValues() }.getOrDefault(emptyMap())
        _state.value = ProfileState(values, loaded = true)
        // After the form is up, not before: this reads every activity, and
        // the values only fill in the "Auto · …" line under each threshold.
        val auto = runCatching { container.sources.autoThresholds() }.getOrNull() ?: return@launch
        _state.value = _state.value.copy(
            auto = listOfNotNull(
                auto.maxHr?.let { "max_hr" to it },
                auto.thresholdHr?.let { "threshold_hr" to it },
                auto.ftp?.let { "ftp" to it },
            ).toMap(),
        )
    }

    /**
     * Write one setting. Theme, accent and units go through the container so
     * its first-frame cache moves with them; everything else is a plain field.
     */
    fun set(field: String, value: Any?) = viewModelScope.launch {
        val before = _state.value.values[field]
        // Optimistic, so a toggle does not flick back while the write lands.
        _state.value = _state.value.copy(values = _state.value.values + (field to value))
        when (field) {
            "theme_mode" -> container.setThemeMode(ThemeMode.of(value as String?))
            "accent_color" -> container.setAccent(Accent.of(value as String?))
            "units" -> container.setImperial(value == "imperial")
            else -> {
                container.sources.writeSetting(field, value)
                if (field == "sex") container.bodyFemale.value = value == "female"
            }
        }
        // Units, thresholds, equipment, experience, frequency: the plan is
        // built from them, so it is rebuilt — as the server does for the same
        // change — rather than left for a Regenerate button that no longer exists.
        if (PlanStaleness.settingStalesPlan(field) && before != value) container.planInputsChanged()
    }
}

/** Plain values from the settings row, with the web's defaults where a field was never set. */
data class ProfileState(
    val values: Map<String, Any?> = emptyMap(),
    val loaded: Boolean = false,
    /**
     * What Auto works out for each threshold (`max_hr`, `threshold_hr`,
     * `ftp`), shown whichever mode is chosen — the web's `*_auto` values.
     * Absent until there is enough history.
     */
    val auto: Map<String, Double> = emptyMap(),
) {
    fun str(field: String): String? = values[field]?.toString()?.takeIf { it.isNotEmpty() }
    fun num(field: String): Double? = when (val v = values[field]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    fun bool(field: String, default: Boolean): Boolean = when (val v = values[field]) {
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: default
        else -> default
    }
    @Suppress("UNCHECKED_CAST")
    fun list(field: String): List<String> = (values[field] as? List<String>) ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    fun map(field: String): Map<String, Any?> = (values[field] as? Map<String, Any?>) ?: emptyMap()

    val imperial: Boolean get() = str("units") == "imperial"
}
