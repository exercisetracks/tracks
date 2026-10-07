// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// A planned workout's colour: hue from what kind of session it is, shade from
// how hard. The tables are generated from spec/workout_colors.yaml (see its
// header for why); this is the resolver, and core spec/WorkoutColors.kt on the
// phone is the same rules in the same order.

import { CHIP_CLASSES, DOT_CLASSES, FAMILY_HUE, LEVEL_OF, STRETCHING_TYPES } from "../spec/workoutColors";
import { sportType } from "../spec/taxonomy";

const RACE_CHIP = "bg-slate-900 dark:bg-white text-white dark:text-slate-900 border-slate-700 dark:border-slate-300";
const RACE_DOT = "bg-slate-900 dark:bg-white";
const STRETCHING_SPORT = /flexib|stretch|mobility|yoga/;

/** The workout's type without its variant suffix (`field_test:ramp` → `field_test`). */
function baseType(w) {
  return (w?.workout_type || "").split(":")[0];
}

/** `race`, `stretching`, `strength`, or a sport type from the taxonomy. */
export function workoutFamily(w) {
  const type = baseType(w);
  if (type === "race") return "race";
  const sport = (w?.sport || "").toLowerCase();
  if (STRETCHING_TYPES.includes(type) || STRETCHING_SPORT.test(sport)) return "stretching";
  if (type === "strength") return "strength";
  const t = sportType(sport, w?.sub_sport);
  return t in FAMILY_HUE ? t : "other";
}

/** 1 easy, 2 steady or long, 3 hard; anything unlisted is 2. */
export function workoutLevel(w) {
  return LEVEL_OF[baseType(w)] ?? 2;
}

/** Fill, text and border classes for a workout's chip or card. */
export function workoutChip(w) {
  const family = workoutFamily(w);
  if (family === "race") return RACE_CHIP;
  return CHIP_CLASSES[FAMILY_HUE[family]][workoutLevel(w)];
}

/** The background class for a workout's dot. */
export function workoutDot(w) {
  const family = workoutFamily(w);
  if (family === "race") return RACE_DOT;
  return DOT_CLASSES[FAMILY_HUE[family]][workoutLevel(w)];
}
