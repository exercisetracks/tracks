// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from "vitest";
import { autoGoals, resolveGoals, DEFAULT_GOALS } from "../components/health/goals";

const days = (n, f) => Array.from({ length: n }, (_, i) => ({ date: `2026-03-${String(i + 1).padStart(2, "0")}`, ...f(i) }));

describe("health goals", () => {
  // A week of history is the least Auto will act on; before it, the defaults.
  it("keeps the defaults until there are seven days", () => {
    expect(autoGoals(days(6, () => ({ steps: 20000 })))).toEqual(DEFAULT_GOALS);
  });

  // The goal must not climb forever: a heavy walker is capped at 12,000.
  it("follows the median, nudged up, inside the band", () => {
    const g = autoGoals(days(14, (i) => ({ steps: i < 7 ? 6000 : 30000, intensity_moderate_min: 60, floors_climbed: 40 })));
    expect(g.steps).toBe(12000);
    expect(g.intensityWeekly).toBe(300);
    expect(g.floors).toBe(20);
    expect(autoGoals(days(10, () => ({ steps: 7000 }))).steps).toBe(7500);
  });

  // The phone port computes the same: one synced setting, one number.
  it("uses the manual number only in manual mode", () => {
    const auto = { steps: 8000, intensityWeekly: 150, floors: 10 };
    expect(resolveGoals(auto, { step_goal_mode: "manual", step_goal_manual: 15000, floors_goal_manual: 3 }))
      .toEqual({ steps: 15000, intensityWeekly: 150, floors: 10 });
  });
});
