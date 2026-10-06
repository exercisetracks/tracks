// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The rules behind the Health page's dials and sleep chart. Every date here is
// anchored to today: a test that hardcodes one starts failing the day it stops
// being recent, and freshness is exactly the thing being tested.
import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import {
  zonesFor, stackedZones, isFresh, currentOf, overflowOf, series, localIso,
  STEP_GOAL,
} from "../components/health/scales";
import {
  nightClock, clockScale, clockLabel, laneOf, sleepNights, hoursMinutes,
} from "../components/health/sleepClock";
import { healthGroups, sleepMetric, caloriesMetric } from "../components/health/metrics";
import { dailyRows } from "../components/health/HistoryCharts";
import { stressSamples } from "../components/health/StressHistory";
import { ScaleStrip } from "../components/health/HistoryModal";
import MetricGaugeGroup from "../components/health/MetricGaugeGroup";
import { isoToday } from "../components/health/helpers";

// jsdom has none, and Recharts' ResponsiveContainer will not mount without
// one. Nothing here measures anything; the dialog's text is what is checked.
globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} };

function daysAgo(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return localIso(d);
}

describe("what a dial may claim is current", () => {
  it("test_a_reading_from_last_night_is_still_current", () => {
    // At nine in the morning, last night's figure is the current answer.
    expect(isFresh(daysAgo(1), 1)).toBe(true);
  });

  it("test_a_reading_from_a_week_ago_is_not_current", () => {
    // The SpO₂ failure: a week-old figure shown in colour under "Normal".
    expect(isFresh(daysAgo(7), 1)).toBe(false);
    expect(currentOf({ dates: [daysAgo(7)], values: [96] }, 1)).toBeNull();
  });

  it("test_weight_never_expires", () => {
    expect(isFresh(daysAgo(90), null)).toBe(true);
  });

  it("test_a_date_ahead_of_the_browser_clock_is_not_stale", () => {
    expect(isFresh(daysAgo(-1), 1)).toBe(true);
  });

  it("test_today_is_the_local_date_not_the_utc_one", () => {
    // isoToday used to be toISOString().slice(0, 10), which files an evening
    // entry west of Greenwich under tomorrow.
    // Pinned to half past eleven at night, local time, where the UTC date has
    // already moved on for anyone west of Greenwich.
    vi.useFakeTimers();
    try {
      const late = new Date();
      late.setHours(23, 30, 0, 0);
      vi.setSystemTime(late);
      const local = `${late.getFullYear()}-${String(late.getMonth() + 1).padStart(2, "0")}-${String(late.getDate()).padStart(2, "0")}`;
      expect(isoToday()).toBe(local);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("the scales", () => {
  it("test_a_goal_scale_runs_past_the_goal_so_beating_it_shows", () => {
    const z = zonesFor({ kind: "goal", target: STEP_GOAL, color: "#0f0" });
    expect(z.map(b => b.label)).toEqual(["Building", "Nearly", "Goal met"]);
    expect(z[2].min).toBe(STEP_GOAL);
  });

  it("test_a_personal_scale_with_one_reading_does_not_claim_an_average", () => {
    // "Above average" of a single reading compared with itself is nonsense.
    const z = zonesFor({ kind: "personal", color: "#00f" }, [72]);
    expect(z).toHaveLength(1);
    expect(z[0].label).toBe("Logged");
  });

  it("test_a_personal_scale_splits_at_the_windows_own_average", () => {
    const z = zonesFor({ kind: "personal", color: "#00f" }, [60, 70, 80]);
    expect(z[0].max).toBe(70);
    expect(z[1].label).toBe("Above avg");
  });

  it("test_a_flat_personal_window_still_has_an_arc_with_extent", () => {
    const z = zonesFor({ kind: "personal", color: "#00f" }, [70, 70]);
    expect(z[z.length - 1].max).toBeGreaterThan(z[0].min);
  });

  it("test_a_stacked_arc_drops_empty_parts_and_keeps_the_gap_to_goal", () => {
    // A night with no REM must not put "REM" on the arc.
    const z = stackedZones({
      parts: [{ label: "Deep", amount: 1, color: "a" }, { label: "REM", amount: 0, color: "b" }],
      target: 8,
    });
    expect(z.map(b => b.label)).toEqual(["Deep", "To goal"]);
    expect(z[1]).toMatchObject({ min: 1, max: 8 });
  });

  it("test_an_empty_stacked_arc_is_one_band_rather_than_nothing", () => {
    expect(stackedZones({ parts: [], target: 8 })).toHaveLength(1);
  });

  it("test_the_arc_laps_only_past_the_top_and_stops_at_one_lap", () => {
    expect(overflowOf(90, 0, 100)).toBeNull();
    expect(overflowOf(130, 0, 100)).toBe(30);
    expect(overflowOf(500, 0, 100)).toBe(100);
  });

  it("test_a_series_skips_days_without_a_reading_rather_than_zeroing_them", () => {
    const s = series([{ date: "a", v: 1 }, { date: "b", v: null }, { date: "c", v: 0 }], d => d.v);
    expect(s).toEqual({ dates: ["a", "c"], values: [1, 0] });
  });
});

describe("the sleep clock", () => {
  const today = daysAgo(0);
  const at = (dayOffset, h, m = 0) => {
    const d = new Date();
    d.setDate(d.getDate() + dayOffset);
    d.setHours(h, m, 0, 0);
    return d.toISOString();
  };

  it("test_a_bedtime_before_midnight_is_negative_hours", () => {
    const c = nightClock({ date: today, startAt: at(-1, 22, 30), endAt: at(0, 6, 45) });
    expect(c.from).toBeCloseTo(-1.5);
    expect(c.to).toBeCloseTo(6.75);
  });

  it("test_a_night_without_times_or_running_backwards_cannot_be_placed", () => {
    expect(nightClock({ date: today, startAt: null, endAt: null })).toBeNull();
    expect(nightClock({ date: today, startAt: at(0, 7), endAt: at(0, 6) })).toBeNull();
  });

  it("test_the_clock_axis_steps_on_hours_people_count_in", () => {
    // A nine-hour span on a quantity axis got a 2.5-hour step and every
    // other label at half past.
    const s = clockScale(-2, 7);
    expect(s.ticks.every(t => Number.isInteger(t))).toBe(true);
    expect(s.min).toBeLessThanOrEqual(-2);
    expect(s.max).toBeGreaterThanOrEqual(7);
  });

  it("test_a_window_of_identical_nights_still_has_an_axis", () => {
    const s = clockScale(0, 0);
    expect(s.max).toBeGreaterThan(s.min);
  });

  it("test_labels_wrap_only_when_written", () => {
    expect(clockLabel(-1.5)).toBe("10:30 PM");
    expect(clockLabel(7)).toBe("7 AM");
    expect(clockLabel(0)).toBe("12 AM");
  });

  it("test_stage_levels_land_in_the_right_lane_and_unknowns_in_awake", () => {
    expect(laneOf("deep")).toBe(3);
    expect(laneOf("sleep_light")).toBe(2);
    expect(laneOf("rem")).toBe(1);
    expect(laneOf("unmeasurable")).toBe(0);
    expect(laneOf("mystery")).toBe(0);
  });

  it("test_waking_falls_back_to_what_the_stages_leave_over", () => {
    // Nights recorded before awake time was kept.
    const [n] = sleepNights([{ date: today, sleep_hours: 7, sleep_deep_hours: 1, sleep_rem_hours: 1, sleep_light_hours: 4.5 }]);
    expect(n.restless).toBeCloseTo(0.5);
  });

  it("test_a_zero_length_night_is_not_a_night", () => {
    expect(sleepNights([{ date: today, sleep_hours: 0 }])).toHaveLength(0);
  });

  it("test_hours_are_written_as_hours_and_minutes", () => {
    expect(hoursMinutes(7.4)).toBe("7h 24m");
    expect(hoursMinutes(0.5)).toBe("30m");
  });
});

describe("the dials", () => {
  it("test_the_sleep_arc_shows_stages_only_for_last_night", () => {
    const fresh = sleepMetric([{ date: daysAgo(0), sleep_hours: 7, sleep_deep_hours: 1, sleep_rem_hours: 1.5, sleep_light_hours: 4.5 }]);
    expect(fresh.scale.parts.map(p => p.label)).toEqual(["Deep", "REM", "Light"]);
    // A week-old night painted on the ring under an em dash is the stale
    // reading problem in a different shape.
    const stale = sleepMetric([{ date: daysAgo(7), sleep_hours: 7, sleep_deep_hours: 1 }]);
    expect(stale.scale.parts).toEqual([]);
  });

  it("test_an_unstaged_night_still_fills_the_arc", () => {
    const m = sleepMetric([{ date: daysAgo(0), sleep_hours: 6 }]);
    expect(m.scale.parts.find(p => p.label === "Asleep").amount).toBe(6);
  });

  it("test_calories_are_the_total_with_resting_and_earned_as_parts", () => {
    const m = caloriesMetric([{ date: daysAgo(0), active_calories: 300, resting_calories: 1600 }]);
    expect(m.trend.values).toEqual([1900]);
    expect(m.scale.kind).toBe("stacked");
    expect(m.caption).toBe("+300 active");
  });

  it("test_weight_is_converted_for_imperial_and_never_expires", () => {
    const { body } = healthGroups({ days: [{ date: daysAgo(40), weight_kg: 100 }], imperial: true, start: null, stress: [] });
    const weight = body.metrics.find(m => m.key === "weight");
    expect(weight.trend.values[0]).toBeCloseTo(220.462);
    expect(weight.freshDays).toBeNull();
  });

  it("test_a_metric_with_no_readings_keeps_its_dial_and_cannot_be_opened", () => {
    // A tile that disappears makes a gap in the data look like a bug.
    const { vitals } = healthGroups({ days: [{ date: daysAgo(0), resting_hr: 52 }], imperial: false, start: null, stress: [] });
    render(<MetricGaugeGroup {...vitals} start={null} columns={6} />);
    expect(screen.getByRole("button", { name: "Blood oxygen: open history" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Resting heart rate: open history" })).toBeEnabled();
  });

  it("test_a_stale_dial_shows_a_dash_but_still_opens_its_history", () => {
    const { vitals } = healthGroups({ days: [{ date: daysAgo(6), spo2: 96 }], imperial: false, start: null, stress: [] });
    render(<MetricGaugeGroup {...vitals} start={null} columns={6} />);
    const spo2 = screen.getByRole("button", { name: "Blood oxygen: open history" });
    expect(spo2).toHaveTextContent("—");
    expect(spo2).not.toHaveTextContent("Normal");
    fireEvent.click(spo2);
    const dialog = screen.getByRole("dialog", { name: "Blood oxygen history" });
    // The real reading, dated, without the verdict that has expired.
    expect(dialog).toHaveTextContent("96");
    expect(dialog).toHaveTextContent(/last on/);
  });
});

describe("the history charts", () => {
  it("test_a_missing_day_is_a_null_row_so_the_line_breaks", () => {
    const rows = dailyRows(daysAgo(3), { v: { dates: [daysAgo(3), daysAgo(1)], values: [1, 2] } });
    expect(rows.map(r => r.v)).toEqual([1, null, 2, null]);
  });

  it("test_the_axis_runs_to_today_not_to_the_last_reading", () => {
    const rows = dailyRows(null, { v: { dates: [daysAgo(5)], values: [1] } });
    expect(rows[rows.length - 1].date).toBe(daysAgo(0));
  });

  it("test_todays_stress_readings_are_not_pressed_against_the_right_edge", () => {
    // The axis runs to the end of today, so noon today sits half a day in
    // from the edge rather than on it.
    const { samples } = stressSamples({
      days: [{ date: daysAgo(0), points: [[720, 40]] }],
      averages: { dates: [], values: [] },
      start: daysAgo(1),
    });
    expect(samples[0].x).toBeCloseTo(1.5 / 2);
  });

  it("test_long_windows_fall_back_to_daily_averages_at_midday", () => {
    const { intraday, samples } = stressSamples({
      days: [],
      averages: { dates: [daysAgo(1)], values: [30] },
      start: daysAgo(1),
    });
    expect(intraday).toBe(false);
    expect(samples[0].x).toBeCloseTo(0.5 / 2);
  });

  it("test_scale_strip_boundaries_that_would_collide_are_dropped_but_the_ends_stay", () => {
    const zones = [
      { label: "A", min: 0, max: 1650, color: "#000" },
      { label: "B", min: 1650, max: 2049, color: "#111" },
      { label: "C", min: 2049, max: 2150, color: "#222" },
    ];
    render(<ScaleStrip zones={zones} value={2000} decimals={0} />);
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("2,150")).toBeInTheDocument();
    expect(screen.queryByText("2,049")).toBeNull();
  });
});
