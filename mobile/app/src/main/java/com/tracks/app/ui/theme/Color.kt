// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's own palette — chrome only.
 *
 * Nothing here carries meaning. Colours that *mean* something — training zones,
 * TSB form bands — come from the shared spec (spec/zones.yaml, generated into
 * com.tracks.core.spec) precisely so the phone and the browser cannot disagree
 * about what "threshold" or "fresh" looks like. Adding a zone colour to this
 * file would be starting that divergence over again.
 */

/** True black, not charcoal. See [TracksTheme] for why the panel type matters. */
val ExpeditionBlack = Color(0xFF000000)

val TracksGreen = Color(0xFF7BD88F)
val TracksGreenLight = Color(0xFFB8F0C4)
val TracksGreenDark = Color(0xFF1E6B33)

val TracksBlue = Color(0xFF7FB5E8)

val TracksRed = Color(0xFFFF8A80)
val TracksRedDark = Color(0xFFB3261E)
