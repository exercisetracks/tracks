// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

vi.mock("../api/client", () => ({
  api: { getCourses: () => Promise.resolve([{ id: 7, name: "Lake loop" }]), courseFromTrack: vi.fn() },
}));

import CourseMenu, { GPX_ACCEPT } from "../components/raceplan/CourseMenu";

const menu = (hasCourse) => render(
  <MemoryRouter><CourseMenu goalId={1} hasCourse={hasCourse} onUpload={() => {}} onPicked={() => {}} /></MemoryRouter>,
);

describe("the race course menu", () => {
  it("is one button, named for whether a course is loaded", () => {
    menu(false);
    expect(screen.getByRole("button", { name: "Add course" })).toBeTruthy();
    expect(screen.queryByText("Draw on map")).toBeNull();
  });

  it("puts upload, drawing and the saved courses behind Replace course", async () => {
    menu(true);
    fireEvent.click(screen.getByRole("button", { name: "Replace course" }));
    expect(screen.getByText("Upload GPX file")).toBeTruthy();
    expect(screen.getByText("Draw on map")).toBeTruthy();
    expect(await screen.findByText("Lake loop")).toBeTruthy();
  });

  it("offers only GPX files to pick", () => {
    // The picker offered every file, and a PDF went to the server's parse.
    menu(false);
    expect(screen.getByTestId("gpx-input").getAttribute("accept")).toBe(GPX_ACCEPT);
    expect(GPX_ACCEPT.split(",")).toContain(".gpx");
  });
});
