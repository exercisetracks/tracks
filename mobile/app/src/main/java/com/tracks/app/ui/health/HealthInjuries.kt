// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.Injury
import com.tracks.core.api.InjuryActivity
import com.tracks.core.api.InjuryCreate
import com.tracks.core.api.InjuryUpdate
import com.tracks.core.format.distance
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Injuries: what is open, what has healed, and when each of them ran.
 *
 * ## Why this stopped being two text fields
 *
 * The first version was a body-part field, a type field and five severity
 * chips — quicker to write and wrong in three ways. Free text meant "IT band",
 * "ITB" and "IT Band" were three different injuries to anything counting them,
 * and the browser has offered fixed lists since the beginning, so the two
 * clients were filling one column with different vocabularies. Severity ran 1–5
 * here and 1–10 on the server, so a phone could not express the top half of the
 * scale at all and a 7 entered on the desktop came back as a level this screen
 * had no chip for. And nothing could be *edited*: a wrong start date meant
 * deleting the injury and its history.
 *
 * ## The timeline
 *
 * A list answers "what am I dealing with". It cannot answer "is this the third
 * time this year my left knee has gone", which is the question that changes what
 * someone does about it. One lane per injury on a shared date axis makes the
 * repeats and the overlaps visible at a glance — an ongoing injury runs to
 * today's edge, a healed one stops where it stopped.
 */
