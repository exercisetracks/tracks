// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.OptionGrid
import com.tracks.app.ui.components.TracksSwitch
import com.tracks.app.ui.theme.Accent
import com.tracks.app.ui.theme.ThemeMode
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.spec.equipmentOptions
import com.tracks.core.spec.experienceLevels
import com.tracks.core.spec.experienceTable
import kotlin.math.roundToInt

// The profile forms, shared by onboarding and Settings. Each takes the state
// and a setter, and writes on commit — a toggle at once, a number when its
// field loses focus or the keyboard's Done is pressed — rather than on every
// keystroke, which would stamp a dozen edits for one typed weight.

typealias SetField = (field: String, value: Any?) -> Unit

private const val KG_PER_LB = 0.45359237
private const val CM_PER_IN = 2.54

/**
 * Name, units, biological sex, weight, height and birth year — the web's
 * Body step.
 *
 * No time zone: it is the phone's own, kept in step automatically
 * (AppContainer.syncTimezone), so there is nothing to ask.
 */
@Composable
fun BodyForm(state: ProfileState, set: SetField) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4)) {
        TextSettingField("Name", state.str("name")) { set("name", it) }
        Labelled("Units") {
            SegmentedChoice(
                options = listOf("metric" to "Metric", "imperial" to "Imperial"),
                selected = state.str("units") ?: "metric",
                onSelect = {
                    // Commit a half-typed weight or height first, in the units
                    // it was typed in. Tapping a unit does not take focus, so
                    // without this the draft survived the switch unconverted
                    // ("180" cm became "180 in") and was then saved in the new
                    // unit when the field finally lost focus.
                    focus.clearFocus()
                    set("units", it)
                },
            )
        }
        Labelled("Biological sex", "Chooses the muscle anatomy model, and sets starting run paces.") {
            SegmentedChoice(
                options = listOf("male" to "Male", "female" to "Female"),
                selected = state.str("sex"),
                onSelect = { set("sex", it) },
            )
        }
        val imperial = state.imperial
        // Stored metric, shown in the person's units, as on the web.
        NumberSettingField(
            label = "Weight",
            suffix = if (imperial) "lb" else "kg",
            value = state.num("weight_kg")?.let { if (imperial) it / KG_PER_LB else it },
            onCommit = { v -> set("weight_kg", v?.let { round1(if (imperial) it * KG_PER_LB else it) }) },
        )
        NumberSettingField(
            label = "Height",
            suffix = if (imperial) "in" else "cm",
            value = state.num("height_cm")?.let { if (imperial) it / CM_PER_IN else it },
            onCommit = { v -> set("height_cm", v?.let { round1(if (imperial) it * CM_PER_IN else it) }) },
        )
        // Optional. With height, weight and sex it sets the paces a running
        // plan starts from until there are runs to measure
        // (com.tracks.core.plan.RunningFitness). A year that is not a
        // plausible birth year is ignored rather than saved.
        Labelled("Birth year", "Optional. Sets starting run paces until you have runs recorded.") {
            NumberSettingField("Year", "", state.num("birth_year")) { v ->
                val year = v?.toInt()
                when {
                    v == null -> set("birth_year", null)
                    year != null && year in 1900..java.time.Year.now().value -> set("birth_year", year)
                }
            }
        }
    }
}

/**
 * Max HR, threshold HR, FTP and swim CSS — each automatic (worked out from
 * history) or set by hand, like the web's Zones step.
 */
@Composable
fun ZonesForm(state: ProfileState, set: SetField) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s5)) {
        ZoneSetting(state, set, "max_hr", "Max heart rate", "bpm",
            "Auto uses the highest you have recorded.")
        ZoneSetting(state, set, "threshold_hr", "Threshold heart rate", "bpm",
            "Auto estimates it from your hardest sustained efforts.")
        ZoneSetting(state, set, "ftp", "FTP", "W",
            "Auto estimates it from your best 20-minute power.")
        ZoneSetting(state, set, "css", "Critical swim speed", "s / 100 m",
            "Auto estimates it from your swims.")
    }
}

@Composable
private fun ZoneSetting(
    state: ProfileState, set: SetField,
    key: String, label: String, unit: String, autoNote: String,
) {
    val manual = state.str("${key}_mode") == "manual"
    Labelled(label, autoNote) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
            SegmentedChoice(
                options = listOf("auto" to "Auto", "manual" to "Manual"),
                selected = if (manual) "manual" else "auto",
                onSelect = { set("${key}_mode", it) },
            )
            if (manual) {
                NumberSettingField(label, unit, state.num("${key}_manual")) { set("${key}_manual", it) }
            }
        }
    }
}

/**
 * How often each endurance sport is done — `activity_frequency`, a map of
 * sport family to level (com.tracks.core.plan.PlanStart).
 *
 * The plan reads it only while there is no history of the sport, to decide
 * where a first plan starts: before it, someone who had never run and someone
 * who runs five times a week got the same first week. Running and cycling are
 * always asked; other sports get a row once picked. Tapping the chosen level
 * again clears it, as strength experience does — "not answered" keeps the
 * old default, which is a real choice.
 */
