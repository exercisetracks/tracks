// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Food on the Health page: what the Eaten dial counts, and the Log today form
// that feeds it. Dates are anchored to today so nothing here ages out.
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";

vi.mock("../api/client", () => ({
  api: {
    patchDailyMetric: vi.fn(() => Promise.resolve({})),
    createMeal: vi.fn(() => Promise.resolve({ id: 42 })),
    logMeal: vi.fn(() => Promise.resolve({ id: 7 })),
    updateMeal: vi.fn(() => Promise.resolve({})),
    deleteMeal: vi.fn(() => Promise.resolve()),
    deleteMealLog: vi.fn(() => Promise.resolve()),
  },
}));

import { api } from "../api/client";
import { eatenSeries, loggedAtFor, grams, foodOn } from "../components/health/food";
import { localIso } from "../components/health/scales";
import LogTodayModal from "../components/health/LogTodayModal";
import MetricGaugeGroup from "../components/health/MetricGaugeGroup";
import { healthGroups } from "../components/health/metrics";

function daysAgo(n, hour = 12) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  d.setHours(hour, 0, 0, 0);
  return d;
}
const entry = (id, n, kcal, hour = 12) => ({ id, name: `food ${id}`, calories: kcal, logged_at: daysAgo(n, hour).toISOString() });

beforeEach(() => vi.clearAllMocks());

describe("what Eaten counts", () => {
  it("test_a_days_food_is_added_up", () => {
    const s = eatenSeries([], [entry(1, 0, 350), entry(2, 0, 520)]);
    expect(s).toEqual({ dates: [localIso()], values: [870] });
  });

  it("test_a_typed_total_from_before_food_was_logged_is_kept", () => {
    const old = localIso(daysAgo(3));
    expect(eatenSeries([{ date: old, calories_in: 2100 }], []).values).toEqual([2100]);
  });

  it("test_a_day_with_food_is_never_also_counted_from_its_typed_total", () => {
    // Both on one day would double it.
    const s = eatenSeries([{ date: localIso(), calories_in: 2100 }], [entry(1, 0, 400)]);
    expect(s.values).toEqual([400]);
  });

  it("test_food_before_the_window_is_left_out", () => {
    const s = eatenSeries([], [entry(1, 60, 500), entry(2, 0, 300)], localIso(daysAgo(30)));
    expect(s.dates).toEqual([localIso()]);
  });

  it("test_food_is_filed_under_the_local_day_it_was_eaten", () => {
    // A late dinner is a UTC stamp on the following date west of Greenwich.
    const late = entry(1, 1, 600, 23);
    expect(foodOn([late], localIso(daysAgo(1)))).toHaveLength(1);
  });

  it("test_food_logged_for_a_past_day_lands_at_its_midday", () => {
    const day = localIso(daysAgo(2));
    expect(localIso(new Date(loggedAtFor(day)))).toBe(day);
    expect(new Date(loggedAtFor(day)).getHours()).toBe(12);
  });

  it("test_a_blank_macro_is_null_not_zero", () => {
    expect(grams("")).toBeNull();
    expect(grams("12.5")).toBe(12.5);
  });

  it("test_the_eaten_dial_reads_the_food_log", () => {
    const { body } = healthGroups({ days: [], imperial: false, start: null, stress: [], food: [entry(1, 0, 640)] });
    expect(body.metrics.find(m => m.key === "calories_in").trend.values).toEqual([640]);
  });
});

describe("the Log today form", () => {
  const meals = [{ id: 1, name: "Porridge", calories: 350 }];
  const open = (props = {}) => render(
    <LogTodayModal days={[]} meals={meals} log={[]} imperial={false} onChanged={vi.fn()} onClose={vi.fn()} {...props} />,
  );

  it("test_save_says_how_many_things_it_will_record", () => {
    open();
    fireEvent.change(screen.getByLabelText("Weight (kg)"), { target: { value: "72" } });
    fireEvent.change(screen.getByLabelText("What did you eat?"), { target: { value: "Bagel" } });
    expect(screen.getByRole("button", { name: "Save 2 entries" })).toBeEnabled();
  });

  it("test_nothing_typed_means_nothing_to_save", () => {
    open();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("test_remember_this_meal_saves_it_and_logs_against_it", async () => {
    open();
    fireEvent.change(screen.getByLabelText("What did you eat?"), { target: { value: "Bagel" } });
    fireEvent.change(screen.getByLabelText("Calories"), { target: { value: "290" } });
    fireEvent.click(screen.getByLabelText("Remember this meal"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.logMeal).toHaveBeenCalled());
    expect(api.createMeal).toHaveBeenCalledWith(expect.objectContaining({ name: "Bagel", calories: 290 }));
    expect(api.logMeal).toHaveBeenCalledWith(expect.objectContaining({ meal_id: 42, calories: 290 }));
  });

  it("test_a_name_already_saved_is_not_saved_twice", async () => {
    // Two chips called "Porridge" would be a puzzle.
    open();
    fireEvent.change(screen.getByLabelText("What did you eat?"), { target: { value: "porridge" } });
    fireEvent.click(screen.getByLabelText("Remember this meal"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(api.logMeal).toHaveBeenCalled());
    expect(api.createMeal).not.toHaveBeenCalled();
    // ...and typing its name filled its calories.
    expect(api.logMeal).toHaveBeenCalledWith(expect.objectContaining({ meal_id: 1, calories: 350 }));
  });

  it("test_a_saved_meal_chip_logs_at_once", async () => {
    open();
    fireEvent.click(screen.getByRole("button", { name: "Log Porridge" }));
    await waitFor(() => expect(api.logMeal).toHaveBeenCalledWith(expect.objectContaining({ meal_id: 1, name: "Porridge" })));
  });

  it("test_under_manage_a_chip_edits_instead_of_logging", () => {
    open();
    fireEvent.click(screen.getByRole("button", { name: "Manage" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit Porridge" }));
    expect(api.logMeal).not.toHaveBeenCalled();
    expect(screen.getByLabelText("Meal name")).toHaveValue("Porridge");
  });

  it("test_a_glass_adds_to_the_days_water_rather_than_replacing_it", () => {
    open({ days: [{ date: localIso(), hydration_ml: 1000 }] });
    fireEvent.click(screen.getByRole("button", { name: "+250" }));
    expect(screen.getByLabelText("Water (ml)")).toHaveValue(1250);
  });
});

describe("the Body explanation", () => {
  it("test_body_explains_that_its_readings_expire_after_a_day", () => {
    const { body } = healthGroups({ days: [], imperial: false, start: null, stress: [], food: [] });
    render(<MetricGaugeGroup {...body} start={null} />);
    fireEvent.click(screen.getByRole("button", { name: "About Body" }));
    expect(screen.getByText(/for one day after the day it was logged/)).toBeInTheDocument();
    expect(screen.getByText(/Weight is the exception/)).toBeInTheDocument();
  });
});
