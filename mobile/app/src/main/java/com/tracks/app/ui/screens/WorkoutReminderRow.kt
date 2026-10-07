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
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tracks.app.AppContainer
import com.tracks.app.nudge.WorkoutReminders
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.profile.ToggleRow
import com.tracks.app.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * The daily workout reminder, as one switch: one notification a day, timed
 * half an hour before the user usually starts a workout (learned from recent
 * workouts, weekdays and weekends apart), quiet on days already trained, on
 * planned rest days and overnight.
 *
 * The fallback time — used until a habit shows — is deliberately not offered:
 * it is a stopgap the user should never have to think about, and a control
 * for it read as the reminder's main setting. It keeps its default
 * (WorkoutReminders.fallback).
 *
 * This phone's own setting, not a synced one: which device buzzes is a
 * property of the device, and a second phone should not start nudging because
 * the first was set to.
 */
@Composable
internal fun WorkoutReminderRow(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(WorkoutReminders.enabled(context)) }
    var allowed by remember { mutableStateOf(WorkoutReminders.canNotify(context)) }

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

    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.s2)) {
        ToggleRow(
            "Remind me to train",
            "Once a day, shortly before you usually start a workout.",
            enabled,
        ) { on ->
            enabled = on
            scope.launch { WorkoutReminders.setEnabled(context, container.sources, on) }
            if (on && !allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (enabled && !allowed) {
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
    }
}
