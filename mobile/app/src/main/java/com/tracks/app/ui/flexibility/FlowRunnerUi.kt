// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.flexibility

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracks.app.ui.body.BodyDiagram
import com.tracks.app.ui.body.BodyView
import com.tracks.app.ui.components.ButtonRow
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.strength.KeepScreenOn
import com.tracks.app.ui.strength.clock
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.spec.muscleLabel

/**
 * The flow player: hands-free.
 *
 * Someone in pigeon pose is looking at a phone on the floor at arm's length,
 * upside down as often as not, and the only question they have is how much
 * longer. So the countdown is as large as the screen allows, ringed by its own
 * progress, and everything else is secondary: the phone *says* each change
 * ("Switch sides", "Rest. Next, …") and buzzes, so it need not be looked at.
 * The same controls sit on the lock-screen notification (FlowPlayerService),
 * and the session plays on with the screen off.
 */
@Composable
fun FlowRunner(vm: FlexibilityViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val session = state.session ?: return
    FlowPlayerContent(
        session = session,
        controls = FlowControls(
            toggle = vm::togglePause, skip = vm::skip, back = vm::back, more = vm::extend,
            end = vm::closeSession, log = vm::finishSession,
        ),
        modifier = modifier,
    )
}

class FlowControls(
    val toggle: () -> Unit = {},
    val skip: () -> Unit = {},
    val back: () -> Unit = {},
    val more: () -> Unit = {},
    val end: () -> Unit = {},
    val log: (Int?) -> Unit = {},
)

@Composable
fun FlowPlayerContent(session: FlowSession, controls: FlowControls, modifier: Modifier = Modifier) {
    KeepScreenOn()
    if (session.phase == HoldPhase.Done) {
        FlowDone(session, controls, modifier)
        return
    }
    val hold = session.hold ?: return
    val resting = session.phase == HoldPhase.Resting
    val total = if (resting) hold.restSeconds else hold.seconds
    val shown = if (resting) session.next ?: hold else hold

    Column(
        modifier.fillMaxSize().padding(Tokens.Space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(session.flow.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "${session.index + 1} / ${session.holds.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { (session.index + if (resting) 1f else 0f) / session.holds.size },
            modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Space.s2),
        )

        Spacer(Modifier.weight(0.4f))
        Text(
            if (resting) "REST · NEXT" else if (session.running) "HOLD" else "PAUSED",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            shown.name,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        shown.side?.let {
            Text("$it side", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }

        Box(Modifier.padding(vertical = Tokens.Space.s6).size(240.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (total > 0) session.remaining.toFloat() / total else 0f },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 10.dp,
                color = if (resting) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                clock(session.remaining),
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = if (resting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }

        val line = if (resting) null else hold.note?.takeIf { it.isNotBlank() }
            ?: hold.stretch?.breathCue?.takeIf { it.isNotBlank() }
            ?: hold.stretch?.cues?.firstOrNull()
        line?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shown.stretch?.let { s ->
            val activation = remember(s.name) {
                s.secondaryMuscles.associateWith { 0.5f } + s.primaryMuscles.associateWith { 1f }
            }
            Row(Modifier.padding(top = Tokens.Space.s3), verticalAlignment = Alignment.CenterVertically) {
                BodyDiagram(activation, BodyView.Front, Modifier.size(40.dp, 84.dp))
                BodyDiagram(activation, BodyView.Back, Modifier.size(40.dp, 84.dp))
                Text(
                    s.primaryMuscles.joinToString(" · ", transform = ::muscleLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Tokens.Space.s2),
                )
            }
        }
        Spacer(Modifier.weight(0.6f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = controls.back, modifier = Modifier.size(56.dp)) { Text("⏮") }
            FilledIconButton(onClick = controls.toggle, modifier = Modifier.size(80.dp)) {
                Text(if (session.running) "❚❚" else "▶", fontSize = 26.sp)
            }
            FilledTonalIconButton(onClick = controls.skip, modifier = Modifier.size(56.dp)) { Text("⏭") }
        }
        ButtonRow {
            NeutralButton("End", onClick = controls.end)
            TonalButton("+${FlowPlayerService.EXTEND_SECONDS}s", onClick = controls.more)
        }
    }
}

@Composable
private fun FlowDone(session: FlowSession, controls: FlowControls, modifier: Modifier) {
    var rpe by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier.fillMaxSize().padding(Tokens.Space.s6),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.s4, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (session.saved) "Session logged" else "Flow complete", style = MaterialTheme.typography.headlineSmall)
        if (!session.saved) {
            val minutes = session.holds.sumOf { it.seconds + it.restSeconds } / 60
            Text(
                "${session.flow.name} · ${session.holds.size} holds · about $minutes min",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("How did it feel?", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s1_5)) {
                (1..10).forEach { v ->
                    FilterChip(selected = rpe == v, onClick = { rpe = if (rpe == v) null else v }, label = { Text("$v") })
                }
            }
        }
        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(Tokens.Space.s2))
        ButtonRow {
            NeutralButton(if (session.saved) "Done" else "Close", onClick = controls.end)
            if (!session.saved) {
                PrimaryButton(if (session.saving) "Saving…" else "Log it", onClick = { controls.log(rpe) }, enabled = !session.saving)
            }
        }
    }
}
