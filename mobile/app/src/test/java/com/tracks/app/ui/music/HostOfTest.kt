// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.music

import kotlin.test.Test
import kotlin.test.assertEquals

class HostOfTest {

    /** The account row names the server as a person knows it, not as a URL. */
    @Test
    fun `the account row shows the host without scheme or path`() {
        assertEquals("music.example.com:4533", hostOf("https://music.example.com:4533/rest"))
        assertEquals("10.0.0.5", hostOf("10.0.0.5"))
        assertEquals("", hostOf(null))
    }
}
