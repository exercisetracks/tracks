// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.tracks.device.CompanionPairing
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Shows Android's companion-device dialog and reports what the user picked.
 *
 * This is the Activity half of [CompanionPairing]: the device layer builds the
 * request, and only something holding an Activity can put a system dialog on
 * screen. Keeping it here is what lets `GarminIntegration` be constructed from a
 * WorkManager job that has no UI at all.
 *
 * Must be constructed during `onCreate` — `registerForActivityResult` refuses
 * once the Activity has started, and it does so at exactly the moment a user
 * would first try to pair.
 */
class CompanionDevicePicker(activity: ComponentActivity) : CompanionPairing.DevicePicker {

    private val context: Context = activity.applicationContext

    private val manager: CompanionDeviceManager? =
        activity.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager

    private var pending: CancellableContinuation<CompanionPairing.ChosenDevice?>? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val continuation = pending
            pending = null
            continuation?.resume(chosenFrom(result.data))
        }

    override suspend fun pick(request: AssociationRequest): CompanionPairing.ChosenDevice? {
        val manager = manager ?: return null

        return suspendCancellableCoroutine { continuation ->
            pending = continuation
            continuation.invokeOnCancellation { pending = null }

            manager.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onDeviceFound(sender: IntentSender) {
                    launcher.launch(IntentSenderRequest.Builder(sender).build())
                }

                override fun onFailure(error: CharSequence?) {
                    // Includes the ordinary "nothing matched" case, so this is
                    // not necessarily an error worth showing the user — the UI
                    // decides what an empty result means.
                    Log.i(TAG, "Companion association did not complete: $error")
                    pending = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }, null)
        }
    }

    /**
     * Pull the chosen device out of the result, across three shapes of the same
     * answer.
     *
     * API 33 introduced `EXTRA_ASSOCIATION`, and before that the extra was
     * either a `BluetoothDevice` or a `ScanResult` depending on which filter
     * matched. All three are handled because a wrong guess here does not fail
     * loudly — it silently returns null and the user is told pairing was
     * cancelled when it actually succeeded.
     */
    @Suppress("DEPRECATION")
    private fun chosenFrom(data: Intent?): CompanionPairing.ChosenDevice? {
        if (data == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val association: AssociationInfo? =
                data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            val address = association?.deviceMacAddress?.toString()
            if (address != null) {
                return CompanionPairing.ChosenDevice(
                    address = address.uppercase(),
                    name = association.displayName?.toString() ?: address,
                )
            }
        }

        return when (val device = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> CompanionPairing.ChosenDevice(
                address = device.address.uppercase(),
                name = safeName(device) ?: device.address,
            )

            is ScanResult -> CompanionPairing.ChosenDevice(
                address = device.device.address.uppercase(),
                name = device.scanRecord?.deviceName ?: safeName(device.device) ?: device.device.address,
            )

            else -> null
        }
    }

    /** Reading a device name needs BLUETOOTH_CONNECT, which may not be granted yet. */
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    private companion object {
        const val TAG = "TracksPicker"
    }
}
