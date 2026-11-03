// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

vi.mock("../api/client", () => ({ api: { getCourses: () => Promise.resolve([]), courseFromTrack: vi.fn() } }));

import TriCourses, { sourceLabel } from "../components/raceplan/TriCourses";

const plan = { triathlon_courses: {
  swim: { source: "whole", distance_m: 1600, path: [] },
  bike: { source: "own", distance_m: 41000, path: [] },
  run: { source: null, distance_m: null, path: [] },
} };

describe("a triathlon's courses, leg by leg", () => {
  it("says where each leg's course came from", () => {
    // Organisers publish a whole course, a file per leg, or both: which one
    // a leg is planned on has to be visible, not guessed.
    render(<MemoryRouter><TriCourses goalId={1} plan={plan} imperial={false} onUpload={() => {}}
      onPicked={() => {}} onRemove={() => {}} /></MemoryRouter>);
    expect(screen.getByText(/1\.6 km · from the whole course/)).toBeTruthy();
    expect(screen.getByText(/41\.0 km · own file/)).toBeTruthy();
    expect(sourceLabel(plan.triathlon_courses.run)).toBe("no course");
  });

  it("removes only a leg's own file", () => {
    const remove = vi.fn();
    render(<MemoryRouter><TriCourses goalId={1} plan={plan} imperial={false} onUpload={() => {}}
      onPicked={() => {}} onRemove={remove} /></MemoryRouter>);
    const buttons = screen.getAllByRole("button", { name: "Remove" });
    expect(buttons).toHaveLength(1);
    fireEvent.click(buttons[0]);
    expect(remove).toHaveBeenCalledWith("bike");
  });
});
