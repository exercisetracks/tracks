// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { clock, stopLabel } from "../components/raceplan/FuelSection";
import Button, { btn } from "../components/ui/Button";

describe("race fuelling timeline", () => {
  it("writes every stop on the h:mm race clock, including the first hour", () => {
    // "20 min" beside "1:00" made the time column two formats and two widths;
    // a race clock is what the watch counts in.
    expect(clock(20)).toBe("0:20");
    expect(clock(60)).toBe("1:00");
    expect(clock(125)).toBe("2:05");
  });

  it("adds fluid to a stop only when the product carries some", () => {
    expect(stopLabel({ name: "Gel", carbs_g: 25, fluid_ml: 0 })).toBe("Gel");
    expect(stopLabel({ name: "Drink mix", carbs_g: 40, fluid_ml: 150 })).toBe("Drink mix + 150 ml");
  });

  it("falls back to grams when no product is carried", () => {
    expect(stopLabel({ name: null, carbs_g: 25, fluid_ml: 0 })).toBe("25 g carbs");
  });
});

describe("the button family", () => {
  it("names a variant and a size, and nothing else", () => {
    expect(btn("primary")).toBe("btn btn-primary");
    expect(btn("danger", { small: true, className: "w-full" })).toBe("btn btn-danger btn-sm w-full");
  });

  it("falls back to tonal for an unknown variant rather than drawing an unstyled button", () => {
    expect(btn("outlined")).toBe("btn btn-tonal");
  });

  it("is a plain button, so it never submits a surrounding form by accident", () => {
    const { container } = render(<Button variant="neutral">Cancel</Button>);
    expect(container.querySelector("button").getAttribute("type")).toBe("button");
  });
});
