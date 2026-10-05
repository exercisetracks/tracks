// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device

/**
 * The two things a watch wants from a phone that have nothing to do with
 * fitness: what just buzzed, and what the weather is doing.
 *
 * They are here rather than in `core` because they only exist to be sent over
 * a wire to a device — `core` is shared with a future iOS app and has no
 * business knowing about Android notifications.
 */

/**
 * A notification, normalised away from Android's `StatusBarNotification`.
 *
 * Deliberately a small subset. Garmin's protocol carries a title, a body, a
 * category, an id and a list of labelled actions, so capturing more would be
 * collecting data the watch cannot show — and this is exactly the sort of data
 * collection that deserves to stop at what is actually used.
 */
data class DeviceNotification(
    /** Stable per notification, so a dismissal can withdraw the right one. */
    val id: Int,
    /** App the notification came from, e.g. "Signal". */
    val appName: String,
    val packageName: String,
    val title: String?,
    val body: String?,
    val category: NotificationCategory,
    val timestamp: Long,
    /** Present for calls and messages where the sender is known. */
    val sender: String? = null,
    /**
     * What the user can do to this notification from the wrist, beyond
     * clearing it — the posting app's own reply and buttons, in the order the
     * app gave them. A [NotificationResponse.Action] names one by its index
     * here, so the order is the contract.
     */
    val actions: List<NotificationAction> = emptyList(),
)

/**
 * One of the posting app's own notification buttons, offered on the watch.
 *
 * Only a label and a kind. The thing that performs it — an Android
 * `PendingIntent`, and for a reply its `RemoteInput` — stays on the phone with
 * the capture side, and the watch's choice comes back as an index into
 * [DeviceNotification.actions]. Nothing the watch sends can make the phone do
 * anything the notification did not already offer.
 */
data class NotificationAction(val label: String, val kind: Kind) {
    enum class Kind {
        /** Takes text: the app's own reply, answered from the watch's quick replies. */
        REPLY,

        /** A plain button — "Mark as read", "Archive", "Like". */
        SIMPLE,
    }
}

/**
 * The user acting on a notification from the watch.
 *
 * Device-to-phone, like [MusicCommand]. Each names the notification by the id
 * it was sent with, so a response to something the phone has since forgotten
 * finds nothing to act on and does nothing.
 */
sealed interface NotificationResponse {
    val notificationId: Int

    /** Cleared on the watch, so clear it on the phone too. */
    data class Dismiss(override val notificationId: Int) : NotificationResponse

    /** Stop relaying the app this came from. Undone from Settings. */
    data class MuteApp(override val notificationId: Int) : NotificationResponse

    /** Send [text] through the notification's [NotificationAction.Kind.REPLY] action. */
    data class Reply(override val notificationId: Int, val text: String) : NotificationResponse

    /** Press the notification's action at [index] in [DeviceNotification.actions]. */
    data class Action(override val notificationId: Int, val index: Int) : NotificationResponse
}

/**
 * Coarse categories, because that is the granularity watches act on — an icon
 * and whether to vibrate.
 */
enum class NotificationCategory {
    MESSAGE, EMAIL, CALL, CALENDAR, ALARM, NAVIGATION, SOCIAL, TRANSPORT, GENERIC;

    companion object {
        /**
         * Map Android's `Notification.category` onto ours.
         *
         * Unknown categories become [GENERIC] rather than being dropped: a
         * notification the user can see on their phone should reach their
         * wrist even if we cannot classify it.
         */
        fun fromAndroid(category: String?): NotificationCategory = when (category) {
            "msg" -> MESSAGE
            "email" -> EMAIL
            "call" -> CALL
            "event" -> CALENDAR
            "alarm" -> ALARM
            "navigation" -> NAVIGATION
            "social" -> SOCIAL
            "transport" -> TRANSPORT
            else -> GENERIC
        }
    }
}

/**
 * A forecast for one location.
 *
 * Shaped to match what weather providers actually broadcast (see
 * `GenericWeatherReceiver` in the Android app) so nothing is lost in
 * translation on the way to the watch. Temperatures are whole degrees Celsius,
 * which is what the wire format uses.
 */
data class WeatherReport(
    val location: String,
    /**
     * Where the forecast is for, in degrees.
     *
     * Carried because the watch does not treat it as decoration: the weather
     * glance attaches conditions to a position, and without one it stays on
     * "waiting for weather" however many valid records it accepts. Nullable
     * because a broadcasting weather app supplies a place name and often no
     * coordinates.
     */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Seconds since the epoch, as the broadcast supplies it. */
    val timestamp: Long,
    val currentTempC: Int,
    val todayMinTempC: Int,
    val todayMaxTempC: Int,
    val currentCondition: String,
    /** OpenWeatherMap condition code — the lingua franca of these broadcasts. */
    val currentConditionCode: Int,
    val windSpeedKmh: Float? = null,
    val windDirectionDegrees: Int? = null,
    val humidityPercent: Int? = null,
    /**
     * The next few hours, for the glance's hourly strip.
     *
     * Empty for a broadcast weather app: the GENERIC_WEATHER intent carries
     * current conditions and daily highs and nothing between them.
     */
    val hourly: List<HourlyForecast> = emptyList(),
    val forecasts: List<DailyForecast> = emptyList(),
)

data class HourlyForecast(
    /** Seconds since the epoch — the hour this applies to, not an offset. */
    val timestamp: Long,
    val tempC: Int,
    val conditionCode: Int,
    val precipProbability: Int? = null,
    val humidityPercent: Int? = null,
)

data class DailyForecast(
    val minTempC: Int,
    val maxTempC: Int,
    val conditionCode: Int,
)
