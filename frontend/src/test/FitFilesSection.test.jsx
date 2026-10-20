// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The Settings section for bulk FIT import/export. The folder picker is the
 * point of it — a multi-file pick of a Garmin export fails in the desktop's
 * dialog (lib/fitFiles/sources.js) — so the test pins that the folder input
 * really is one: React passes `webkitdirectory` through only when spelled as
 * a string attribute, and without it the button opens a plain file picker.
 */
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import FitFilesSection from "../components/settings/FitFilesSection";

describe("FitFilesSection", () => {
  it("offers a folder picker, a file picker and an export", () => {
    const { container } = render(<FitFilesSection />);
    expect(screen.getByRole("button", { name: "Import a folder" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Import files" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Export all files" })).toBeEnabled();
    const inputs = [...container.querySelectorAll('input[type="file"]')];
    expect(inputs.filter((i) => i.hasAttribute("webkitdirectory"))).toHaveLength(1);
    expect(inputs.find((i) => i.multiple).accept).toContain(".zip");
  });
});