@Composable
fun FrequencyForm(state: ProfileState, set: SetField) {
    val answers = state.map("activity_frequency")
    fun write(family: String, level: String?) {
        val next = LinkedHashMap(answers).apply { if (level == null) remove(family) else put(family, level) }
        set("activity_frequency", next.toMap())
    }
    // Rows beyond running and cycling: those already answered, plus any
    // picked in this visit and not yet answered.
    var added by remember { mutableStateOf(emptySet<String>()) }
    val shown = FREQUENCY_ALWAYS + FREQUENCY_MORE.map { it.first }.filter { it in answers || it in added }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4)) {
        for (family in shown) {
            val current = answers[family] as? String
            Labelled(FREQUENCY_SPORTS.getValue(family)) {
                OptionGrid(
                    FREQUENCY_LEVELS,
                    isSelected = { it == current },
                    onPick = { write(family, if (it == current) null else it) },
                )
            }
        }
        Labelled("Other sports") {
            OptionGrid(
                FREQUENCY_MORE,
                isSelected = { it in shown },
                onPick = { family ->
                    if (family in shown) {
                        added = added - family
                        if (family in answers) write(family, null)
                    } else {
                        added = added + family
                    }
                },
                multi = true,
            )
        }
    }
}

/** The levels, as calculators/plan/starting.py names them. */
internal val FREQUENCY_LEVELS = listOf(
    "never" to "Never",
    "occasional" to "Now and then",
    "1_2" to "1–2× a week",
    "3_4" to "3–4× a week",
    "5_plus" to "5+× a week",
)
private val FREQUENCY_ALWAYS = listOf("running", "cycling")
/** Sport families (PlanBase.sportFamily) a plan can be built for. */
private val FREQUENCY_MORE = listOf(
    "swimming" to "Swimming",
    "mountain_biking" to "Mountain biking",
    "hiking" to "Hiking",
    "rowing" to "Rowing",
    "nordic_skiing" to "XC skiing",
    "climbing" to "Climbing",
)
private val FREQUENCY_SPORTS = mapOf("running" to "Running", "cycling" to "Cycling") + FREQUENCY_MORE

/** Equipment and experience — what strength sessions may use, and how hard they start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrengthForm(state: ProfileState, set: SetField) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4)) {
        Labelled("Equipment you have", "Sessions only use what is ticked here.") {
            EquipmentPicker(state.list("equipment_available").toSet()) { set("equipment_available", it) }
        }
        Labelled("Experience") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                val current = state.str("strength_experience")
                for (level in experienceLevels) {
                    val entry = experienceTable[level] ?: continue
                    ChoiceCard(
                        title = entry.label,
                        body = entry.blurb,
                        selected = current == level,
                        // Tapping the chosen level again clears it, as on the web.
                        onClick = { set("strength_experience", if (current == level) null else level) },
                    )
                }
            }
        }
    }
}

/**
 * The owned-equipment grid. Shared with the Strength page's equipment sheet so
 * the two edit the one synced `equipment_available` field the same way.
 * [onChange] gets the whole new list, in [equipmentOptions] order, so two
 * devices ticking the same set write the same value.
 */
@Composable
fun EquipmentPicker(have: Set<String>, onChange: (List<String>) -> Unit) {
    OptionGrid(
        equipmentOptions.map { it.value to it.label },
        isSelected = { it in have },
        onPick = { value ->
            val next = if (value in have) have - value else have + value
            onChange(equipmentOptions.map { it.value }.filter { it in next })
        },
        multi = true,
    )
}

/** Light/dark and accent — shared with the web, like everything else here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookForm(state: ProfileState, set: SetField) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4)) {
        Labelled("Colour scheme", "Shared with Tracks on the web. System follows each device's own setting.") {
            SegmentedChoice(
                options = ThemeMode.entries.map { it.key to it.label },
                selected = ThemeMode.of(state.str("theme_mode")).key,
                onSelect = { set("theme_mode", it) },
            )
        }
        Labelled("Accent colour") {
            val current = Accent.of(state.str("accent_color"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
                for (a in Accent.entries) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(a.swatch, CircleShape)
                            .border(
                                if (a == current) 3.dp else 0.dp,
                                MaterialTheme.colorScheme.onSurface,
                                CircleShape,
                            )
                            .clickable { set("accent_color", a.key) },
                    )
                }
            }
        }
    }
}

/** Sports to leave out of every chart and total, chart detail, and on-watch pace coaching. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingPrefsForm(state: ProfileState, set: SetField, sportLabel: (String) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4)) {
        Labelled("Hidden sports", "Left out of metrics, charts and training load.") {
            if (state.sports.isEmpty()) {
                Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                val hidden = state.list("hidden_sports").toSet()
                OptionGrid(
                    state.sports.map { it to sportLabel(it) },
                    isSelected = { it in hidden },
                    onPick = { sport -> set("hidden_sports", (if (sport in hidden) hidden - sport else hidden + sport).sorted()) },
                    multi = true,
                )
            }
        }
        Labelled("Chart detail") {
            SegmentedChoice(
                options = listOf("low" to "Low", "medium" to "Medium", "high" to "High", "raw" to "Raw"),
                selected = state.str("chart_resolution") ?: "high",
                onSelect = { set("chart_resolution", it) },
            )
        }
        if (com.tracks.app.ui.components.LocalHasDevice.current) {
            ToggleRow(
                "Pace coaching on the watch",
                "Planned runs carry pace targets the watch coaches you to.",
                state.bool("pace_coaching", false),
            ) { set("pace_coaching", it) }
        }
    }
}

/**
 * What reaches out beyond this phone. Each is off-device traffic, which is why
 * they are switches and why the copy says where each one goes.
 */
