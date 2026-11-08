// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * What the race plan's predicted finish assumes. It is race day's, not
 * today's: today's running fitness plus what the rest of the training plan
 * should add, at the rate the plan has actually been done, and race
 * morning's form. A finish that silently assumed every remaining session
 * would read as a promise; one that silently assumed none would never move
 * as the training went in. The page says which it is.
 */
import { describe, expect, it } from "vitest";
import { projectionNote } from "../components/raceplan/format";

const projection = {
  vdot_today: 41.2, vdot_race_day: 42.3, adherence: 0.86,
  tsb_today: -8.4, tsb_race_day: 14.1, today_seconds: 3125.2, today_time: "52:05",
};

describe("the race-day prediction's note", () => {
  it("says the gain still to come is credited at the rate the plan has been done", () => {
    const note = projectionNote(projection);
    expect(note).toContain("VDOT 41.2");
    expect(note).toContain("VDOT 42.3 on race day");
    expect(note).toContain("86% of the plan");
  });

  it("gives race morning's form beside today's, signed", () => {
    expect(projectionNote(projection)).toContain("Form on race morning +14.1 (today -8.4).");
  });

  it("prints a whole VDOT with its decimal, as the phone does", () => {
    expect(projectionNote({ ...projection, vdot_today: 45, vdot_race_day: 46 })).toContain("VDOT 45.0");
  });

  it("says a race with no training plan is predicted at today's fitness", () => {
    const note = projectionNote({ ...projection, adherence: null, vdot_race_day: 41.2 });
    expect(note).toContain("No training plan for this race");
    expect(note).not.toContain("planned day missed");
  });

  it("has nothing to say without a projection", () => {
    expect(projectionNote(null)).toBeNull();
    expect(projectionNote(undefined)).toBeNull();
  });
});
