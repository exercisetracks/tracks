// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Marking a goal done. Before, a goal could only be paused, and a race
 * already run sat among the paused ones as if it were still to come.
 */
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

vi.mock("../api/client", () => ({ api: { getPredictedTime: vi.fn(() => Promise.resolve(null)) } }));

import GoalCard from "../components/goals/GoalCard";

const goal = { id: 7, goal_type: "volume_target", is_active: true, target_weekly_km: 30, volume_sport: "running" };

function card(g, onComplete = vi.fn()) {
  render(
    <MemoryRouter>
      <GoalCard goal={g} imperial={false} onActivate={() => {}} onComplete={onComplete}
        onDelete={() => {}} onReload={() => {}} />
    </MemoryRouter>,
  );
  return onComplete;
}

describe("completing a goal", () => {
  it("is a button on a goal still being worked toward", () => {
    const onComplete = card(goal);
    fireEvent.click(screen.getByRole("button", { name: "Complete" }));
    expect(onComplete).toHaveBeenCalledWith(goal);
  });

  it("shows a done goal as completed, with nothing left to complete", () => {
    card({ ...goal, is_active: false, completed_at: "2026-11-08" });
    expect(screen.getByText(/Completed ·/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Complete" })).toBeNull();
  });
});
