// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from "vitest";
import { areaPolygons, unionOutline } from "../pages/maps/utils/areaOutline";

const box = (w, s, e, n) => areaPolygons({ bbox: [w, s, e, n] })[0];
const length = (lines) => lines.reduce((t, [[x1, y1], [x2, y2]]) => t + Math.abs(x2 - x1) + Math.abs(y2 - y1), 0);

// The same cases are in the phone's AreaOutlineTest, so the two draw alike.
describe("combined area outlines", () => {
  it("one box is its own outline", () => {
    expect(length(unionOutline([box(0, 0, 2, 1)]).lines)).toBe(6);
  });

  it("the lines inside an overlap are gone", () => {
    // Two 2×2 boxes overlapping by 1×1: the union's perimeter is 12, where
    // the two boxes drawn separately came to 16.
    expect(length(unionOutline([box(0, 0, 2, 2), box(1, 1, 3, 3)]).lines)).toBe(12);
  });

  it("a box inside another adds no line", () => {
    expect(length(unionOutline([box(0, 0, 4, 4), box(1, 1, 2, 2)]).lines)).toBe(16);
  });

  it("boxes that only touch share no seam", () => {
    expect(length(unionOutline([box(0, 0, 1, 1), box(1, 0, 2, 1)]).lines)).toBe(6);
  });

  it("separate boxes keep separate outlines", () => {
    expect(length(unionOutline([box(0, 0, 1, 1), box(3, 0, 4, 1)]).lines)).toBe(8);
  });

  it("the fill covers an overlap once", () => {
    const cells = unionOutline([box(0, 0, 2, 2), box(1, 1, 3, 3)]).cells;
    const area = cells.reduce((t, [r]) => t + (r[1][0] - r[0][0]) * (r[2][1] - r[1][1]), 0);
    expect(area).toBe(7);
  });
});
