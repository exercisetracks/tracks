// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * The race plan's intensity slider: how hard to race against the
 * prediction. It is drawn as the training plan's intensity slider is, so the
 * two read as one control; the phone's RaceIntensitySlider says the same words.
 */
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { RaceIntensitySlider } from "../components/goals/formControls";
import { intensityColor, raceIntensityColor, raceIntensityLabel } from "../components/goals/helpers";

describe("the race intensity slider", () => {
  it("says the prediction plainly, and how far either side of it", () => {
    expect(raceIntensityLabel(1)).toBe("Predicted");
    expect(raceIntensityLabel(1.04)).toBe("Predicted (+4%)");
    expect(raceIntensityLabel(1.05)).toBe("Hard (+5%)");
    expect(raceIntensityLabel(0.95)).toBe("Steady (−5%)");
    expect(raceIntensityLabel(1.1)).toBe("Hard (+10%)");
    expect(raceIntensityLabel(1.15)).toBe("All out (+15%)");
    expect(raceIntensityLabel(0.8)).toBe("Easy (−20%)");
    expect(raceIntensityLabel(1.2)).toBe("All out (+20%)");
  });

  it("is coloured from the training plan's track, ends to ends", () => {
    expect(raceIntensityColor(0.8)).toBe(intensityColor(0.5));
    expect(raceIntensityColor(1)).toBe(intensityColor(1));
    expect(raceIntensityColor(1.2)).toBe(intensityColor(1.5));
  });

  it("moves a hundredth at a time between a fifth either way", () => {
    const onChange = vi.fn();
    render(<RaceIntensitySlider value={1} onChange={onChange} />);
    const input = screen.getByLabelText("Race intensity");
    expect([input.min, input.max, input.step]).toEqual(["0.8", "1.2", "0.01"]);
    fireEvent.change(input, { target: { value: "1.03" } });
    expect(onChange).toHaveBeenCalledWith(1.03);
    for (const stop of ["Easy", "Steady", "Predicted", "Hard", "All out"]) {
      expect(screen.getAllByText(stop).length).toBeGreaterThan(0);
    }
  });
});
