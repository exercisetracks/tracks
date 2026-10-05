// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Strength-training experience levels for the web app.
 *
 * The table itself is no longer here. It lives in spec/strength.yaml, is
 * generated into src/spec/strength.js, and is evaluated by
 * src/spec/experience.js — the same table the backend maps to a default
 * strength tier, a difficulty ceiling, and a periodization starting stage
 * (see backend/app/calculators/strength_plan/leveling.py). This module is
 * now just the app-facing shape of that, matching how src/utils/sportUtils.js
 * sits over src/spec/taxonomy.js.
 *
 * To add a level or reword its copy, edit spec/strength.yaml and run
 * `python3 spec/codegen.py`. Editing the generated file does nothing.
 */
export { EXPERIENCE_OPTIONS, EXPERIENCE_LABEL, experienceDefaultTier } from "../spec/experience";
