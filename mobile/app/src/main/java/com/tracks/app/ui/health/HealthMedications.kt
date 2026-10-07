// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.tracks.app.meds.DoseSlot
import com.tracks.app.meds.asNeededMedications
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.EvenGrid
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.OptionCell
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.Medication
import com.tracks.core.api.MedicationIn
import com.tracks.core.api.MedicationLog
import com.tracks.core.api.MedicationLogCreate
import com.tracks.core.api.MedicationScheduleIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Medications: what is due today, what is on the list, and what was taken.
 *
 * ## Why the phone gets the whole feature and not just a "Take" button
 *
 * The first version here was a read-only list with one button, on the reasoning
 * that adding a medication is setup and setup belongs on a desktop. That is
 * true of a bike profile and false of this: a prescription changes in a doctor's
 * office, a dose changes over the phone, and the device in the pocket at that
 * moment is this one. Sending someone to a laptop to record it means it gets
 * recorded late or not at all.
 *
 * Three tabs, matching the web app, because they are three different tasks with
 * different frequencies — *today* is opened every morning, *medications* a few
 * times a year, *history* when something needs checking.
 *
 * The due list is computed on the phone rather than fetched — see
 * [com.tracks.app.meds.dosesOn] for both reasons, one of which is a timezone bug
 * in the endpoint the browser uses.
 */
@Composable
fun MedicationsSection(
    medications: List<Medication>,
    doses: List<DoseSlot>,
    history: List<MedicationLog>,
    onLog: (MedicationLogCreate) -> Unit,
    onSave: (Int?, MedicationIn) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var tab by remember { mutableStateOf(MedTab.Today) }
    val due = doses.count { it.status == null }

    HealthSection(
        title = "Medications",
        trailing = {
            if (due > 0) {
                Text(
                    if (due == 1) "1 due today" else "$due due today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MedTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label) },
                )
            }
        }

        when (tab) {
            MedTab.Today -> TodayTab(medications, doses, onLog)
            MedTab.List -> ListTab(medications, onSave, onDelete)
            MedTab.History -> HistoryTab(medications, history)
        }
    }
}

private enum class MedTab(val label: String) {
    Today("Today"),
    List("Medications"),
    History("History"),
}

