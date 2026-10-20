// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The note's first words, as the detail screen's app bar shows them.
 *
 * The bar has room for a few words beside the pencil and no more; the cases
 * here are the ones that would otherwise put an empty pill there, or cut a
 * word in half, or hide that there is more to read.
 */
class NoteSnippetTest {

    @Test
    fun `no note shows no snippet`() {
        assertNull(noteSnippet(null))
        assertNull(noteSnippet(""))
        // A note cleared to whitespace is no note, not a blank pill.
        assertNull(noteSnippet("  \n "))
    }

    @Test
    fun `a short note is shown whole`() {
        assertEquals("Felt flat today", noteSnippet("Felt flat today"))
    }

    @Test
    fun `a long note is cut at a word and says there is more`() {
        assertEquals("Legs heavy from Saturday's…", noteSnippet("Legs heavy from Saturday's long run, kept it easy"))
    }

    @Test
    fun `only the first line is used, and the rest is marked as more`() {
        assertEquals("Windy…", noteSnippet("Windy\nGusts on the ridge to 60 km/h"))
    }
}
