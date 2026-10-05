// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

/**
 * A settings file that switches the watch's own Wi-Fi uploading off.
 *
 * A Garmin set up through Garmin Connect keeps that account and its Wi-Fi
 * networks, and with Auto Upload on it sends every activity to Garmin the
 * moment it is saved — no phone involved, and invisible to any phone app
 * (found 2026-09-30; docs/garmin-ble-protocol.md). The only lever a phone has
 * is the watch's `device_settings`, delivered as a FIT file of type
 * `settings`, which the watch applies on import.
 *
 * Mirrors Gadgetbridge's experimental "Send Connection Settings"
 * (`GarminSettingsCustomizer.sendConnection`) field for field: a `file_id`
 * of type settings from manufacturer Garmin, product 65534, serial 1, then a
 * `device_settings` record carrying only the fields being changed —
 * `wifi_auto_upload_enabled` (38) and `wifi_enabled` (98), both enums. A
 * field left out is left alone by the watch, which is why both are optional.
 * Header and byte order are Gadgetbridge's `FitFile` defaults (protocol 1.0,
 * profile 21117, little-endian), the shape that implementation sends.
 */
object SettingsFit {

    private const val MESSAGE_FILE_ID = 0
    private const val MESSAGE_DEVICE_SETTINGS = 2

    private const val FILE_TYPE_SETTINGS = 2
    private const val MANUFACTURER_GARMIN = 1
    private const val PRODUCT_CONNECT = 65534
    private const val SERIAL = 1L

    private const val FIELD_WIFI_AUTO_UPLOAD_ENABLED = 38
    private const val FIELD_WIFI_ENABLED = 98

    private const val PROTOCOL_VERSION = 0x10
    private const val PROFILE_VERSION = 21117

    private const val FIT_EPOCH_OFFSET = 631_065_600L

    /**
     * The file, or null when it would change nothing.
     *
     * [nowMillis] is the file's creation time, a parameter so the output is
     * reproducible in tests.
     */
    fun encode(wifiAutoUpload: Boolean?, wifi: Boolean? = null, nowMillis: Long): ByteArray? {
        val settings = listOfNotNull(
            wifiAutoUpload?.let { fitEnum(FIELD_WIFI_AUTO_UPLOAD_ENABLED, if (it) 1 else 0) },
            wifi?.let { fitEnum(FIELD_WIFI_ENABLED, if (it) 1 else 0) },
        )
        if (settings.isEmpty()) return null

        val writer = FitWriter(PROTOCOL_VERSION, PROFILE_VERSION, bigEndian = false)
        writer.write(
            MESSAGE_FILE_ID,
            listOf(
                fitEnum(0, FILE_TYPE_SETTINGS),
                fitUint16(1, MANUFACTURER_GARMIN),
                fitUint16(2, PRODUCT_CONNECT),
                fitUint32z(3, SERIAL),
                fitUint32(4, nowMillis / 1000 - FIT_EPOCH_OFFSET),
                fitUint16(5, 0),
            ),
        )
        writer.write(MESSAGE_DEVICE_SETTINGS, settings)
        return writer.finish()
    }
}