@Composable
fun FeaturesForm(state: ProfileState, set: SetField) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        ToggleRow("Race weather", "Forecasts for race-day predictions, fetched by your server.",
            state.bool("weather_enabled", true)) { set("weather_enabled", it) }
        ToggleRow("Map tiles", "Basemaps from your Tracks server.",
            state.bool("map_enabled", false)) { set("map_enabled", it) }
        ToggleRow("Wildfire overlay", "Active fire perimeters on the map.",
            state.bool("wildfire_enabled", false)) { set("wildfire_enabled", it) }
    }
}

/** Satellite pre-fetch (AGPS) for faster GPS locks on the watch. */
@Composable
fun AgpsForm(state: ProfileState, set: SetField) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
        val on = state.bool("agps_enabled", false)
        ToggleRow("Satellite pre-fetch", "Faster GPS locks after the watch syncs.", on) { set("agps_enabled", it) }
        if (on) {
            SegmentedChoice(
                options = listOf("garmin" to "Garmin EPO", "custom" to "Custom URL"),
                selected = state.str("agps_source") ?: "garmin",
                onSelect = { set("agps_source", it) },
            )
            if (state.str("agps_source") == "custom") {
                TextSettingField("Custom URL", state.str("agps_custom_url")) { set("agps_custom_url", it) }
            }
            NumberSettingField("Refresh after", "hours", state.num("agps_max_age_hours")) {
                set("agps_max_age_hours", it?.roundToInt())
            }
        }
    }
}

// ── Primitives ───────────────────────────────────────────────────────────────

@Composable
fun Labelled(label: String, note: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        LabelWithTip(label, note, MaterialTheme.typography.titleSmall)
        content()
    }
}

/** The web's segmented button row: one bordered strip, the chosen option filled. */
@Composable
fun SegmentedChoice(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                        when (i) {
                            0 -> RoundedCornerShape(topStart = Tokens.Radius.lg, bottomStart = Tokens.Radius.lg)
                            options.lastIndex -> RoundedCornerShape(topEnd = Tokens.Radius.lg, bottomEnd = Tokens.Radius.lg)
                            else -> RoundedCornerShape(0.dp)
                        },
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = Tokens.Space.s2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(end = Tokens.Space.s3)) {
            LabelWithTip(title, body, MaterialTheme.typography.bodyLarge)
        }
        TracksSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A label with its explanation behind a "?" rather than printed under it: the
 * user found the settings a wall of hints, each read once and then only in
 * the way.
 */
@Composable
private fun LabelWithTip(label: String, note: String?, style: androidx.compose.ui.text.TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        Text(label, style = style)
        note?.let { com.tracks.app.ui.components.InfoTip(com.tracks.app.ui.components.MetricInfo(label, it)) }
    }
}

@Composable
fun ChoiceCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Card.radius)
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    if (selected) 2.dp else Tokens.Card.border,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(Tokens.Card.padding),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A text setting that writes when focus leaves or Done is pressed — not per keystroke. */
@Composable
fun TextSettingField(label: String, value: String?, onCommit: (String?) -> Unit) {
    var text by remember(value) { mutableStateOf(value.orEmpty()) }
    val focus = LocalFocusManager.current
    fun commit() { if (text != value.orEmpty()) onCommit(text.trim().ifEmpty { null }) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
    )
}

/** A number setting; blank means "not set". Written on commit, like [TextSettingField]. */
@Composable
fun NumberSettingField(label: String, suffix: String, value: Double?, onCommit: (Double?) -> Unit) {
    val shown = value?.let { formatNumber(it) }.orEmpty()
    var text by remember(shown) { mutableStateOf(shown) }
    val focus = LocalFocusManager.current
    fun commit() {
        if (text == shown) return
        val parsed = text.trim().replace(',', '.').toDoubleOrNull()
        if (text.isBlank() || parsed != null) onCommit(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        isError = text.isNotBlank() && text.trim().replace(',', '.').toDoubleOrNull() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
    )
}

internal fun formatNumber(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.1f".format(v)

internal fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
