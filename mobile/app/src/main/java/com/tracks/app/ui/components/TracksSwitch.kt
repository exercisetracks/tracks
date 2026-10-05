// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's switch: Material's, with an "off" state that can be seen.
 *
 * Material draws off as an `outline` thumb and border on a
 * `surfaceContainerHighest` track. Our dark outline is a near-black grey
 * (#3A3A3C) chosen for hairline dividers, and the container roles are unset,
 * so off was a dark grey knob on a dark grey track on a black sheet — the
 * "Include strength" toggle could not be read as a control at all. Off here
 * is drawn in `onSurfaceVariant`, the colour secondary text already uses,
 * which reads on both themes and on both card and sheet backgrounds.
 *
 * Done as a wrapper rather than by changing `outline` in the scheme: outline
 * is every divider and border in the app, and those are right as hairlines.
 * Every Switch in the app goes through this, so they all look the same.
 */
@Composable
fun TracksSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = Switch(checked, onCheckedChange, modifier, enabled = enabled, colors = tracksSwitchColors())

@Composable
fun tracksSwitchColors(): SwitchColors {
    val c = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = c.onPrimary,
        checkedTrackColor = c.primary,
        checkedBorderColor = c.primary,
        uncheckedThumbColor = c.onSurfaceVariant,
        uncheckedTrackColor = c.onSurface.copy(alpha = 0.08f),
        uncheckedBorderColor = c.onSurfaceVariant.copy(alpha = 0.8f),
    )
}
