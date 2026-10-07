// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.AppContainer
import com.tracks.app.nudge.WorkoutReminders
import com.tracks.app.ui.components.MetricInfo
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.health.TimePickerDialog
import com.tracks.app.ui.profile.LabelWithTip
import com.tracks.app.ui.profile.ToggleRow
import com.tracks.app.ui.theme.Tokens
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The daily workout reminder: on or off, the fallback time, and when the next
 * one is due and why — so a learned time is never a mystery.
 *
 * This phone's own setting, not a synced one: which device buzzes is a
 * property of the device, and a second phone should not start nudging because
 * the first was set to.
 */
@Composable
internal fun WorkoutReminderCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(WorkoutReminders.enabled(context)) }
    var fallback by remember { mutableStateOf(WorkoutReminders.fallback(context)) }
    var allowed by remember { mutableStateOf(WorkoutReminders.canNotify(context)) }
    var picking by remember { mutableStateOf(false) }
    val next by WorkoutReminders.next.collectAsStateWithLifecycle()

    // Back from the system's notification settings, the answer may have changed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowed = WorkoutReminders.canNotify(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = WorkoutReminders.canNotify(context)
    }

    SettingsCard(
        "Workout reminder",
        MetricInfo(
            "Workout reminder",
            "One notification a day, timed half an hour before you usually start a workout. Tracks learns " +
                "that time from your recent workouts, weekdays and weekends separately, and uses your " +
                "fallback time until a habit shows.",
            "Quiet on days you have already trained, on rest days in your plan, and between 9 pm and 6 am.",
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s3)) {
            ToggleRow("Remind me to train", null, enabled) { on ->
                enabled = on
                scope.launch { WorkoutReminders.setEnabled(context, container.sources, on) }
                if (on && !allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (!enabled) return@Column
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = Tokens.Space.s3)) {
                    LabelWithTip(
                        "Fallback time",
                        "Used until Tracks has seen enough workouts to know when you usually start.",
                        MaterialTheme.typography.bodyLarge,
                    )
                }
                NeutralButton(fallback, onClick = { picking = true }, small = true)
            }
            if (!allowed) {
                Text(
                    "Notifications are off for Tracks, so no reminder can appear.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TonalButton("Allow notifications", small = true, onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                })
            }
            next?.let { n ->
                val day = if (n.at.toLocalDate() == LocalDate.now()) "today" else "tomorrow"
                val time = n.at.format(CLOCK)
                Text(
                    if (n.learned && n.habit != null) {
                        "Next: $day at $time, before you usually start (${n.habit.format(CLOCK)})."
                    } else {
                        "Next: $day at $time, your fallback until a habit shows in your workouts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (picking) {
        TimePickerDialog(
            initial = fallback,
            title = "Fallback time",
            onPick = { picked ->
                fallback = picked
                picking = false
                scope.launch { WorkoutReminders.setFallback(context, container.sources, picked) }
            },
            onDismiss = { picking = false },
        )
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("H:mm")