@Composable
fun InjuriesSection(
    injuries: List<Injury>,
    onLog: (InjuryCreate) -> Unit,
    onUpdate: (Int, InjuryUpdate) -> Unit,
    onHeal: (Injury) -> Unit,
    onDelete: (Int) -> Unit,
    /** What was happening around an injury — see [InjuryActivitiesSheet]. */
    loadActivities: suspend (Int) -> List<InjuryActivity> = { emptyList() },
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var opened by remember { mutableStateOf<Injury?>(null) }
    var showHealed by remember { mutableStateOf(false) }

    val active = injuries.filter { it.isActive }.sortedByDescending { it.severity }
    val healed = injuries.filterNot { it.isActive }.sortedByDescending { it.startDate }

    HealthSection(title = "Injuries") {
        if (!adding) {
            // The same control the Body group uses to open its form, because
            // it is the same offer: something happened that the watch cannot
            // see. A bare text link beside the heading made one of the two look
            // like an action and the other like an afterthought.
            LogButton("Log an injury", onClick = { adding = true; editing = null })
        }

        if (adding) {
            InjuryForm(
                initial = null,
                onSave = { draft ->
                    onLog(draft.toCreate())
                    adding = false
                },
                onCancel = { adding = false },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        }

        if (injuries.isEmpty()) {
            Text(
                "Nothing logged. That is the good outcome.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@HealthSection
        }

        InjuryTimeline(
            injuries = injuries,
            selected = selected,
            onSelect = { id ->
                selected = id
                opened = injuries.firstOrNull { it.id == id }
            },
        )

        active.forEach { injury ->
            InjuryEntry(
                injury = injury,
                editing = editing == injury.id,
                highlighted = selected == injury.id,
                onOpen = { selected = injury.id; opened = injury },
                onEdit = { editing = if (editing == injury.id) null else injury.id },
                onSave = { draft ->
                    onUpdate(injury.id, draft.toUpdate())
                    editing = null
                },
                onHeal = { onHeal(injury) },
                onDelete = { onDelete(injury.id) },
            )
        }

        if (healed.isNotEmpty()) {
            NeutralButton(
                if (showHealed) "Hide healed (${healed.size})" else "Show healed (${healed.size})",
                onClick = { showHealed = !showHealed },
            )
            if (showHealed) {
                healed.forEach { injury ->
                    InjuryEntry(
                        injury = injury,
                        editing = editing == injury.id,
                        highlighted = selected == injury.id,
                        onOpen = { selected = injury.id; opened = injury },
                        onEdit = { editing = if (editing == injury.id) null else injury.id },
                        onSave = { draft ->
                            onUpdate(injury.id, draft.toUpdate())
                            editing = null
                        },
                        onHeal = null,
                        onDelete = { onDelete(injury.id) },
                    )
                }
            }
        }
    }

    opened?.let { injury ->
        InjuryActivitiesSheet(
            injury = injury,
            load = loadActivities,
            onDismiss = { opened = null },
        )
    }
}


/**
 * What you were doing around an injury.
 *
 * The question this answers is the one people actually open an injury for, and
 * it has two halves. Before it started: what might have caused it — the long
 * run on the Sunday, the session that felt wrong. After: what you went back to,
 * and how soon. So the list is split at day zero rather than sorted by date and
 * left for the reader to work out where the injury falls, and every row carries
 * its distance from that day.
 *
 * A week either side, chosen by the server so the two clients cannot disagree
 * about what "around" means.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InjuryActivitiesSheet(
    injury: Injury,
    load: suspend (Int) -> List<InjuryActivity>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember(injury.id) { mutableStateOf(true) }
    var activities by remember(injury.id) { mutableStateOf<List<InjuryActivity>>(emptyList()) }

    LaunchedEffect(injury.id) {
        activities = load(injury.id)
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                listOf(injury.bodyPart, injury.injuryType)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "Injury" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                buildString {
                    append(shortDay(injury.startDate))
                    append(injury.endDate?.let { " → ${shortDay(it)}" } ?: " → ongoing")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                loading -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                activities.isEmpty() -> Text(
                    "Nothing recorded in the week either side. Either you rested, " +
                        "or the activities have not synced yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val before = activities.filter { (it.daysFromInjury ?: 0) < 0 }
                    val after = activities.filterNot { (it.daysFromInjury ?: 0) < 0 }
                    if (before.isNotEmpty()) {
                        ActivityGroup("BEFORE IT STARTED", before)
                    }
                    if (after.isNotEmpty()) {
                        ActivityGroup("SINCE", after)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityGroup(title: String, activities: List<InjuryActivity>) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
    activities.forEach { activity ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    activity.title?.takeIf { it.isNotBlank() }
                        ?: activity.sport?.replaceFirstChar(Char::uppercase)
                        ?: "Activity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    listOfNotNull(
                        activity.startedAt?.let { com.tracks.app.ui.components.startedLocalDay(it) }?.let { shortDay(it) },
                        activity.distanceMeters?.takeIf { it > 0 }
                            ?.let { distance(it) },
                        activity.durationSeconds?.takeIf { it > 0 }
                            ?.let { "${(it / 60).toInt()} min" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Distance from day zero, which is the column that makes this a
            // timeline rather than a list of activities that happen to be near
            // each other.
            activity.daysFromInjury?.let { days ->
                Text(
                    when {
                        days == 0 -> "that day"
                        days < 0 -> "${-days}d before"
                        else -> "${days}d after"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (days <= 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// ── The timeline ─────────────────────────────────────────────────────────────

/**
 * One lane per injury, over a shared date axis.
 *
 * The web app draws all of them on a single track, which is compact and hides
 * every overlap — the exact thing worth seeing, since two injuries at once is a
 * different situation from two in a row. Lanes cost vertical space and are worth
 * it, and on a phone the space is there because nothing else is competing for
 * the width.
 *
 * The right edge is always today, so an ongoing injury runs to it and a lane
 * that stops short has visibly stopped.
 */
@Composable
private fun InjuryTimeline(
    injuries: List<Injury>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    var zoomed by remember { mutableStateOf(false) }
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val laneBackground = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)

    val today = LocalDate.now()
    val sorted = remember(injuries) { injuries.sortedBy { it.startDate } }
    val earliest = remember(sorted) {
        sorted.mapNotNull { DayAxis.parseDay(it.startDate) }.minOrNull() ?: today
    }
    val fullSpanDays = ChronoUnit.DAYS.between(earliest, today)

    // Six months minimum, so a single fortnight-long injury is a bar and not a
    // hairline against a year of empty axis.
    val from = when {
        zoomed -> today.minusYears(1)
        else -> minOf(earliest, today.minusMonths(6))
    }
    val totalDays = ChronoUnit.DAYS.between(from, today).coerceAtLeast(1L)

    val lanes = remember(sorted, from, totalDays) {
        sorted.mapNotNull { injury ->
            val start = DayAxis.parseDay(injury.startDate) ?: return@mapNotNull null
            val end = injury.endDate?.let { DayAxis.parseDay(it) } ?: today
            val startF = (ChronoUnit.DAYS.between(from, start).toFloat() / totalDays)
                .coerceIn(0f, 1f)
            val endF = (ChronoUnit.DAYS.between(from, end).toFloat() / totalDays)
                .coerceIn(0f, 1f)
            if (endF < 0f || startF > 1f) null else InjuryLane(injury, startF, endF)
        }.takeLast(MAX_LANES)
    }
    if (lanes.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (zoomed) "Past 12 months" else "All time",
                style = MaterialTheme.typography.labelSmall,
                color = axis,
            )
            if (fullSpanDays > 365) {
                NeutralButton(if (zoomed) "Show all" else "Past year", onClick = { zoomed = !zoomed }, small = true)
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(LANE_HEIGHT * lanes.size)
                .pointerInput(lanes) {
                    detectTapGestures { offset ->
                        val index = (offset.y / (size.height / lanes.size))
                            .toInt()
                            .coerceIn(0, lanes.lastIndex)
                        onSelect(lanes[index].injury.id)
                    }
                },
        ) {
            val laneHeight = size.height / lanes.size
            val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

            lanes.forEachIndexed { index, lane ->
                val top = laneHeight * index
                drawRoundRect(
                    color = laneBackground,
                    topLeft = Offset(0f, top + laneHeight * 0.22f),
                    size = Size(size.width, laneHeight * 0.56f),
                    cornerRadius = radius,
                )
                val x0 = size.width * lane.start
                val x1 = size.width * lane.end
                val chosen = lane.injury.id == selected
                drawRoundRect(
                    color = severityColor(lane.injury.severity)
                        .copy(alpha = if (selected == null || chosen) 0.95f else 0.4f),
                    topLeft = Offset(x0, top + laneHeight * 0.22f),
                    // A one-day injury is a real injury and must not be
                    // sub-pixel on a two-year axis.
                    size = Size((x1 - x0).coerceAtLeast(3.dp.toPx()), laneHeight * 0.56f),
                    cornerRadius = radius,
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(monthLabel(from), style = MaterialTheme.typography.labelSmall, color = axis)
            Text(
                "tap a bar",
                style = MaterialTheme.typography.labelSmall,
                color = axis,
            )
            Text("Today", style = MaterialTheme.typography.labelSmall, color = axis)
        }
    }
}

private data class InjuryLane(val injury: Injury, val start: Float, val end: Float)

// ── One injury ───────────────────────────────────────────────────────────────

@Composable
private fun InjuryEntry(
    injury: Injury,
    editing: Boolean,
    highlighted: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onSave: (InjuryDraft) -> Unit,
    onHeal: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Surface(
        // The whole row opens what was happening around it, matching the
        // timeline bar above — the desktop's behaviour, and the reason the two
        // views highlight together rather than being separate lists.
        Modifier
            .fillMaxWidth()
            .then(if (editing) Modifier else Modifier.clickable(onClick = onOpen)),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
    ) {
        Column(Modifier.padding(vertical = 6.dp, horizontal = if (highlighted) 8.dp else 0.dp)) {
            if (editing) {
                Text(
                    "Edit injury",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                InjuryForm(initial = injury, onSave = onSave, onCancel = onEdit)
                return@Column
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(severityColor(injury.severity), CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            listOf(injury.bodyPart, injury.injuryType)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        SeverityBadge(injury.severity)
                    }
                    Text(
                        buildString {
                            append(shortDay(injury.startDate))
                            append(injury.endDate?.let { " → ${shortDay(it)}" } ?: " → ongoing")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    injury.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onHeal?.let { TonalButton("Healed", onClick = it, small = true) }
                TonalButton("Edit", onClick = onEdit, small = true)
                if (confirmingDelete) {
                    // Two taps, because deleting an injury deletes its history
                    // and there is no undo on the server.
                    DangerButton("Delete?", onClick = onDelete, small = true)
                } else {
                    DangerButton("Delete", onClick = { confirmingDelete = true }, small = true)
                }
            }
        }
    }
}

@Composable
private fun SeverityBadge(severity: Int) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md),
        color = severityColor(severity).copy(alpha = 0.18f),
    ) {
        Text(
            "$severity/10",
            style = MaterialTheme.typography.labelSmall,
            color = severityColor(severity),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

// ── The form ─────────────────────────────────────────────────────────────────

/** What the form is editing, before it becomes a create or a patch. */
private data class InjuryDraft(
    val bodyPart: String,
    val injuryType: String,
    val severity: Int,
    val startDate: String,
    val endDate: String?,
    val notes: String?,
) {
    fun toCreate() = InjuryCreate(
        bodyPart = bodyPart,
        injuryType = injuryType,
        severity = severity,
        startDate = startDate,
        endDate = endDate,
        notes = notes,
    )

    fun toUpdate() = InjuryUpdate(
        bodyPart = bodyPart,
        injuryType = injuryType,
        severity = severity,
        startDate = startDate,
        endDate = endDate,
        notes = notes,
    )
}

@Composable
private fun InjuryForm(
    initial: Injury?,
    onSave: (InjuryDraft) -> Unit,
    onCancel: () -> Unit,
) {
    var bodyPart by remember(initial) {
        mutableStateOf(initial?.bodyPart?.takeIf { it.isNotBlank() } ?: BODY_PARTS.first())
    }
    var type by remember(initial) {
        mutableStateOf(initial?.injuryType?.takeIf { it.isNotBlank() } ?: INJURY_TYPES.first())
    }
    var severity by remember(initial) { mutableStateOf(initial?.severity ?: 5) }
    var start by remember(initial) {
        mutableStateOf(initial?.startDate?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString())
    }
    var end by remember(initial) { mutableStateOf(initial?.endDate) }
    var notes by remember(initial) { mutableStateOf(initial?.notes.orEmpty()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerField(
                label = "Body part",
                value = bodyPart,
                options = BODY_PARTS,
                onSelect = { bodyPart = it },
                modifier = Modifier.weight(1f),
            )
            PickerField(
                label = "Type",
                value = type,
                options = INJURY_TYPES,
                onSelect = { type = it },
                modifier = Modifier.weight(1f),
            )
        }

        Column {
            Text(
                "Severity  $severity/10",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A slider rather than ten chips: ten chips do not fit across a
            // phone, and severity is a continuum the user is estimating anyway.
            Slider(
                value = severity.toFloat(),
                onValueChange = { severity = it.roundToInt().coerceIn(1, 10) },
                valueRange = 1f..10f,
                steps = 8,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(
                label = "Started",
                value = start,
                onPick = { start = it },
                modifier = Modifier.weight(1f),
            )
            DateField(
                label = "Healed",
                value = end,
                onPick = { end = it },
                onClear = { end = null },
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
        )

        ButtonRow {
            PrimaryButton("Save", onClick = {
                    onSave(
                        InjuryDraft(
                            bodyPart = bodyPart,
                            injuryType = type,
                            severity = severity,
                            startDate = start,
                            endDate = end,
                            notes = notes.trim().takeIf { it.isNotBlank() },
                        )
                    )
                })
            NeutralButton("Cancel", onClick = onCancel)
        }
    }
}

/**
 * A read-only field that opens a menu.
 *
 * Deliberately not a text field with suggestions. The whole reason these are
 * lists is that free text produced "IT band" and "ITB" as different injuries,
 * and an editable field invites exactly that back in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerField(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

/** A date, picked from a calendar rather than typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(
    label: String,
    value: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value?.let(::shortDay) ?: "—",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            TonalButton("Set", onClick = { open = true }, small = true)
        },
        modifier = modifier,
    )

    if (open) {
        val initialMillis = value?.let(DayAxis::parseDay)
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                PrimaryButton("OK", onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // UTC, because that is the zone the picker reports in;
                        // reading it locally shifts the date by a day either
                        // side of midnight depending on the offset.
                        onPick(
                            java.time.Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        )
                    }
                    open = false
                })
            },
            dismissButton = {
                Row {
                    onClear?.let {
                        NeutralButton("Clear", onClick = { it(); open = false })
                    }
                    NeutralButton("Cancel", onClick = { open = false })
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

// ── Vocabulary and colour ────────────────────────────────────────────────────

/**
 * The same lists the web app offers, in the same order.
 *
 * Shared vocabulary is the entire point: these strings are stored verbatim, so
 * two clients offering different words fill one column with two taxonomies and
 * nothing can count injuries by body part afterwards.
 */
internal val BODY_PARTS = listOf(
    "Knee", "Hip", "Ankle", "Shin", "Calf", "Hamstring", "Quadricep",
    "IT Band", "Plantar Fascia", "Achilles", "Back", "Shoulder", "Foot", "Other",
)

internal val INJURY_TYPES = listOf(
    "Strain", "Sprain", "Tendinopathy", "Stress fracture", "Bursitis",
    "DOMS", "Contusion", "Overuse", "Other",
)

/**
 * Severity as colour, over the server's 1–10 scale.
 *
 * Not from `spec/` — injury severity has no shared table, and inventing one
 * here would be a constant the server never agreed to. The ramp matches the
 * web app's severity badges band for band.
 */
internal fun severityColor(severity: Int): Color = when (severity.coerceIn(1, 10)) {
    1, 2 -> Color(0xFF4ADE80)
    3, 4 -> Color(0xFFFACC15)
    5, 6 -> Color(0xFFF97316)
    7, 8 -> Color(0xFFEF4444)
    else -> Color(0xFFB91C1C)
}

private fun shortDay(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DAY_FORMAT) }.getOrDefault(iso)

private fun monthLabel(date: LocalDate): String = date.format(MONTH_FORMAT)

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

private val LANE_HEIGHT = 18.dp

/** Enough to see a pattern; beyond this the widget is taller than the list. */
private const val MAX_LANES = 10
