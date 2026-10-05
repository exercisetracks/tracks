// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt
import com.tracks.app.ui.components.NeutralButton
import com.tracks.core.spec.muscleLabel

/**
 * Choose muscles by pointing at them.
 *
 * A body, front and back, that you tap. The alternative — and what a phone app
 * usually does — is a list of forty checkboxes reading "Latissimus Dorsi",
 * "Erector Spinae", "Rhomboids". That list asks the user to already know the
 * anatomical name of the thing they can feel, and to know which of the three
 * names above covers it. Pointing at the sore spot asks nothing.
 *
 * Both views are on screen at once rather than behind a front/back toggle. The
 * bodies are narrow enough to sit side by side on a phone, and a toggle would
 * hide half the answer at all times — someone looking for "the muscle down the
 * back of my thigh" cannot find it on a switch they have to know to flip.
 *
 * Selection is a set, not one value: an exercise works several muscles and a
 * session is usually planned around a region rather than a single head. What
 * the caller does with the set is its own business — [StrengthScreen] filters a
 * library by it, but the same picker fits a stretch flow or a soreness report.
 *
 * The drawing, hit-testing and artwork all belong to [BodyDiagram]; this is the
 * selection state and the labelling around it.
 */
@Composable
fun MusclePicker(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    gender: BodyGender = BodyGender.Male,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PickerSide("Front", BodyView.Front, selected, onSelectedChange, gender, Modifier.weight(1f))
            PickerSide("Back", BodyView.Back, selected, onSelectedChange, gender, Modifier.weight(1f))
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                // Named as well as shaded, because one shape can stand for
                // several muscles and nobody can read a muscle name off a
                // colour. This is also the only feedback for a tap that landed
                // on the neighbour of the intended muscle.
                if (selected.isEmpty()) "Tap a muscle to narrow the list"
                else selected.joinToString(" · ") { muscleLabel(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) {
                NeutralButton("Clear", onClick = { onSelectedChange(emptySet()) })
            }
        }
    }
}

/**
 * Front over back, each as tall as the space allows.
 *
 * The figures are twice as tall as they are wide, so on a phone height is
 * what makes them big enough to hit a calf or a forearm. Each takes half of
 * whatever height it is given and sizes its width from that — the opposite
 * of [MusclePicker], which fits two side by side in a width.
 */
@Composable
fun StackedMusclePicker(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    gender: BodyGender = BodyGender.Male,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (view in listOf(BodyView.Front, BodyView.Back)) {
            BodyDiagram(
                activation = emptyMap(),
                view = view,
                gender = gender,
                selected = selected,
                onMuscleTap = { muscle ->
                    onSelectedChange(if (muscle in selected) selected - muscle else selected + muscle)
                },
                // The slot, not an aspect ratio: BodyDiagram already fits the
                // figure inside whatever box it gets (fitScale takes the smaller
                // of the two scales) and centres it. Sizing the box by height ×
                // aspect instead made it wider than a narrow column, and the
                // hands were clipped by the screen edge and the list beside it.
                // Then inset by 5% of the slot on every side: edge to edge the
                // figures looked crammed against each other and the header
                // (user asked for more room, 2026-09-28).
                modifier = Modifier.weight(1f).fillMaxWidth().insetFraction(FIGURE_INSET),
            )
        }
    }
}

/** Breathing room around each stacked figure, as a share of its slot. */
private const val FIGURE_INSET = 0.05f

/**
 * Padding as a fraction of the space given rather than in dp, so the gap
 * stays in proportion to the figure on a small phone and a tall one.
 */
private fun Modifier.insetFraction(f: Float): Modifier = layout { measurable, c ->
    val dx = if (c.hasBoundedWidth) (c.maxWidth * f).roundToInt() else 0
    val dy = if (c.hasBoundedHeight) (c.maxHeight * f).roundToInt() else 0
    val placeable = measurable.measure(c.offset(-2 * dx, -2 * dy))
    layout(placeable.width + 2 * dx, placeable.height + 2 * dy) { placeable.place(dx, dy) }
}

@Composable
private fun PickerSide(
    label: String,
    view: BodyView,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    gender: BodyGender,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BodyDiagram(
            // Nothing is "active" here — this body reports a choice rather than
            // a workout, so the only fill is the selection itself.
            activation = emptyMap(),
            view = view,
            gender = gender,
            selected = selected,
            onMuscleTap = { muscle ->
                // Toggling, not setting: a mis-tap is undone by tapping the
                // same place again, which is the gesture the hand already
                // expects from a checkbox.
                onSelectedChange(
                    if (muscle in selected) selected - muscle else selected + muscle
                )
            },
            // The artwork is twice as tall as it is wide; inside a Row a canvas
            // left to choose its own height gets none.
            modifier = Modifier.fillMaxWidth().aspectRatio(bodyAspect(view, gender)),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

