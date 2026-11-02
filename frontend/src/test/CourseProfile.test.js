// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The same cases as mobile/core's CourseProfileTest: the two are one algorithm.
import { describe, expect, it } from "vitest";
import { courseProfile, targetMetric } from "../components/raceplan/courseProfile";

// Two equal legs along the equator, up and down a 100 m hill.
const PATH = [[0, 0, 100], [0, 0.01, 200], [0, 0.02, 100]];
const LAPS = [
  { distance_m: 1000, target_sec_per_km: 300, target_watts: 250, hr_ceiling: 160 },
  { distance_m: 1000, target_sec_per_km: 360, target_watts: 200, hr_ceiling: 170 },
];

describe("the course profile", () => {
  it("lays the plan's laps along the course, whatever its own length", () => {
    // Laps cover 2 km; the course is ~2.22 km. Each lap's target sits at its
    // middle on the course, and the line runs smooth between them.
    const pts = courseProfile(PATH, LAPS, "pace");
    expect(pts.map((p) => p.ele)).toEqual([100, 200, 100]);
    expect(pts[0].target).toBe(300);
    expect(pts[1].target).toBeCloseTo(330, 6);
    expect(pts[2].target).toBe(360);
    expect(pts[2].m).toBeCloseTo(2223.9, 0);
  });

  it("has no plot for a course without elevation", () => {
    // Without the hills there is nothing to read the target against.
    expect(courseProfile([[0, 0], [0, 0.01]], LAPS, "pace")).toBeNull();
  });

  it("draws each sport's own coaching number", () => {
    expect(targetMetric("running", LAPS)).toBe("pace");
    expect(targetMetric("cycling", LAPS)).toBe("power");
    expect(targetMetric("triathlon", LAPS)).toBe("power");
    expect(targetMetric("mountain_biking", LAPS)).toBe("hr");
    expect(targetMetric("swimming", LAPS)).toBeNull();
  });

  it("falls back to pace when a sport's own number is missing", () => {
    // A cycling plan without an FTP has laps with no watts.
    expect(targetMetric("cycling", [{ distance_m: 1000, target_sec_per_km: 120 }])).toBe("pace");
  });
});
