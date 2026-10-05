// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.regex.Pattern

/**
 * Pairing through Android's companion-device flow rather than a BLE scan.
 *
 * ## Why not just scan
 *
 * Scanning for BLE advertisements is the single most expensive thing this app
 * could do to a battery: it wakes the radio and the CPU on a duty cycle, for as
 * long as it runs, and it has to run again every time the watch wanders out of
 * range. A companion association is the opposite trade — the user confirms the
 * device once in a system dialog, the association survives reboots, and the OS
 * takes on the job of noticing when the watch comes back. For a tool that is
 * meant to last a week in the field, that is not an optimisation, it is the
 * difference between usable and not.
 *
 * It is also better for privacy in a way that is easy to miss: a companion
 * association means the app never sees the devices the user did *not* pick. A
 * scan enumerates every BLE device in range, which on a train is a small crowd
 * survey.
 *
 * ## What is here and what is not
 *
 * Reading existing associations needs nothing but a context, so it lives here.
 * *Creating* one requires launching a system dialog from an Activity, which this
 * layer has no business holding — so that is [DevicePicker], implemented by the
 * UI layer and handed in.
 */
class CompanionPairing(private val context: Context) {

    /** What the system dialog hands back: an address and whatever name it showed. */
    data class ChosenDevice(val address: String, val name: String)

    /**
     * Shows the system's companion-device dialog and reports what the user
     * chose.
     *
     * Implemented by whoever owns an Activity; null means the user dismissed the
     * dialog, which is an ordinary outcome and not an error.
     *
     * It returns a [ChosenDevice] rather than a [PairedDevice] because the
     * picker has no idea which vendor it just found — that is the caller's
     * knowledge, and making the UI layer guess at it would put vendor
     * identification in the one place that must stay vendor-neutral.
     */
    fun interface DevicePicker {
        suspend fun pick(request: AssociationRequest): ChosenDevice?
    }

    private val manager: CompanionDeviceManager? =
        context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Whether this device can do companion associations at all. */
    val isSupported: Boolean get() = manager != null && adapter != null

    /**
     * Devices the user has already associated with Tracks.
     *
     * Returns empty rather than throwing when the permission is missing or the
     * platform disagrees about which API exists: a failure to enumerate should
     * put the user in front of the pairing dialog, not in front of a crash.
     */
    @SuppressLint("MissingPermission")
    fun associated(vendorId: String): List<PairedDevice> {
        val manager = manager ?: return emptyList()
        return try {
            addresses(manager).mapNotNull { address ->
                val name = remoteName(address) ?: return@mapNotNull null
                PairedDevice(address = address, name = name, vendorId = vendorId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read companion associations", e)
            emptyList()
        }
    }

    /**
     * The associated addresses, across two generations of the same API.
     *
     * `getAssociations()` was deprecated in API 33 in favour of
     * `getMyAssociations()`, which returns richer objects. Both are kept because
     * minSdk is 26 and the deprecated one is the only option below 33 — this is
     * a version fork, not dead code.
     */
    @Suppress("DEPRECATION")
    private fun addresses(manager: CompanionDeviceManager): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
        } else {
            manager.associations.map { it.uppercase() }
        }

    @SuppressLint("MissingPermission")
    private fun remoteName(address: String): String? = try {
        // Falls back to the address so a bonded-but-unnamed watch is still
        // selectable; a null name here would silently drop a real association.
        adapter?.getRemoteDevice(address)?.name ?: address
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve a name for an associated device", e)
        null
    }

    /**
     * Watches already bonded to this phone that match [namePattern].
     *
     * ## Why this path exists
     *
     * A companion scan only finds devices that are *advertising*, and a watch
     * that is already bonded and managed by another app is not — it is sitting
     * in an encrypted LE connection to whatever paired it. So a user coming from
     * Garmin Connect or Gadgetbridge, which is most users, gets an empty picker
     * and no explanation.
     *
     * Their watch is right there in the bond list. Offering it directly turns
     * "nothing found, good luck" into "is this your fenix?".
     *
     * ## What is given up
     *
     * A bond is not an association, and the association is what buys the
     * battery win: presence callbacks, so the OS wakes the app when the watch
     * comes back rather than the app scanning for it. A bonded-only device has
     * to be connected on our own schedule. That is a real cost, which is why the
     * association is still preferred and this is the fallback — but a fallback
     * that works beats a preference that finds nothing.
     */
    @SuppressLint("MissingPermission")
    fun bonded(vendorId: String, namePattern: Pattern): List<PairedDevice> = try {
        adapter?.bondedDevices.orEmpty()
            .mapNotNull { device ->
                val name = device.name ?: return@mapNotNull null
                if (!namePattern.matcher(name).find()) return@mapNotNull null
                PairedDevice(
                    address = device.address.uppercase(),
                    name = name,
                    vendorId = vendorId,
                )
            }
    } catch (e: SecurityException) {
        // BLUETOOTH_CONNECT not granted yet. The caller prompts and retries.
        Log.i(TAG, "Cannot read bonded devices without BLUETOOTH_CONNECT")
        emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read bonded devices", e)
        emptyList()
    }

    /**
     * Ask the user to associate a device.
     *
     * [namePattern] narrows the dialog to plausible candidates — a list
     * containing every BLE thing in the room is a worse experience than one
     * containing the user's watch.
     */
    suspend fun associate(
        picker: DevicePicker,
        namePattern: Pattern,
        vendorId: String,
    ): PairedDevice? {
        check(isSupported) { "This device has no companion-device manager" }
        val chosen = picker.pick(request(namePattern)) ?: return null
        return PairedDevice(
            address = chosen.address.uppercase(),
            name = chosen.name,
            vendorId = vendorId,
        )
    }

    /**
     * Filter by advertised name rather than by service UUID.
     *
     * A UUID filter would be more precise, but `BluetoothLeDeviceFilter` accepts
     * exactly one scan filter and Garmin watches advertise one of two GFDI
     * service UUIDs depending on firmware generation — so a UUID filter would
     * quietly hide half the supported hardware. The name pattern matches both,
     * and the user confirms the choice in the dialog either way.
     *
     * `setSingleDevice(false)` so the OS always shows the list.
     * `true` skips it when exactly one device matches, which sounds friendlier
     * but hides *which* watch is about to be paired — worth one extra tap.
     */
    fun request(namePattern: Pattern): AssociationRequest =
        AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(namePattern)
                    .build()
            )
            .setSingleDevice(false)
            .build()

    private companion object {
        const val TAG = "TracksPairing"
    }
}
