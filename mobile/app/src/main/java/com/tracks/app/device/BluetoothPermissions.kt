// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Asks for the Bluetooth permissions, at the moment they are needed.
 *
 * ## Why this exists as its own thing
 *
 * A companion association is *not* a Bluetooth permission. It tells the system
 * which device this app may talk to; it does not grant the right to open a GATT
 * connection. On API 31+ that still needs `BLUETOOTH_CONNECT` at runtime, and
 * without it every call fails with a `SecurityException` from somewhere deep in
 * the BLE stack — which reads like a driver bug rather than a missing grant.
 *
 * ## What is asked for, and when
 *
 * Only at the point the user taps to pair. A permission dialog on first launch,
 * before the user has expressed any interest in a watch, is the pattern that
 * teaches people to dismiss these without reading.
 *
 * `POST_NOTIFICATIONS` is deliberately not bundled in here. It is unrelated to
 * pairing, and asking for two things at once when the user asked for one is how
 * a prompt gets dismissed wholesale. It belongs with the foreground service that
 * will actually post something.
 */
class BluetoothPermissions(activity: ComponentActivity) {

    private val context: Context = activity.applicationContext

    private var pending: CancellableContinuation<Boolean>? = null

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val continuation = pending
            pending = null
            // All of them, not any: connecting without CONNECT fails, and the
            // companion dialog without SCAN shows an empty list on some builds.
            continuation?.resume(granted.values.all { it })
        }

    /**
     * Whether every required permission is held.
     *
     * On API 30 and below this is always true: the pre-31 spellings
     * (`BLUETOOTH`, `BLUETOOTH_ADMIN`) are install-time permissions with no
     * runtime component, so there is nothing to ask for.
     */
    val granted: Boolean
        get() = granted(context)

    /** Returns true if the permissions are held, prompting once if they are not. */
    suspend fun ensure(): Boolean {
        if (granted) return true
        val missing = required().toTypedArray()
        if (missing.isEmpty()) return true

        return suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { pending = null }
            launcher.launch(missing)
        }
    }

    private fun required(): List<String> = requiredFor()

    companion object {
        /**
         * Whether the permissions are held, without an Activity.
         *
         * Constructing this class registers an activity-result launcher, which
         * `registerForActivityResult` refuses to do once the Activity has
         * started — so anything that merely wants to *read* the state (the
         * grant registry, a status row) cannot go through an instance. Reading
         * and asking have genuinely different lifetimes here.
         */
        fun granted(context: Context): Boolean = requiredFor().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        private fun requiredFor(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                emptyList()
            }
    }
}
