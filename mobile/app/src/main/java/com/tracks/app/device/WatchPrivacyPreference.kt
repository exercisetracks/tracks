// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import android.content.Context

/**
 * Whether Tracks keeps the watch's own Wi-Fi uploads to Garmin switched off.
 *
 * A watch once set up with Garmin Connect uploads every activity to Garmin
 * over Wi-Fi the moment it is saved, phone or no phone (see
 * [com.tracks.core.fit.SettingsFit]). Tracks can switch that off over
 * Bluetooth, and does so when this is on — at onboarding, and again whenever
 * a sync finds an activity the watch had already flagged synced, which is the
 * evidence that something turned it back on.
 *
 * Defaulted **on**. Tracks exists so that this data stays with its owner; a
 * copy on Garmin's servers is the outcome a person has to choose, not the one
 * they have to discover. Turning it off asks first (see WatchUploadsStep).
 */
object WatchPrivacyPreference {

    private const val PREFS = "tracks_watch"
    private const val KEY_BLOCK_WIFI_UPLOADS = "block_wifi_uploads"

    fun blocksWifiUploads(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BLOCK_WIFI_UPLOADS, true)

    fun setBlocksWifiUploads(context: Context, block: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BLOCK_WIFI_UPLOADS, block).apply()
    }
}
