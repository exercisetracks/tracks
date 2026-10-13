// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import com.tracks.core.fit.decode.FitReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The file that switches off the watch's own Wi-Fi uploads to Garmin, read
 * back by the phone's decoder: a settings file whose device_settings carries
 * only the fields being changed, so the watch leaves everything else alone.
 */
class SettingsFitTest {

    private val now = 1_790_820_000_000L

    @Test
    fun auto_upload_off_is_a_settings_file_carrying_that_field_alone() {
        val bytes = SettingsFit.encode(wifiAutoUpload = false, nowMillis = now)!!
        val messages = FitReader(bytes).messages().toList()

        val fileId = messages.single { it.name == "file_id" }
        assertEquals("settings", fileId.get("type").toString())
        assertEquals("65534", fileId.fields.first { it.name == "product" || it.name == "garmin_product" }.rawValue.toString())

        val settings = messages.single { it.globalMesgNum == 2 }
        assertEquals(listOf("unknown_38"), settings.fields.map { it.name })
        assertEquals("0", settings.fields.single().value.toString())
    }

    @Test
    fun wifi_itself_can_be_switched_off_too() {
        val settings = FitReader(SettingsFit.encode(wifiAutoUpload = false, wifi = false, nowMillis = now)!!)
            .messages().single { it.globalMesgNum == 2 }
        assertEquals(listOf("unknown_38", "unknown_98"), settings.fields.map { it.name })
    }

    @Test
    fun a_file_that_would_change_nothing_is_not_built() {
        assertNull(SettingsFit.encode(wifiAutoUpload = null, nowMillis = now))
    }

    @Test
    fun the_profile_carries_the_body_as_garmin_connect_sends_it() {
        // Connect's capture, 2026-10-13: user_profile in a settings file,
        // height in centimetres, weight in tenths of a kilogram, birth year
        // less 1900. A watch fresh from a factory reset has none of this.
        val bytes = SettingsFit.encodeUserProfile(
            weightKg = 72.34, heightCm = 181.6, sex = "male", birthYear = 1990,
            nowMillis = now, currentYear = 2026,
        )!!
        val messages = FitReader(bytes).messages().toList()
        assertEquals("settings", messages.single { it.name == "file_id" }.get("type").toString())
        val profile = messages.single { it.globalMesgNum == 3 }
        fun raw(n: String) = profile.fields.single { it.name == n }.rawValue.toString()
        assertEquals("1", raw("gender"))
        assertEquals("36", raw("age"))
        assertEquals("182", raw("height"))
        assertEquals("723", raw("weight"))
        assertEquals("90", raw("unknown_24"), "birth year since 1900")
    }

    @Test
    fun only_what_is_known_goes_into_the_profile() {
        val profile = FitReader(SettingsFit.encodeUserProfile(70.0, null, null, null, now, 2026)!!)
            .messages().single { it.globalMesgNum == 3 }
        assertEquals(listOf("weight"), profile.fields.map { it.name })
        assertNull(SettingsFit.encodeUserProfile(null, null, null, null, now, 2026))
    }
}
