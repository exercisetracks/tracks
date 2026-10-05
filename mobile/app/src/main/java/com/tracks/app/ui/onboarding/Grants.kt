// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.onboarding

import android.content.Context
import com.tracks.app.device.BluetoothPermissions
import com.tracks.app.feeds.FeedPermissions

/**
 * Every grant this app needs, as data.
 *
 * ## Why a list rather than a sequence of calls
 *
 * The alternative — a permission step that hard-codes four prompts in order —
 * has failed here before. Notifications, calendar, and weather all shipped
 * fully wired and completely silent, because nothing ever asked for them and
 * nothing showed that they were unasked. A wired-but-ungranted feed is
 * indistinguishable from a broken one: nothing arrives, nothing errors.
 *
 * Making the set data means the failure cannot recur quietly. A new capability
 * is one entry here, and it appears in onboarding and reports its own state
 * without either screen being edited — the same "registration, not an edit"
 * shape the sport-layout and map-layer registries use.
 *
 * ## Required vs optional
 *
 * [required] does not mean the OS refuses to run without it — it means the app
 * cannot do the thing the user installed it for. Bluetooth is required because
 * without it there is no watch sync, which is the entire product. Everything
 * else degrades to a missing feature, so onboarding offers those and moves on
 * rather than blocking. Nothing here is ever forced: a declined grant leaves a
 * usable app and a row in Settings that says what is off and how to turn it on.
 */
data class Grant(
    val key: String,
    val title: String,
    /** What it is for, in the user's terms — never the permission's name. */
    val rationale: String,
    val required: Boolean,
    /**
     * Only worth asking for with a watch. Onboarding asks whether there is one
     * before it asks for anything, so a phone-only user is never asked for
     * Bluetooth or notification access they have no use for.
     */
    val deviceOnly: Boolean,
    /** Whether the grant is currently held. Re-read on every resume. */
    val isGranted: (Context) -> Boolean,
    /**
     * Ask for it. Suspends until the user answers a dialog, or returns
     * immediately for the ones that hand off to a system settings screen —
     * see [GrantRequester] for why those cannot report a result.
     */
    val request: suspend (GrantRequester) -> Unit,
)

/**
 * The Activity-scoped machinery a grant needs to actually ask.
 *
 * Bundled into one object passed to [Grant.request] so the registry stays
 * declarative: entries name what they need, and the screen supplies it.
 */
class GrantRequester(
    val context: Context,
    val bluetooth: BluetoothPermissions,
    val feeds: FeedPermissions,
)

/**
 * The grants, in the order onboarding asks for them.
 *
 * Ordering is not arbitrary: the ones that open a *dialog* come first, because
 * they are answered in place and keep the user inside the flow. The one that
 * hands off to another screen comes last, since it bounces the user out to
 * Android Settings and back.
 */
val GRANTS: List<Grant> = listOf(
    Grant(
        key = "bluetooth",
        title = "Bluetooth",
        rationale = "Required to talk to your watch. Tracks connects only to the " +
            "one watch you pair with — it never scans for other devices.",
        required = true,
        deviceOnly = true,
        isGranted = { BluetoothPermissions.granted(it) },
        request = { it.bluetooth.ensure() },
    ),
    Grant(
        key = "location",
        title = "Location",
        // Asked here so the first run recorded on the phone starts when the
        // button is pressed, not after a dialog. Foreground only — see
        // FeedPermissions.ensureLocation for why a run needs no more.
        rationale = "Records the route of runs you track with this phone, and " +
            "shows where you are on the map. Only while a run or the map is open.",
        required = false,
        deviceOnly = false,
        isGranted = { FeedPermissions.locationGranted(it) },
        request = { it.feeds.ensureLocation() },
    ),
    Grant(
        key = "post_notifications",
        title = "Notifications",
        rationale = "Shows a run, a sync or a medication reminder while Tracks is " +
            "in the background. Android will not run these without one.",
        required = false,
        deviceOnly = false,
        isGranted = { FeedPermissions.postNotificationsGranted(it) },
        request = { it.feeds.ensurePostNotifications() },
    ),
    Grant(
        key = "calendar",
        title = "Calendar",
        rationale = "Puts your agenda on the watch. Only event title, time, and " +
            "location ever leave the phone.",
        required = false,
        deviceOnly = true,
        isGranted = { FeedPermissions.calendarGranted(it) },
        request = { it.feeds.ensureCalendar() },
    ),
    Grant(
        key = "notification_access",
        title = "Notification relay",
        rationale = "Forwards phone notifications to your watch. Android grants " +
            "this on its own settings screen — find Tracks in the list it opens.",
        required = false,
        deviceOnly = true,
        isGranted = { FeedPermissions.notificationAccessGranted(it) },
        // No result to await: this leaves the app entirely, and the user may
        // return without having granted anything. State is re-read on resume.
        request = { it.context.startActivity(FeedPermissions.notificationAccessIntent()) },
    ),
)

/** What onboarding asks this person for: the device grants only if they have a watch. */
fun grantsFor(hasDevice: Boolean): List<Grant> = GRANTS.filter { hasDevice || !it.deviceOnly }
