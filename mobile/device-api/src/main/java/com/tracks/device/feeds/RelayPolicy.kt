// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.feeds

import android.app.NotificationManager

/**
 * What the user has agreed may reach the watch.
 *
 * Separate from [NotificationCapture]'s own filtering, which drops what a watch
 * *cannot* usefully show — ongoing chrome, group summaries, notifications an app
 * flagged local-only. This is the other half: what the user has decided they do
 * not *want* on their wrist, which is a preference and so has to be carried in
 * rather than hard-coded.
 *
 * Lives in `:device-api` beside the capture it modifies, so that all of the
 * filtering is one decision in one place, and is a plain value so it can be
 * tested without a phone, a watch, or a preferences file.
 */
data class RelayPolicy(
    /** Packages the user has blacklisted. Nothing from these leaves the phone. */
    val blockedPackages: Set<String> = emptySet(),
    /**
     * Whether notifications Android itself delivers silently should be relayed
     * anyway.
     *
     * Defaults to false, matching the settings toggle. Long-pressing a
     * notification and choosing "Silent" is how someone tells Android they do
     * not want to be interrupted by an app — and a watch strapped to a wrist is
     * the most interrupting screen they own, so honouring that instruction
     * matters more there than anywhere else. It also quietly removes most of
     * the noise a relay produces: background sync, "app is running", weather
     * updates and the rest all post on low-importance channels.
     */
    val relaySilent: Boolean = false,
) {

    /**
     * @param importance the effective importance from the notification's
     *   ranking, or null when it could not be read. Null means "no opinion" and
     *   relays: a notification we cannot classify is one the user asked for.
     */
    fun allows(packageName: String, importance: Int?): Boolean {
        if (packageName in blockedPackages) return false
        if (relaySilent || importance == null) return true
        return importance >= NotificationManager.IMPORTANCE_DEFAULT
    }
}
