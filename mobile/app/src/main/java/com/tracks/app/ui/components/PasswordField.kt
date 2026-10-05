// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A password entry that behaves like one.
 *
 * `KeyboardType.Password` is the part that matters, and the part a merely
 * masked field is missing: it tells the keyboard this is a secret, so it stops
 * autocorrecting, stops offering suggestions above the keys, and stops learning
 * what is typed. Without it a password gets "corrected" into something else
 * between the keyboard and the field, and the login fails for a reason nobody
 * can see.
 *
 * The eye swaps the dots for the real characters, for checking a long password
 * before submitting it. The keyboard stays in password mode either way: there
 * is never a good reason to autocorrect one.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Password",
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) { EyeIcon(off = !visible, tint = tint) }
        },
        modifier = modifier,
    )
}

/**
 * A small eye, struck through when the password is hidden.
 *
 * Drawn rather than taken from material-icons-extended, which is a very large
 * dependency to add for one glyph in an app that otherwise needs none of it.
 */
@Composable
private fun EyeIcon(off: Boolean, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.06f, h * 0.28f),
            size = Size(w * 0.88f, h * 0.44f),
            style = Stroke(width = w * 0.08f, cap = StrokeCap.Round),
        )
        drawCircle(color = tint, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
        if (off) {
            drawLine(
                color = tint,
                start = Offset(w * 0.14f, h * 0.16f),
                end = Offset(w * 0.86f, h * 0.84f),
                strokeWidth = w * 0.09f,
                cap = StrokeCap.Round,
            )
        }
    }
}
