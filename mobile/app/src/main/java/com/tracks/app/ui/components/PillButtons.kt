// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens

/**
 * The one button family in the app: fully rounded "tonal pills".
 *
 * - [PrimaryButton]: filled accent — the one action a screen or dialog exists for.
 * - [TonalButton]: accent at low alpha — secondary actions ("Add exercise").
 * - [NeutralButton]: neutral tint — dismissive or low-stakes ("Cancel", "Skip").
 * - [DangerButton]: error tint — destructive ("Delete", "Log out").
 *
 * Material's TextButton / OutlinedButton / Button are not used directly in
 * screens: mixing them is what produced the old mismatch of bare blue text,
 * outlined boxes and bubbles. The user picked this look on 2026-09-27.
 * Every variant shares height, radius, padding and label style, so only colour
 * says how much an action matters.
 *
 * [small] is for rows that are dense by necessity (list trailing actions,
 * chips-with-actions); everything else is the 40 dp default. Geometry comes
 * from spec/design.yaml `components.button`, so the web's `.btn` is the same
 * shape.
 *
 * How a group of them is laid out — equal cells across the width — is in
 * Justified.kt ([ButtonRow]).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) = Pill(text, onClick, modifier, icon, enabled, small, ButtonDefaults.buttonColors())

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) = Pill(text, onClick, modifier, icon, enabled, small, tinted(MaterialTheme.colorScheme.primary, TONAL_ALPHA))

@Composable
fun NeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) = Pill(
    text, onClick, modifier, icon, enabled, small,
    tinted(MaterialTheme.colorScheme.onSurface, NEUTRAL_ALPHA, content = MaterialTheme.colorScheme.onSurface),
)

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) = Pill(text, onClick, modifier, icon, enabled, small, tinted(MaterialTheme.colorScheme.error, TONAL_ALPHA))

/**
 * Accent 12 %, neutral 8 % (spec/design.yaml, shared with the web): strong
 * enough to read as a button on a card, quiet enough not to compete with the
 * primary.
 */
private const val TONAL_ALPHA = Tokens.Button.tonalAlpha
private const val NEUTRAL_ALPHA = Tokens.Button.neutralAlpha

@Composable
private fun tinted(base: Color, alpha: Float, content: Color = base): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = base.copy(alpha = alpha),
    contentColor = content,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
)

@Composable
private fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    small: Boolean,
    colors: ButtonColors,
) {
    Button(
        onClick = onClick,
        // A fixed height, not a minimum: Material's Button already floors its
        // content at 40 dp, so a min of 32 would never produce the small size.
        modifier = modifier.height(if (small) Tokens.Button.heightSm else Tokens.Button.height),
        enabled = enabled,
        shape = RoundedCornerShape(Tokens.Button.radius),
        colors = colors,
        elevation = null,
        contentPadding = PaddingValues(
            horizontal = if (small) Tokens.Button.paddingXSm else Tokens.Button.paddingX,
            vertical = Tokens.Button.paddingY,
        ),
    ) { PillContent(text, icon, small) }
}

@Composable
private fun RowScope.PillContent(text: String, icon: ImageVector?, small: Boolean) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (small) 16.dp else 18.dp))
        Spacer(Modifier.width(6.dp))
    }
    Text(text, style = Tokens.Button.style, maxLines = 1)
}
