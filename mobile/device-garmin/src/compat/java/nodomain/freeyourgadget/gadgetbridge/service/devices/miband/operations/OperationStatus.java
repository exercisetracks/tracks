// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.service.devices.miband.operations;

/**
 * Shim for the operation lifecycle enum.
 *
 * <p>Lives under a Mi Band package upstream purely for historical reasons — it
 * is vendor-neutral, and the BLE transaction layer every device shares refers
 * to it. Vendoring the whole Mi Band package for one enum would be worse.
 */
public enum OperationStatus {
    INITIAL,
    STARTED,
    RUNNING,
    FINISHED,
}
