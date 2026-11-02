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

  it("draws a course without elevation from its laps", () => {
    // A GPX with no heights has no hills to show, but its targets still do.
    const pts = courseProfile([[0, 0], [0, 0.01]], LAPS, "pace");
    expect(pts.every((p) => p.ele == null)).toBe(true);
    expect(pts[0].target).toBe(300);
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

import { raceTargetsText } from "../components/raceplan/RaceTargets";

describe("the race-wide targets line", () => {
  it("keeps the HR ceiling and climb target the old strategy card showed", () => {
    // The card went with the old fuelling model; these two must not.
    expect(raceTargetsText({ target_hr_ceiling: 165, climb_target_w_per_kg: 3.7, climb_target_watts: 259 }))
      .toBe("Average HR ≤ 165 bpm · Climb at 3.7 W/kg (≈ 259 W)");
    expect(raceTargetsText({ target_hr_ceiling: null })).toBe("");
  });
});

describe("the profile without a course", () => {
  const laps = [
    { distance_m: 1000, target_sec_per_km: 300, hr_ceiling: 150, label: "Climb 4%" },
    { distance_m: 1000, target_sec_per_km: 360, hr_ceiling: 170, label: "Flat" },
  ];

  it("is drawn from the laps alone, with no elevation", () => {
    // The graph replaced the lap table, so it has to show whether or not a
    // course is loaded.
    const pts = courseProfile(null, laps, "pace");
    expect(pts.length).toBeGreaterThan(50);
    expect(pts.every((p) => p.ele == null)).toBe(true);
    expect(pts[0].target).toBe(300);
    expect(pts[pts.length - 1].target).toBe(360);
    expect(pts[pts.length - 1].m).toBe(2000);
  });

  it("carries the HR ceiling as its own line, and the ground at each point", () => {
    const pts = courseProfile(null, laps, "pace");
    expect(pts[0].hr).toBe(150);
    expect(pts[0].lap.label).toBe("Climb 4%");
    expect(pts[pts.length - 1].lap.label).toBe("Flat");
  });

  it("has nothing to draw with neither a course nor a lap", () => {
    expect(courseProfile(null, [], "pace")).toBeNull();
  });
});
