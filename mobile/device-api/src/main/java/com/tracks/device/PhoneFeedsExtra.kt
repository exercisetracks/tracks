// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device

/**
 * The rest of what a watch expects from a phone: what is playing, and what is
 * next in the calendar.
 *
 * Together with [DeviceNotification] and [WeatherReport] these are the four
 * feeds that make a watch feel connected rather than merely synced. A fitness
 * app that pairs to a watch and then silences music control and the agenda is
 * a downgrade from what the user had, which is why they are in scope.
 */

/** What the phone is playing, for the watch's music screen. */
data class MusicState(
    val isPlaying: Boolean,
    val position: Int,
    /** Seconds. Zero when the player does not report one (live streams). */
    val duration: Int,
    /** 0-100, or null when the player does not expose volume. */
    val volumePercent: Int? = null,
)

/** Track metadata. Null fields are genuinely unknown, not empty strings. */
data class MusicTrack(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Commands a watch can send back to the phone.
 *
 * The watch is a remote control here, which is the one place these feeds run
 * in the opposite direction.
 */
enum class MusicCommand {
    PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN;

    companion object {
        /** Unknown commands are ignored rather than guessed at. */
        fun fromWire(value: String): MusicCommand? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * A device asking to be helped to find its phone.
 *
 * ## Why the quiet one is kept separate
 *
 * Watches send two different requests and they are not the same instruction.
 * "Ring" is for a phone lost in a room; "vibrate" is what a device sends when
 * it knows the phone should stay silent — a meeting, a sleeping baby, a rule
 * the user set on the device itself. Collapsing them into one "make yourself
 * findable" would honour the first and override the second, which is the sort
 * of thing that gets an app deleted.
 *
 * Garmin only ever sends [RING] and [STOP] today. [VIBRATE] costs three lines
 * to carry and is the difference between another vendor slotting in and another
 * vendor needing this enum widened.
 */
enum class FindPhone {
    /** Make noise, loudly enough to be heard through a sofa cushion. */
    RING,

    /** Buzz only — the device has asked for this, so do not add sound. */
    VIBRATE,

    /** Found, or given up on: stop whatever is happening. */
    STOP,
}

/**
 * A calendar entry, flattened for a watch.
 *
 * Only what fits on a wrist: when, what, and where. Attendees, organisers,
 * conferencing links, and descriptions are deliberately not carried — a watch
 * cannot usefully show them, and calendar access already reads more of a
 * person's life than most permissions.
 */
data class CalendarEvent(
    /** Stable across syncs so an edit updates rather than duplicates. */
    val id: Long,
    val title: String,
    val location: String? = null,
    /** Epoch millis. */
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    /** Calendar colour, so the watch can group by calendar. */
    val color: Int? = null,
)
