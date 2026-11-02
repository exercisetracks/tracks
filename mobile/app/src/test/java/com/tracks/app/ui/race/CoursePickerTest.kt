// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.race

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoursePickerTest {

    /** The picker used to offer every file, and a PDF reached the GPX parse. */
    @Test
    fun `the picker offers no type a PDF or a photo carries`() {
        assertFalse("*/*" in GPX_MIME_TYPES)
        assertFalse(GPX_MIME_TYPES.any { it.startsWith("image/") || it == "application/pdf" })
    }

    /**
     * Octet-stream is what many file managers call a downloaded .gpx, so it
     * has to stay offered — and then the name is what tells a GPX apart.
     */
    @Test
    fun `a file that slips through the filter is refused by its name`() {
        assertTrue("application/octet-stream" in GPX_MIME_TYPES)
        assertTrue(isGpxName("Marathon.GPX"))
        assertFalse(isGpxName("entry.pdf"))
        assertFalse(isGpxName("course.gpx.zip"))
    }

    /** A provider that hides the name still gets the parse, which refuses a file with no track. */
    @Test
    fun `a file without a name is left to the parse`() {
        assertTrue(isGpxName(null))
    }
}
