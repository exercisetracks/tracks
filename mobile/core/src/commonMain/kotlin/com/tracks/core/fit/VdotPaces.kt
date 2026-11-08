// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

import com.tracks.core.plan.PlanBase

/**
 * Training paces from a VDOT, so the phone can put coaching targets on a
 * workout it built itself: `vdot_to_paces` in `app/calculators/plan/base.py`.
 *
 * This is [PlanBase.vdotToPaces], the plan generator's port, by name. It had
 * its own table and its own rounding — the scaled value rounded half-even,
 * where the server rounds with Python's `round(x, 1)` — and a tenth of a
 * second matters here: the pace is divided into a million and truncated to
 * get the speed target a step carries, so the two could hand the watch
 * different integers for the same plan. One table, one rounding.
 */
fun vdotToPaces(vdot: Double): Map<String, Double> = PlanBase.vdotToPaces(vdot)
