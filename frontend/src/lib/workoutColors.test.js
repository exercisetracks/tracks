// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from "vitest";
import { workoutChip, workoutFamily, workoutLevel } from "./workoutColors";

describe("workout colours", () => {
  it("gives a long run a colour of its own, not the grey it fell through to", () => {
    const long = { workout_type: "long", sport: "running" };
    expect(workoutChip(long)).toContain("blue");
    expect(workoutChip(long)).not.toEqual(workoutChip({ workout_type: "easy", sport: "running" }));
  });

  it("keeps stretching, strength, runs and rides apart", () => {
    const hues = [
      { workout_type: "flexibility", sport: "flexibility_training" },
      { workout_type: "strength", sport: "strength_training" },
      { workout_type: "easy", sport: "running" },
      { workout_type: "easy_spin", sport: "cycling" },
    ].map((w) => workoutChip(w).split(" ")[0].split("-")[1]);
    expect(new Set(hues).size).toBe(4);
  });

  it("files a mobility session as stretching whatever sport it is under", () => {
    expect(workoutFamily({ workout_type: "mobility", sport: "running" })).toBe("stretching");
  });

  it("puts an unheard-of type in the middle shade of its family", () => {
    expect(workoutLevel({ workout_type: "something_new" })).toBe(2);
    expect(workoutChip({ workout_type: "something_new", sport: "swimming" })).toContain("cyan");
  });
});
