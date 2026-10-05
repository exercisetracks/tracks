// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import com.tracks.core.spec.muscleLabel

/**
 * A name for a session built out of a muscle filter.
 *
 * When somebody taps chest and triceps on the body model and turns the result
 * into a workout, "Chest & Triceps" is what they were going to type anyway. It
 * is a suggestion and not a decision — the field stays editable, and an empty
 * filter suggests nothing rather than inventing something.
 *
 * Sorted by label rather than left in set order, so the same two muscles always
 * produce the same name.
 */
fun suggestedSessionName(muscles: Set<String>): String {
    val labels = muscles.map(::muscleLabel).sorted()
    return when (labels.size) {
        0 -> ""
        1 -> labels[0]
        2 -> "${labels[0]} & ${labels[1]}"
        // Three is where a full list stops being a name. The first two carry
        // the sense of it and the count says there is more.
        else -> "${labels[0]}, ${labels[1]} & ${labels.size - 2} more"
    }
}
