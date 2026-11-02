// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import PaceTable from "../components/raceplan/PaceTable";

describe("the lap table", () => {
  it("renders its rows, striped", () => {
    // Removing its drink column once left the stripe's row index undefined,
    // and the whole race plan page failed to draw.
    const laps = [1, 2].map((n) => ({ lap: n, distance_m: 1000, target_sec_per_km: 300, target_pace: "5:00/km",
      cumulative_km: n, gradient: 0, grade_adj_pace: "5:00/km" }));
    const { container } = render(<PaceTable laps={laps} hasCourse={false} imperial={false} />);
    expect(container.querySelectorAll("tbody tr")).toHaveLength(2);
  });
});
