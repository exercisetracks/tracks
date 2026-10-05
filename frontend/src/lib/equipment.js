// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Strength-training equipment vocabulary for the web app.
 *
 * The table itself is no longer here. It lives in spec/strength.yaml and is
 * generated into src/spec/strength.js — the full set of values the backend
 * validates against (see app/api/strength/schemas.py's VALID_EQUIPMENT,
 * which now sources from the same spec). This used to be a manually
 * maintained superset of the backend's bare vocabulary that the file's own
 * header admitted could drift; it can't any more.
 *
 * To add or relabel an option, edit spec/strength.yaml and run
 * `python3 spec/codegen.py`. Editing the generated file does nothing.
 */
export { EQUIPMENT as EQUIPMENT_OPTIONS } from "../spec/strength";
