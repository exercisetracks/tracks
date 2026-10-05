// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Map library movement names to a pose slug when slugify(name) doesn't match the
// pose file name — e.g. variants that reuse one animation, or naming quirks.
// Keys are exact library names; values are slugs of files in ./poses/.
export const ALIASES = {
  // Squat variants can share the back-squat animation until authored separately.
  "Air Squat": "barbell_back_squat",
  "Dumbbell Squat": "barbell_back_squat",
  // Push-up variants.
  "Incline Push-Up": "push_up",
  "Decline Push-Up": "push_up",
  // Deadlift/hinge family.
  "Barbell Deadlift": "romanian_deadlift",
};
