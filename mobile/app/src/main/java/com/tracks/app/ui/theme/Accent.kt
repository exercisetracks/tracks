// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The accent colours, shared with the web app.
 *
 * These are the same seven presets `AppearanceSection.jsx` offers, under the
 * same keys, from the same Tailwind ramps — which is what makes the preference
 * portable at all. Picking "violet" on the laptop has to arrive here as violet
 * rather than as a hex the phone has to guess a dark-mode variant for.
 *
 * Five shades rather than one, because a single hue cannot serve both schemes:
 * a 500 that reads well on white is muddy on true black, and one that glows on
 * black is unreadable on white. Dark takes the 400, light takes the 600, and
 * the containers come from the ends of the ramp.
 *
 * ## What is *not* here
 *
 * The colours that mean something — training zones, form bands, sleep stages.
 * Those come from the shared spec and from the web app's own constants
 * precisely so the two clients cannot disagree about what "threshold" looks
 * like, and letting an accent move them would undo that. The accent is chrome.
 */
enum class Accent(
    val key: String,
    val label: String,
    /** The swatch shown in the picker — the ramp's 500. */
    val swatch: Color,
    private val shade100: Color,
    private val shade400: Color,
    private val shade600: Color,
    private val shade900: Color,
) {
    Emerald("emerald", "Emerald", Color(0xFF10B981), Color(0xFFD1FAE5), Color(0xFF34D399), Color(0xFF059669), Color(0xFF064E3B)),
    Blue("blue", "Blue", Color(0xFF3B82F6), Color(0xFFDBEAFE), Color(0xFF60A5FA), Color(0xFF2563EB), Color(0xFF1E3A8A)),
    Violet("violet", "Violet", Color(0xFF8B5CF6), Color(0xFFEDE9FE), Color(0xFFA78BFA), Color(0xFF7C3AED), Color(0xFF4C1D95)),
    Rose("rose", "Rose", Color(0xFFF43F5E), Color(0xFFFFE4E6), Color(0xFFFB7185), Color(0xFFE11D48), Color(0xFF881337)),
    Amber("amber", "Amber", Color(0xFFF59E0B), Color(0xFFFEF3C7), Color(0xFFFBBF24), Color(0xFFD97706), Color(0xFF78350F)),
    Sky("sky", "Sky", Color(0xFF0EA5E9), Color(0xFFE0F2FE), Color(0xFF38BDF8), Color(0xFF0284C7), Color(0xFF0C4A6E)),
    Teal("teal", "Teal", Color(0xFF14B8A6), Color(0xFFCCFBF1), Color(0xFF2DD4BF), Color(0xFF0D9488), Color(0xFF134E4A)),
    ;

    fun primary(dark: Boolean): Color = if (dark) shade400 else shade600
    fun onPrimary(dark: Boolean): Color = if (dark) Color.Black else Color.White
    fun container(dark: Boolean): Color = if (dark) shade900 else shade100
    fun onContainer(dark: Boolean): Color = if (dark) shade100 else shade900

    companion object {
        /**
         * Matched to the web app's default, not to the app's old green.
         *
         * The phone shipped with its own mint green and the browser with
         * emerald, which was invisible while neither synced and would have been
         * the first thing anyone noticed once they did. They are near enough
         * that nobody will mourn the mint.
         */
        val default = Emerald

        /** An unknown or absent key falls back rather than failing. */
        fun of(key: String?): Accent =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: default
    }
}

/**
 * Light, dark, or whatever the phone is doing.
 *
 * Device-local by design — see [com.tracks.core.api.UserSettings]. The phone in
 * a tent at night and the laptop under an office light want different answers,
 * so syncing this would guarantee one of the two is wrong.
 */
enum class ThemeMode(val key: String, val label: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
    ;

    companion object {
        fun of(key: String?): ThemeMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: System
    }
}
