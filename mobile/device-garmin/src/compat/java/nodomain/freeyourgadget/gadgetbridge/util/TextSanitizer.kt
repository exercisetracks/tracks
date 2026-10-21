// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util

import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport

/**
 * Shim for Gadgetbridge's sanitizeText (0.94+), which the vendored
 * DeviceTextAdaptable calls. Upstream filters per device and turns emoji into
 * ASCII for watches that cannot draw them, with an emoji library Tracks does
 * not carry. Tracks only talks to Garmins, which draw Unicode, so text goes
 * through as it is.
 */
@Suppress("UNUSED_PARAMETER")
fun sanitizeText(deviceSupport: DeviceSupport, text: String?): String? = text
