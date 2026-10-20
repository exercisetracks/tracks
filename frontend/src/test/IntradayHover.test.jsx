// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The heart-rate and stress charts answer a hover like every other history.
import { describe, it, expect } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { IntradayHistory } from "../components/health/StressHistory";
import { HEART_RATE_ZONES, localIso } from "../components/health/scales";

globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} };

function chart() {
  const today = localIso(new Date());
  return render(
    <IntradayHistory
      days={[{ date: today, points: [[600, 62], [720, 140]] }]}
      averages={{ dates: [], values: [] }} start={today}
      zones={HEART_RATE_ZONES} lo={30} hi={210} ticks={[30, 100, 210]} id="hr" unit="bpm"
      empty="none" label="Heart rate history chart" />,
  );
}

describe("the intraday history chart", () => {
  it("shows the reading under the pointer, with its time and unit", () => {
    // A chart that only draws is what the heart-rate history was: nothing to
    // hover, so a spike could be seen but never read.
    const { container } = chart();
    expect(screen.queryByText(/bpm/)).toBeNull();

    // Over the right-hand end, where the later reading is.
    fireEvent.mouseMove(container.firstChild.firstChild, { clientX: 630 });

    expect(screen.getByText(/bpm/).textContent).toContain("140");
  });

  it("clears when the pointer leaves", () => {
    const { container } = chart();
    fireEvent.mouseMove(container.firstChild.firstChild, { clientX: 630 });
    fireEvent.mouseLeave(container.firstChild.firstChild);

    expect(screen.queryByText(/bpm/)).toBeNull();
  });
});
