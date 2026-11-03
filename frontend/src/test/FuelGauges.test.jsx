// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const PLAN = {
  targets: { carbs_g_per_h: 90, sodium_mg_per_h: 400, potassium_mg_per_h: 100, fluid_ml_per_h: 500, interval_min: 15 },
  ceilings: { carbs_g_per_h: 120, sodium_mg_per_h: 1500, potassium_mg_per_h: 375, fluid_ml_per_h: 1200 },
  timeline: {
    items: [
      { minute: 15, distance_m: 2500, product_uid: "homemade", name: "Homemade mix", carbs_g: 15, fluid_ml: 125 },
      { minute: 30, distance_m: 5000, product_uid: "g", name: "Gel", carbs_g: 25, fluid_ml: 0 },
    ],
    per_hour: { carbs_g: 80, sodium_mg: 380, potassium_mg: 95, fluid_ml: 470 },
  },
  gut_training: {},
  homemade: {
    preset: "basic", bottle_ml: 750, bottles: 4, concentration_pct: 12, sip_ml: 125, interval_min: 15,
    ingredients: [{ key: "sugar", name: "Table sugar", grams: 90, ml: null, measure: "7 tbsp" }],
    per_bottle: { carbs_g: 90, sodium_mg: 600, potassium_mg: 150 },
  },
};

vi.mock("../api/client", () => ({
  api: { getFuelPlan: () => Promise.resolve(PLAN), getFuelProducts: () => Promise.resolve([]) },
}));

import FuelSection, { GAUGES, HOMEMADE_UID, amountLabel, mixAdvice } from "../components/raceplan/FuelSection";

describe("the fuelling section", () => {
  it("has a gauge for carbs, sodium, potassium and fluid, each ticked at its target", async () => {
    const patch = vi.fn();
    render(<FuelSection goalId={1} plan={{ predicted_seconds: 3600, fuel_product_uids: [HOMEMADE_UID] }} onPatch={patch} />);
    await waitFor(() => expect(screen.getByTestId("fuel-gauges")).toBeTruthy());
    expect(GAUGES.map((g) => g.label)).toEqual(["Carbs", "Sodium", "Potassium", "Fluid"]);
    expect(screen.getAllByTestId("gauge-target")).toHaveLength(4);
    expect(screen.getByText("target 90")).toBeTruthy();
  });

  it("offers the homemade mix as a product and shows its recipe when carried", async () => {
    const patch = vi.fn();
    render(<FuelSection goalId={1} plan={{ predicted_seconds: 3600, fuel_product_uids: [HOMEMADE_UID] }} onPatch={patch} />);
    expect(await screen.findByText("Table sugar")).toBeTruthy();
    expect(screen.getByText("90 g (7 tbsp)")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Natural" }));
    expect(patch).toHaveBeenCalledWith({ fuel_mix_preset: "natural" });
  });

  it("folds the stops into a strip, and the list opens from it", async () => {
    render(<FuelSection goalId={1} plan={{ predicted_seconds: 3600, fuel_product_uids: [] }} onPatch={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Show all stops (2)" }));
    expect(screen.getByText("Gel")).toBeTruthy();
  });

  it("writes a liquid in ml and a solid in grams, with the spoon beside", () => {
    expect(amountLabel({ grams: 1.2, ml: null, measure: "¼ tsp" })).toBe("1.2 g (¼ tsp)");
    expect(amountLabel({ grams: null, ml: 240, measure: "1 cup" })).toBe("240 ml (1 cup)");
  });

  it("says how much food would bring a too-strong mix back within its heat band", () => {
    // The mix always meets the targets now; when that takes more than the
    // band's strength, the athlete is told what to eat alongside it.
    expect(mixAdvice({ sip_ml: 130, over_band: false })).toBeNull();
    expect(mixAdvice({ sip_ml: 130, over_band: true, concentration_pct: 19.4, band_pct: 6, extra_carbs_g_per_h: 52 }))
      .toBe("This mix is 19.4%, stronger than the 6% that absorbs well in these conditions. "
        + "About 52 g of carbs an hour from gels or food would bring it to 6%.");
    expect(mixAdvice({ sip_ml: 0, over_band: false, extra_carbs_g_per_h: 30 })).toMatch(/drinks already bring all the fluid/);
  });

  it("fills a new product in from the common-products list", async () => {
    render(<FuelSection goalId={1} plan={{ predicted_seconds: 3600, fuel_product_uids: [] }} onPatch={() => {}} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add product" }));
    fireEvent.change(screen.getByRole("textbox", { name: "Search products" }), { target: { value: "snick" } });
    fireEvent.click(screen.getByRole("option", { name: /Snickers/ }));
    expect(screen.getByDisplayValue("Snickers")).toBeTruthy();
    expect(screen.getByDisplayValue("32")).toBeTruthy();
  });
});