// ── Today ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayTab(
    medications: List<Medication>,
    doses: List<DoseSlot>,
    onLog: (MedicationLogCreate) -> Unit,
) {
    val now = remember { LocalDateTime.now() }
    val asNeeded = remember(medications) { asNeededMedications(medications) }

    if (doses.isEmpty() && asNeeded.isEmpty()) {
        Text(
            if (medications.isEmpty()) {
                "Nothing added yet. Use the Medications tab to add one."
            } else {
                "Nothing scheduled for today."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    doses.forEach { slot ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        slot.medication.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (slot.overdue(now)) {
                        Badge("Overdue", MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    listOfNotNull(slot.timeOfDay, slot.medication.doseLabel).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                // Deliberately not buttons once logged. Logging a second dose
                // because the control still looked tappable is a real-world
                // harm, not a UI annoyance.
                slot.taken -> Text(
                    "✓ Taken",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                slot.skipped -> Text(
                    "Skipped",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PrimaryButton("Taken", onClick = { onLog(slot.entry(MedicationLogCreate.STATUS_TAKEN)) })
                    NeutralButton("Skip", onClick = { onLog(slot.entry(MedicationLogCreate.STATUS_SKIPPED)) })
                }
            }
        }
    }

    if (asNeeded.isNotEmpty()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Text(
            "AS NEEDED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            asNeeded.forEach { medication ->
                AssistChip(
                    onClick = {
                        onLog(
                            MedicationLogCreate(
                                medicationId = medication.id,
                                status = MedicationLogCreate.STATUS_AS_NEEDED,
                            )
                        )
                    },
                    label = {
                        Text(
                            listOfNotNull(medication.name, medication.doseLabel)
                                .joinToString(" "),
                        )
                    },
                )
            }
        }
    }
}

/** The log entry this slot would produce. Carries the schedule and the time it
 *  was due, so a dose logged late is still filed against the right slot. */
private fun DoseSlot.entry(status: String) = MedicationLogCreate(
    medicationId = medication.id,
    scheduleId = scheduleId,
    status = status,
    scheduledFor = at.toString(),
)

// ── The list ─────────────────────────────────────────────────────────────────

@Composable
private fun ListTab(
    medications: List<Medication>,
    onSave: (Int?, MedicationIn) -> Unit,
    onDelete: (Int) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }

    if (adding) {
        MedicationForm(
            initial = null,
            onSave = {
                onSave(null, it)
                adding = false
            },
            onCancel = { adding = false },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    } else {
        TonalButton("Add medication", onClick = { adding = true; editing = null }, icon = Icons.Default.Add)
    }

    if (medications.isEmpty() && !adding) {
        Text(
            "No medications yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    medications.forEach { medication ->
        if (editing == medication.id) {
            MedicationForm(
                initial = medication,
                onSave = {
                    onSave(medication.id, it)
                    editing = null
                },
                onCancel = { editing = null },
                onDelete = {
                    onDelete(medication.id)
                    editing = null
                },
            )
            return@forEach
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        medication.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    medication.doseLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!medication.isActive) {
                        Badge("Inactive", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                medication.schedules.filterNot { it.isAsNeeded }.forEach { schedule ->
                    Text(
                        buildString {
                            append(schedule.timeOfDay)
                            append(" · ")
                            append(dayLabel(schedule.daysOfWeek))
                            if (schedule.notify) append(" · Reminder")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (medication.schedules.any { it.isAsNeeded }) {
                    Text(
                        "As needed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TonalButton("Edit", onClick = { editing = medication.id; adding = false }, small = true)
        }
    }
}

// ── History ──────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTab(medications: List<Medication>, history: List<MedicationLog>) {
    val names = remember(medications) { medications.associate { it.id to it.name } }
    if (history.isEmpty()) {
        Text(
            "Nothing logged in the last 30 days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        Modifier
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        history.sortedByDescending { it.loggedAt }.forEach { entry ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    buildString {
                        append(names[entry.medicationId] ?: "Unknown")
                        append("  ")
                        append(stamp(entry.loggedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (entry.status) {
                        MedicationLogCreate.STATUS_TAKEN -> "✓ Taken"
                        MedicationLogCreate.STATUS_SKIPPED -> "Skipped"
                        else -> "As needed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.status == MedicationLogCreate.STATUS_TAKEN) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ── The form ─────────────────────────────────────────────────────────────────

/**
 * Add or edit a medication and every time it is due.
 *
 * The schedule editor is the substance of it. A medication without times is a
 * note; a medication with them is something the phone can remind you about, and
 * the reminder switch is right there on the row it applies to rather than being
 * a single setting for the whole medication — morning and evening doses are
 * routinely not equally easy to forget.
 */
@Composable
private fun MedicationForm(
    initial: Medication?,
    onSave: (MedicationIn) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val today = remember { LocalDate.now().toString() }
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var dose by remember(initial) { mutableStateOf(initial?.dose.orEmpty()) }
    var doseUnit by remember(initial) { mutableStateOf(initial?.doseUnit ?: DOSE_UNITS.first()) }
    var form by remember(initial) { mutableStateOf(initial?.form ?: MED_FORMS.first()) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }
    var active by remember(initial) { mutableStateOf(initial?.isActive ?: true) }
    var schedules by remember(initial) {
        mutableStateOf(
            initial?.toInput()?.schedules?.takeIf { it.isNotEmpty() }
                ?: listOf(MedicationScheduleIn(timeOfDay = "08:00", startDate = today)),
        )
    }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dose,
                onValueChange = { dose = it },
                label = { Text("Dose") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            PickerField(
                label = "Unit",
                value = doseUnit,
                options = DOSE_UNITS,
                onSelect = { doseUnit = it },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField(
                label = "Form",
                value = form,
                options = MED_FORMS,
                onSelect = { form = it },
                modifier = Modifier.weight(1f),
            )
            PickerField(
                label = "Status",
                value = if (active) "Active" else "Inactive",
                options = listOf("Active", "Inactive"),
                onSelect = { active = it == "Active" },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SCHEDULE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TonalButton("Add time", onClick = {
                schedules = schedules + MedicationScheduleIn(
                    timeOfDay = "08:00",
                    startDate = today,
                )
            }, icon = Icons.Default.Add)
        }

        schedules.forEachIndexed { index, schedule ->
            ScheduleEditor(
                schedule = schedule,
                onChange = { updated ->
                    schedules = schedules.toMutableList().also { it[index] = updated }
                },
                onRemove = {
                    schedules = schedules.filterIndexed { i, _ -> i != index }
                },
            )
        }

        ButtonRow {
            PrimaryButton(
                "Save",
                onClick = {
                    onSave(
                        MedicationIn(
                            name = name.trim(),
                            dose = dose.trim().takeIf { it.isNotBlank() },
                            doseUnit = doseUnit,
                            form = form,
                            notes = notes.trim().takeIf { it.isNotBlank() },
                            isActive = active,
                            schedules = schedules,
                        )
                    )
                },
                // The server requires a name; a disabled button says so more
                // clearly than a 422 would.
                enabled = name.isNotBlank(),
            )
            NeutralButton("Cancel", onClick = onCancel)
            onDelete?.let {
                if (confirmingDelete) {
                    DangerButton("Delete?", onClick = it)
                } else {
                    DangerButton("Delete", onClick = { confirmingDelete = true })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(
    schedule: MedicationScheduleIn,
    onChange: (MedicationScheduleIn) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var pickingTime by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onChange(schedule.copy(notify = granted)) }

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tokens.Radius.lg),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TonalButton(if (schedule.isAsNeeded) "As needed" else schedule.timeOfDay, onClick = { pickingTime = true }, small = true)
                DangerButton("Remove", onClick = onRemove, small = true)
            }

            if (!schedule.isAsNeeded) {
                // Every day and the seven weekdays as one grid of eight: two
                // even rows of four at a phone's width.
                EvenGrid(spacing = 4.dp, maxColumns = 4) {
                    OptionCell(
                        "Every day",
                        selected = schedule.daysOfWeek.isNullOrEmpty(),
                        onClick = { onChange(schedule.copy(daysOfWeek = null)) },
                    )
                    WEEKDAYS.forEachIndexed { index, label ->
                        val chosen = schedule.daysOfWeek?.contains(index) == true
                        OptionCell(
                            label,
                            selected = chosen,
                            onClick = {
                                val current = schedule.daysOfWeek.orEmpty()
                                val next = if (chosen) current - index else (current + index).sorted()
                                onChange(schedule.copy(daysOfWeek = next.ifEmpty { null }))
                            },
                            multi = true,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = schedule.notify,
                    onCheckedChange = { wants ->
                        // The permission is asked for here rather than at
                        // startup: a user who never sets a reminder is never
                        // prompted, which is the whole argument for asking late.
                        if (wants && !canNotify(context)) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onChange(schedule.copy(notify = wants))
                        }
                    },
                    enabled = !schedule.isAsNeeded,
                )
                Text(
                    "Remind me",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Checkbox(
                    checked = schedule.isAsNeeded,
                    onCheckedChange = {
                        onChange(schedule.copy(isAsNeeded = it, notify = if (it) false else schedule.notify))
                    },
                )
                Text(
                    "As needed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            initial = schedule.timeOfDay,
            onPick = {
                onChange(schedule.copy(timeOfDay = it))
                pickingTime = false
            },
            onDismiss = { pickingTime = false },
        )
    }
}

/**
 * Whether a notification would actually appear.
 *
 * Both halves matter: the runtime grant on Android 13+, and the user having not
 * turned the app's notifications off in system settings, which no permission
 * check catches.
 */
private fun canNotify(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * A time, typed.
 *
 * [TimeInput] rather than the clock face: this is a habitual value someone
 * already knows ("eight in the morning"), and typing four digits beats dragging
 * a hand around a dial for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
    initial: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Dose time",
) {
    val parsed = remember(initial) { com.tracks.app.meds.parseTime(initial) }
    val state = rememberTimePickerState(
        initialHour = parsed.hour,
        initialMinute = parsed.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = {
            PrimaryButton("OK", onClick = {
                onPick("%02d:%02d".format(state.hour, state.minute))
            })
        },
        dismissButton = { NeutralButton("Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(Tokens.Radius.md), color = color.copy(alpha = 0.16f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Sunday first, matching the day numbers the server stores. */
private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

private val MED_FORMS = listOf(
    "Tablet", "Capsule", "Liquid", "Injection", "Patch", "Cream", "Inhaler", "Drop", "Other",
)

private val DOSE_UNITS = listOf(
    "mg", "mcg", "g", "ml", "IU", "tablet(s)", "capsule(s)", "drop(s)", "puff(s)", "unit(s)",
)

private fun dayLabel(days: List<Int>?): String =
    if (days.isNullOrEmpty()) "Every day" else days.sorted().joinToString(", ") { WEEKDAYS[it % 7] }

private fun stamp(iso: String): String {
    val parsed = com.tracks.app.meds.localDateOf(iso) ?: return iso.take(10)
    return parsed.format(STAMP)
}

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
