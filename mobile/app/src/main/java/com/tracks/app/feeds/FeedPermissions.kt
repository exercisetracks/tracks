// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The grants the three phone feeds need, and the fact that nothing was ever
 * asking for them.
 *
 * Notifications, calendar, and weather were all wired end to end — a listener
 * service, a calendar reader the watch pulls from, a broadcast receiver — and
 * all three did nothing on a real phone. Not because the plumbing was wrong,
 * but because:
 *
 *   - notification access was never granted, and nothing in the app offered to
 *     take the user to the one screen where it can be;
 *   - `READ_CALENDAR` was declared in the manifest and never requested at
 *     runtime, so it sat at `granted=false` forever;
 *   - weather depends on another app being told to broadcast to us, which the
 *     user has no way to know from inside Tracks.
 *
 * Each fails silently and looks identical from the outside: the feature is
 * simply absent. So this exists to make the state legible and actionable, and
 * the Settings screen reports each one rather than leaving the user to guess.
 *
 * Notification access is deliberately *not* a runtime permission request —
 * `BIND_NOTIFICATION_LISTENER_SERVICE` cannot be granted by a dialog. It is a
 * system settings screen the user has to visit, which is why [openSettings]
 * hands them there instead of pretending a prompt exists.
 */
class FeedPermissions(activity: ComponentActivity) {

    private val context: Context = activity.applicationContext

    private var pending: CancellableContinuation<Boolean>? = null

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val continuation = pending
            pending = null
            continuation?.resume(granted.values.all { it })
        }

    // ── Calendar ─────────────────────────────────────────────────────────────

    val calendarGranted: Boolean
        get() = calendarGranted(context)

    suspend fun ensureCalendar(): Boolean {
        if (calendarGranted) return true
        return request(arrayOf(Manifest.permission.READ_CALENDAR))
    }

    // ── Notifications the app itself posts ───────────────────────────────────

    /**
     * Distinct from notification *access*: this is permission to post the
     * foreground-service notification a background watch sync must show, and
     * on API 33+ its absence means that sync runs invisibly or not at all.
     */
    val postNotificationsGranted: Boolean
        get() = postNotificationsGranted(context)

    suspend fun ensurePostNotifications(): Boolean {
        if (postNotificationsGranted) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return request(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    // ── Location ─────────────────────────────────────────────────────────────

    /**
     * Precise location, asked during onboarding so the first run recorded on
     * the phone does not stop at a permission dialog. Here rather than in its
     * own class because this launcher already exists and asking is all it
     * does; the run screen still asks too, for anyone who declined here.
     *
     * Foreground only. A run records with the screen off through RunService, a
     * location-typed foreground service started from the app while it is on
     * screen, and Android delivers fixes to that without
     * ACCESS_BACKGROUND_LOCATION — which is why the manifest does not declare
     * it and nothing here asks for it (see the note there).
     */
    suspend fun ensureLocation(): Boolean {
        if (locationGranted(context)) return true
        return request(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private suspend fun request(permissions: Array<String>): Boolean =
        suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { pending = null }
            launcher.launch(permissions)
        }

    companion object {
        // Context-only readers. Constructing this class registers an
        // activity-result launcher, which is only legal before the Activity
        // starts — so reading a grant's state and asking for it cannot share a
        // lifetime. See BluetoothPermissions.granted for the same split.

        /**
         * Precise, specifically: the run recorder refuses coarse fixes (a run
         * drawn from cell towers is drawn wrong), so a coarse-only grant is not
         * the grant a run needs.
         */
        fun locationGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        fun calendarGranted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR,
            ) == PackageManager.PERMISSION_GRANTED

        fun postNotificationsGranted(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        /** Whether the user has granted notification *access* (the relay). */
        fun notificationAccessGranted(context: Context): Boolean =
            TracksNotificationListener.isEnabled(context)

        /**
         * The system screen where notification access is granted.
         *
         * `ACTION_NOTIFICATION_LISTENER_SETTINGS` shows the list of apps; there
         * is no reliable way to deep-link to our own row across OEM builds, so
         * the Settings screen tells the user what to look for.
         */
        fun notificationAccessIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        const val BREEZY_PACKAGE = "org.breezyweather"
    }
}
