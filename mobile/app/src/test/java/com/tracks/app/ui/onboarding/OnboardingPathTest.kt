// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.onboarding

import com.tracks.app.ui.map.blankStyleJson
import com.tracks.app.ui.profile.backupOverdue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.tracks.app.ui.onboarding.OnboardingStep as S

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class OnboardingPathTest {

    /** Standalone is the only source of a profile, so it must ask the web Setup's questions. */
    @Test
    fun a_standalone_phone_asks_for_the_profile_and_is_told_what_no_server_means() {
        val path = onboardingPath(standalone = true, restored = false, hasDevice = true)
        assertEquals(
            listOf(S.Welcome, S.Body, S.Zones, S.Strength, S.Habits, S.Look, S.Privacy, S.Device, S.Permissions, S.Watch, S.Uploads, S.Weather, S.Done),
            path,
        )
    }

    /** Re-asking a signed-in account's profile would be a second place to set the same value. */
    @Test
    fun the_server_path_skips_the_profile_and_offers_music() {
        val path = onboardingPath(standalone = false, restored = false, hasDevice = true)
        assertFalse(S.Body in path)
        assertTrue(S.SignIn in path && S.Music in path)
    }

    /**
     * A watch set up with Garmin Connect uploads every activity to Garmin over
     * Wi-Fi on its own; only its owner can switch that off, so everyone with a
     * watch is told how — and nobody without one is.
     */
    @Test
    fun everyone_with_a_watch_is_told_to_stop_its_garmin_uploads() {
        for (standalone in listOf(true, false)) {
            val path = onboardingPath(standalone = standalone, restored = false, hasDevice = true)
            assertEquals("standalone=$standalone", path.indexOf(S.Watch) + 1, path.indexOf(S.Uploads))
        }
        assertFalse(S.Uploads in onboardingPath(standalone = true, restored = false, hasDevice = false))
    }

    /** Weather reaches the watch only if a weather app is told to send it; a watch owner is shown how. */
    @Test
    fun everyone_with_a_watch_is_shown_how_to_get_weather_onto_it() {
        for (standalone in listOf(true, false)) {
            assertTrue(S.Weather in onboardingPath(standalone = standalone, restored = false, hasDevice = true))
        }
        assertFalse(S.Weather in onboardingPath(standalone = true, restored = false, hasDevice = false))
    }

    @Test
    fun a_restored_backup_skips_the_profile_it_already_carries() {
        val path = onboardingPath(standalone = true, restored = true, hasDevice = false)
        assertEquals(listOf(S.Welcome, S.Device, S.Permissions, S.Done), path)
    }

    /**
     * Permissions used to come first and ask everyone for Bluetooth and
     * notification access — before the app knew whether there was a watch.
     */
    @Test
    fun the_watch_question_comes_before_the_permissions_it_decides() {
        for (standalone in listOf(true, false)) for (hasDevice in listOf(true, false)) {
            val path = onboardingPath(standalone = standalone, restored = false, hasDevice = hasDevice)
            assertTrue("standalone=$standalone device=$hasDevice", path.indexOf(S.Device) < path.indexOf(S.Permissions))
        }
    }

    @Test
    fun a_phone_only_user_is_asked_for_location_and_notifications_and_nothing_for_a_watch() {
        assertEquals(listOf("location", "post_notifications"), grantsFor(hasDevice = false).map { it.key })
        assertTrue("nothing blocks a phone-only user", grantsFor(hasDevice = false).none { it.required })
        assertEquals(
            listOf("bluetooth", "location", "post_notifications", "calendar", "notification_access"),
            grantsFor(hasDevice = true).map { it.key },
        )
    }

    /** The first recorded run used to stop at a location prompt; onboarding asks instead. */
    @Test
    fun location_is_asked_during_onboarding_either_way() {
        assertTrue(grantsFor(hasDevice = true).any { it.key == "location" })
        assertTrue(grantsFor(hasDevice = false).any { it.key == "location" })
    }

    /** New users were asked about strength but not how often they run or ride. */
    @Test
    fun a_standalone_phone_asks_how_often_they_train() {
        assertTrue(S.Habits in onboardingPath(standalone = true, restored = false, hasDevice = false))
    }

    @Test
    fun only_a_phone_with_no_server_and_no_recent_backup_is_nudged() {
        val day = 86_400_000L
        val now = 100 * day
        assertTrue(backupOverdue(linked = false, lastBackupAtMs = null, nowMs = now))
        assertTrue(backupOverdue(linked = false, lastBackupAtMs = now - 15 * day, nowMs = now))
        assertFalse(backupOverdue(linked = false, lastBackupAtMs = now - 13 * day, nowMs = now))
        assertFalse(backupOverdue(linked = true, lastBackupAtMs = null, nowMs = now))
    }

    @Test
    fun the_blank_basemap_is_a_valid_style_with_only_a_background() {
        val style = JSONObject(blankStyleJson("#E5E7EB"))
        assertEquals(8, style.getInt("version"))
        val layers = style.getJSONArray("layers")
        assertEquals(1, layers.length())
        assertEquals("background", layers.getJSONObject(0).getString("type"))
    }
}
