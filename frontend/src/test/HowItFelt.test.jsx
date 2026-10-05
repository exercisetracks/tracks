// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { HowItFelt, feelLabel, effortLabel } from "../components/activity/cards/HowItFelt";

// The watch's post-workout prompts, stored scaled: feel 0–100 in quarters,
// effort ×10. Showing the raw numbers would read "75" and "60" for "Strong"
// and "6/10".
describe("HowItFelt", () => {
  it("reads feel as the watch's five words", () => {
    expect([0, 25, 50, 75, 100].map(feelLabel)).toEqual(["Very weak", "Weak", "Normal", "Strong", "Very strong"]);
  });

  it("reads effort out of ten with its band", () => {
    expect(effortLabel(60)).toEqual({ score: "6/10", band: "Moderate" });
    expect(effortLabel(100)).toEqual({ score: "10/10", band: "Maximum" });
  });

  it("shows nothing for an unanswered prompt", () => {
    // Zero effort is the watch's "skipped"; neither may surface as an answer.
    expect(effortLabel(0)).toBeNull();
    const { container } = render(<HowItFelt activity={{ workout_feel: null, workout_rpe: 0 }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows both answers when given", () => {
    render(<HowItFelt activity={{ workout_feel: 75, workout_rpe: 60 }} />);
    expect(screen.getByText("Strong")).toBeInTheDocument();
    expect(screen.getByText("Moderate")).toBeInTheDocument();
  });
});
