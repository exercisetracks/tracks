// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.app.Application
import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The song's title on the watch's music screen. Players with "Bluetooth
 * lyrics" on put the lyric line in the title, and the watch showed the
 * lyrics, a line at a time, while the phone showed the song.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MusicTitleTest {

    @Test
    fun the_display_title_wins_over_a_lyric_line_in_the_title() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "and the night is young")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Midnight City")
            .build()
        assertEquals("Midnight City", MusicMonitor.titleOf(metadata))
    }

    @Test
    fun the_title_is_used_when_the_player_sets_no_display_title() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Midnight City")
            .build()
        assertEquals("Midnight City", MusicMonitor.titleOf(metadata))
    }
}
