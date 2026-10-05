// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.Explain
import com.tracks.app.ui.components.InfoTip
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.OptionGrid
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.StretchColumn
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.components.TracksSwitch
import com.tracks.app.ui.components.dayLabel
import com.tracks.app.ui.components.fillRemaining
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.format.Units
import com.tracks.core.local.RecentLoad
import com.tracks.core.plan.EventDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The goal form — the web's New goal form (components/goals/NewGoalForm.jsx)
 * as a sheet: the three goal types, and for each the fields the web asks for.
 *
 * Distances are typed in the account's units and stored in metres (event) or
 * kilometres (weekly volume), as the web does. Save stays disabled with the
 * reason shown until the type's required fields are filled in
 * ([GoalDraft.missing]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoalEditorSheet(
    initial: GoalDraft,
    onSave: (GoalDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    /**
     * This phone's recent training, for the recommended event date; null
     * until it has been read (the date then fills in when it arrives).
     */
    eventLoad: RecentLoad? = null,
    /**
     * Copies the calendar subscription link. Only for a goal being edited,
     * and only with a server linked — the feed is a URL on the server.
     */
    onCopyLink: (() -> Unit)? = null,
    today: LocalDate = LocalDate.now(),
) {
    var d by remember { mutableStateOf(initial) }
    var pickingDate by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val imperial = Units.imperial

    // The recommended date follows the sport and distance while nobody has
    // picked one; the reasons stay behind the date's "?" either way.
    LaunchedEffect(eventLoad, d.goalType, d.eventSport, d.eventDistanceMeters) {
        if (eventLoad != null && d.goalType == "event") {
            d = d.withAdvice(eventLoad.recommend(d.eventSport, d.eventDistanceMeters, CivilDate(today.year, today.monthValue, today.dayOfMonth)))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Full height whatever the goal type. Sized to its content, the sheet
        // jumped up and down as the type changed which fields it shows; now
        // the notes field takes up whatever the fields leave, and when they
        // leave nothing the whole thing scrolls as before.
        BoxWithConstraints(Modifier.fillMaxHeight()) {
        StretchColumn(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = Tokens.Space.s4)
                .padding(bottom = Tokens.Space.s8),
            spacing = Tokens.Space.s3,
        ) {
            Text(if (d.isNew) "New goal" else "Edit goal", style = MaterialTheme.typography.titleLarge)

            if (d.isNew) {
                GoalOptions.TYPES.forEach { (id, title, blurb) ->
                    val on = d.goalType == id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Tokens.Radius.lg))
                            .border(
                                if (on) 2.dp else 1.dp,
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(Tokens.Radius.lg),
                            )
                            .clickable { d = d.copy(goalType = id) }
                            .padding(Tokens.Space.s3),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            when (d.goalType) {
                "event" -> {
                    Label("Sport")
                    Chips(GoalOptions.SPORTS.entries.map { it.key to it.value }, GoalOptions.sportChip(d.eventSport)) { d = d.pickSport(it) }
                    Variants(d.eventSport) { d = d.withVariant(it) }
                    GoalOptions.PRESETS[GoalOptions.sportChip(d.eventSport)]?.let { presets ->
                        Label("Event")
                        Chips(presets.map { it.id to it.label }, d.presetId) { id ->
                            d = d.pickPreset(presets.first { it.id == id })
                        }
                    }
                    NumberField(
                        label = if (imperial) "Distance (mi)" else "Distance (km)",
                        value = d.eventDistanceMeters?.let { it / if (imperial) 1609.34 else 1000.0 },
                    ) { v -> d = d.copy(eventDistanceMeters = v?.let { it * if (imperial) 1609.34 else 1000.0 }, presetId = null) }
                    OutlinedTextField(
                        d.eventName, { d = d.copy(eventName = it) },
                        label = { Text("Event name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
                        DateButton("Event date", d.eventDate, Modifier.weight(1f)) { pickingDate = "event" }
                        d.dateAdvice?.let { InfoTip(adviceInfo(it)) }
                    }

                    Label("Workout days per week")
                    Chips((2..7).map { "$it" to "$it" }, "${d.daysPerWeek}") { d = d.copy(daysPerWeek = it.toInt()) }
                    SliderLabel(
                        "Intensity — ${GoalOptions.intensityLabel(d.planIntensity)}",
                        Explain.PlanIntensity,
                    )
                    IntensitySlider(
                        value = d.planIntensity.toFloat(),
                        onValueChange = { d = d.copy(planIntensity = it.toDouble()) },
                    )
                    if (d.eventSport == "mountain biking") {
                        Label("Discipline")
                        Disciplines(GoalOptions.MTB_DISCIPLINES, d.mtbDiscipline) { d = d.copy(mtbDiscipline = it) }
                    }
                    if (d.eventSport in GoalDraft.ROAD_CYCLING) {
                        Label("Discipline")
                        Disciplines(GoalOptions.CYCLING_DISCIPLINES, d.cyclingDiscipline) { d = d.copy(cyclingDiscipline = it) }
                    }
                    if (d.eventSport == "mountain biking" || d.eventSport in GoalDraft.ROAD_CYCLING) {
                        Toggle(
                            "Schedule field tests",
                            "FTP and sprint tests at 10%, 45% and 75% of the plan; results update your thresholds.",
                            d.scheduleTests,
                        ) { d = d.copy(scheduleTests = it) }
                    }
                    Toggle("Include strength", "Strength sessions woven around the plan.", d.includeStrength) {
                        d = d.copy(includeStrength = it)
                    }
                    if (d.includeStrength) {
                        SliderLabel("Strength focus — ${GoalOptions.strengthFocusLabel(d.strengthTier)}", Explain.StrengthFocus)
                        StrengthFocusSlider(d.strengthTier, { d = d.copy(strengthTier = it) })
                    }
                }
                "fitness" -> {
                    // Several sports: the plan shares the week's load between
                    // them. Why and how is behind the "?", not on the form.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
                        Label("Sports")
                        InfoTip(Explain.FitnessSports)
                    }
                    OptionGrid(
                        GoalOptions.FITNESS_SPORTS,
                        isSelected = { it in d.fitnessSports },
                        onPick = { d = d.toggleFitnessSport(it) },
                        multi = true,
                    )
                    RampSlider(d.ctlRampPerWeek, { d = d.copy(ctlRampPerWeek = it) })
                    Label("Workout days per week")
                    Chips((2..7).map { "$it" to "$it" }, "${d.daysPerWeek}") { d = d.copy(daysPerWeek = it.toInt()) }
                    Toggle("Include strength", "Strength sessions woven around the plan.", d.includeStrength) {
                        d = d.copy(includeStrength = it)
                    }
                    if (d.includeStrength) {
                        SliderLabel("Strength focus — ${GoalOptions.strengthFocusLabel(d.strengthTier)}", Explain.StrengthFocus)
                        StrengthFocusSlider(d.strengthTier, { d = d.copy(strengthTier = it) })
                    }
                }
                "volume_target" -> {
                    NumberField(
                        if (imperial) "Weekly distance (mi)" else "Weekly distance (km)",
                        d.targetWeeklyKm?.let { if (imperial) it / 1.60934 else it },
                    ) { v -> d = d.copy(targetWeeklyKm = v?.let { if (imperial) it * 1.60934 else it }) }
                    Label("Sport")
                    Chips(GoalOptions.SPORTS.entries.map { it.key to it.value }, d.volumeSport) { d = d.copy(volumeSport = it) }
                }
            }

            OutlinedTextField(
                d.notes, { d = d.copy(notes = it) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().fillRemaining(minHeight = 96.dp),
            )

            // The calendar feed is the plan's, so it lives with the plan's goal —
            // and a goal not yet saved has no plan to subscribe to.
            if (!d.isNew && onCopyLink != null) {
                TonalButton("Copy subscription link", onClick = onCopyLink, modifier = Modifier.fillMaxWidth())
            }

            d.missing?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ButtonRow {
                PrimaryButton(if (d.isNew) "Create goal" else "Save", onClick = { onSave(d) }, enabled = d.missing == null)
                NeutralButton("Cancel", onClick = onDismiss)
                onDelete?.let {
                    DangerButton(if (confirmDelete) "Tap again" else "Delete", onClick = { if (confirmDelete) it() else confirmDelete = true })
                }
            }
        }
        }
    }

    pickingDate?.let { which ->
        val current = d.eventDate
        val start = runCatching { LocalDate.parse(current) }.getOrNull() ?: LocalDate.now().plusWeeks(12)
        val picker = rememberDatePickerState(start.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = null },
            confirmButton = {
                PrimaryButton("OK", onClick = {
                    picker.selectedDateMillis?.let { ms ->
                        val iso = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        d = d.copy(eventDate = iso, eventDateAuto = false)
                    }
                    pickingDate = null
                })
            },
            dismissButton = { NeutralButton("Cancel", onClick = { pickingDate = null }) },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun Label(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** A one-of choice, justified to the sheet's width — see components/Justified.kt. */
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) =
    OptionGrid(options, isSelected = { it == selected }, onPick = onPick)

/**
 * Which kind of swimming or skiing ([GoalOptions.SPORT_VARIANTS]), for the
 * sports that have kinds; the explanation is behind the "?".
 */
@Composable
private fun Variants(sport: String, onPick: (String) -> Unit) {
    val options = GoalOptions.SPORT_VARIANTS[GoalOptions.sportChip(sport)] ?: return
    val selected = GoalOptions.variantOf(sport)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        Label("Kind")
        InfoTip(if (GoalOptions.sportChip(sport) == "skiing") Explain.SkiVariant else Explain.SwimVariant)
    }
    Chips(options, selected, onPick)
}

@Composable
private fun Disciplines(options: List<Triple<String, String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        options.forEach { (key, label, blurb) ->
            val on = key == selected
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Tokens.Radius.lg))
                    .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onPick(key) }
                    .padding(horizontal = Tokens.Space.s3, vertical = Tokens.Space.s2),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, blurb: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(blurb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TracksSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: Double?, onChange: (Double?) -> Unit) {
    // Text state of its own: a number field that reformats on every keystroke
    // eats a trailing "." before the decimal can be typed.
    fun show(v: Double?) = v?.let { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.2f".format(it) }.orEmpty()
    var text by remember { mutableStateOf(show(value)) }
    // ...but follow a value set from outside (a preset picked), which the
    // field used to ignore: it kept showing the last distance typed or picked.
    // Within a hair of what is typed is the same value, not an outside one —
    // a mile typed comes back through metres a few ulps off.
    LaunchedEffect(value) {
        val typed = text.replace(',', '.').toDoubleOrNull()
        if (value == null && typed != null || value != null && (typed == null || kotlin.math.abs(typed - value) > 1e-6)) {
            text = show(value)
        }
    }
    OutlinedTextField(
        text,
        { t -> text = t; onChange(t.replace(',', '.').toDoubleOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DateButton(label: String, iso: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TonalButton(if (iso.isBlank()) "$label…" else "$label: ${dayLabel(iso)}", onClick = onClick, modifier = modifier.fillMaxWidth())
}

/** A slider's heading, its value in words, and the "?" that explains it. */
@Composable
private fun SliderLabel(text: String, info: MetricInfo) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
        Label(text)
        InfoTip(info)
    }
}

/** The recommended date's reasons, for the "?" beside the date. */
internal fun adviceInfo(a: EventDate.Advice): MetricInfo = MetricInfo(
    "Recommended date",
    a.reasons + "Recommended: ${java.time.LocalDate.of(a.date.year, a.date.month, a.date.day).format(ADVICE_DAY)}. " +
        "Change it to your event's real date.",
)

private val ADVICE_DAY = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy")
