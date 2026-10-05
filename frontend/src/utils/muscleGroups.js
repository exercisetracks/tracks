// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Muscle-group data and activation for the web app.
 *
 * The table itself is no longer here. It lives in spec/muscle_groups.yaml,
 * is generated into src/spec/muscleGroups.js, and is evaluated by
 * src/spec/muscleActivation.js — the same table and the same semantics the
 * Python backend (app/calculators/muscle_activation.py) and the Kotlin
 * mobile core (spec/MuscleGroups.kt) use. This module is now just the
 * app-facing shape of that, matching how src/utils/sportUtils.js sits over
 * src/spec/taxonomy.js.
 *
 * To change a mapping or a label, edit spec/muscle_groups.yaml and run
 * `python3 spec/codegen.py`. Editing the generated file does nothing.
 */
export { CATEGORY_MUSCLES } from "../spec/muscleGroups";
export { computeMuscleActivation, muscleLabel, categoryLabel } from "../spec/muscleActivation";
