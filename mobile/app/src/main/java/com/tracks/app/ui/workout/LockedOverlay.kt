// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.theme.Tokens

/**
 * A workout shown over the lock screen: to look at, not to touch.
 *
 * Waking the phone mid-session shows the current stage without unlocking it
 * (MainActivity.showOverLockDuringWorkouts), but a phone lying unlocked-in-
 * effect in a pocket must not be able to pause, finish or delete the
 * session, or wander off to another page. So while the keyguard is up this
 * covers everything and takes every touch and the back gesture; any of them
 * asks the system to unlock, and once it is unlocked the overlay goes.
 */
@Composable
fun LockedOverlay(onUnlock: () -> Unit) {
    BackHandler(onBack = onUnlock)
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUnlock,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(Tokens.Radius.full),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
            modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lock, contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    "Unlock to use the controls",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}
