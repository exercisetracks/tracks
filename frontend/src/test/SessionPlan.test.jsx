// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, act, fireEvent } from "@testing-library/react";
import { planStrength, afterSetDone, planFlow, prescribedWeightKg } from "../lib/sessionPlan";
import { buildFlowPhases } from "../components/player/usePlayerTimer";
import FlowPlayer from "../components/player/FlowPlayer";
import StrengthRunner from "../components/player/StrengthRunner";
import { api } from "../api/client";

// Saved workouts and flows started from the desktop library. The rules are
// the phone's (SessionLogic.plan / afterSetDone, FlowPlan), so a template
// runs the same on both.

const LIB = {
  "Squat": { name: "Squat", cues: ["Brace"], primary_muscles: ["quads"] },
  "Bench Press": { name: "Bench Press", cues: [], primary_muscles: ["chest"] },
  "Row": { name: "Row", cues: [], primary_muscles: ["lats"] },
};

const row = (name, extra = {}) => ({
  exercise_name: name, target_sets: 3, target_reps: 5, rest_seconds: 120,
  weight_method: "rpe", weight_value: null, order_index: 0, ...extra,
});

describe("planStrength", () => {
  it("follows the template's order, sets, reps and rest", () => {
    const [a, b] = planStrength([row("Bench Press", { order_index: 1 }), row("Squat", { target_reps: 8 })], { library: LIB });
    expect(a).toMatchObject({ name: "Squat", sets: 3, reps: 8, rest_seconds: 120, cues: ["Brace"] });
    expect(b.name).toBe("Bench Press");
  });

  it("skips an exercise the library no longer has", () => {
    // A deleted custom exercise; logging it would resurrect its strength row.
    const plan = planStrength([row("Squat"), row("Gone", { order_index: 1 })], { library: LIB });
    expect(plan.map(e => e.name)).toEqual(["Squat"]);
  });

  it("adds a rest block to the rest after the exercise before it", () => {
    const plan = planStrength([
      row("Squat"),
      { item_kind: "rest", rest_seconds: 180, order_index: 1 },
      row("Row", { order_index: 2 }),
    ], { library: LIB });
    expect(plan).toHaveLength(2);
    expect(plan[0].rest_after_seconds).toBe(180);
  });

  it("gives a group member one set per round", () => {
    const g = { group_uid: "g1", group_kind: "superset", group_rounds: 4, group_rest_seconds: 90 };
    const plan = planStrength([row("Squat", g), row("Row", { ...g, order_index: 1 })], { library: LIB });
    expect(plan.map(e => e.sets)).toEqual([4, 4]);
    expect(plan[0].group).toMatchObject({ uid: "g1", kind: "superset", rest: 90 });
  });

  it("reads a planned session's steps, rest steps included", () => {
    // A planned session made from a saved workout carries type "rest" steps;
    // the calendar's runner used to drop them.
    const plan = planStrength([
      { type: "strength_exercise", name: "Squat", sets: 2, reps: 5, weight_kg: 100, rest_seconds: 60 },
      { type: "rest", rest_seconds: 120 },
      { type: "mobility_exercise", name: "Pigeon" },
    ]);
    expect(plan).toHaveLength(1);
    expect(plan[0]).toMatchObject({ weight_kg: 100, sets: 2, rest_after_seconds: 120 });
  });
});

describe("prescribedWeightKg", () => {
  const standing = { last_weight_kg: 80, estimated_1rm_kg: 117 };
  it("uses a fixed weight as written", () => {
    expect(prescribedWeightKg({ weight_method: "fixed", weight_value: 60 }, standing)).toBe(60);
  });
  it("takes a percentage of e1RM to the nearest half kilo", () => {
    expect(prescribedWeightKg({ weight_method: "percentage_e1rm", weight_value: 0.75 }, standing)).toBe(88);
  });
  it("falls back to the last weight with no e1RM to take a percentage of", () => {
    expect(prescribedWeightKg({ weight_method: "percentage_e1rm", weight_value: 0.75 }, { last_weight_kg: 40 })).toBe(40);
    expect(prescribedWeightKg({ weight_method: "rpe" }, standing)).toBe(80);
    expect(prescribedWeightKg({ weight_method: "rpe" }, undefined)).toBe(0);
  });
});

describe("afterSetDone", () => {
  const g = { uid: "g", kind: "superset", rounds: 2, rest: 90 };
  const ss = [
    { name: "A", sets: 2, rest_seconds: 0, rest_after_seconds: 0, group: g },
    { name: "B", sets: 2, rest_seconds: 0, rest_after_seconds: 30, group: g },
    { name: "C", sets: 3, rest_seconds: 60, rest_after_seconds: 0, group: null },
  ];

  it("goes straight to the next member of a superset with no rest", () => {
    expect(afterSetDone(ss, 0, 0, () => false)).toEqual({ moveTo: 1, rest: 0 });
  });

  it("returns to the first member after a round, resting the group's rest", () => {
    expect(afterSetDone(ss, 1, 0, (i, s) => i === 0 && s === 0)).toEqual({ moveTo: 0, rest: 90 });
  });

  it("after the last round rests for the block that follows and stays put", () => {
    expect(afterSetDone(ss, 1, 1, () => true)).toEqual({ moveTo: null, rest: 30 });
  });

  it("rests between sets of a lone exercise", () => {
    expect(afterSetDone(ss, 2, 0, () => false)).toEqual({ moveTo: null, rest: 60 });
    expect(afterSetDone(ss, 2, 2, () => false)).toEqual({ moveTo: null, rest: 0 });
  });
});

const STRETCHES = {
  "Pigeon": { name: "Pigeon", each_side: true, duration_per_side_sec: 45, primary_muscles: ["glutes"], breath_cue: "Breathe" },
  "Child Pose": { name: "Child Pose", each_side: false, duration_per_side_sec: 60 },
};

describe("planFlow", () => {
  it("takes the flow's own duration, sides from the library", () => {
    const [s] = planFlow([{ exercise_name: "Pigeon", duration_seconds: 30, sets: 1, rest_seconds: 5 }], STRETCHES);
    expect(s).toMatchObject({ name: "Pigeon", duration_seconds: 30, each_side: true, rest_seconds: 5, breath_cue: "Breathe" });
  });

  it("unrolls a group once per round with the group's rest after each round", () => {
    const g = { group_uid: "g", group_kind: "repeat", group_rounds: 2, group_rest_seconds: 20 };
    const steps = planFlow([
      { exercise_name: "Child Pose", rest_seconds: 10, ...g, order_index: 0 },
      { exercise_name: "Pigeon", rest_seconds: 10, ...g, order_index: 1 },
    ], STRETCHES);
    expect(steps.map(s => s.name)).toEqual(["Child Pose", "Pigeon", "Child Pose", "Pigeon"]);
    // Inside a group the next member follows at once.
    expect(steps.map(s => s.rest_seconds)).toEqual([0, 0, 0, 0]);
    expect(steps.map(s => s.rest_after_seconds)).toEqual([0, 20, 0, 20]);
  });

  it("adds a rest block after the hold before it", () => {
    const steps = planFlow([
      { exercise_name: "Child Pose", rest_seconds: 0, order_index: 0 },
      { item_kind: "rest", duration_seconds: 30, order_index: 1 },
      { exercise_name: "Child Pose", rest_seconds: 0, order_index: 2 },
    ], STRETCHES);
    expect(steps).toHaveLength(2);
    expect(steps[0].rest_after_seconds).toBe(30);
  });
});

describe("buildFlowPhases with rests", () => {
  it("rests after each side and drops a rest at the very end", () => {
    const phases = buildFlowPhases(planFlow([{ exercise_name: "Pigeon", rest_seconds: 5, sets: 1 }], STRETCHES));
    expect(phases.map(p => p.rest ? "rest" : p.side)).toEqual(["left", "rest", "right"]);
  });

  it("numbers holds without counting rests", () => {
    const phases = buildFlowPhases(planFlow([
      { exercise_name: "Child Pose", rest_seconds: 5, order_index: 0 },
      { exercise_name: "Child Pose", rest_seconds: 5, order_index: 1 },
    ], STRETCHES));
    expect(phases.map(p => p.hold)).toEqual([0, 1, 1]);
  });
});

describe("FlowPlayer", () => {
  afterEach(() => vi.useRealTimers());

  it("keeps counting down across renders instead of restarting", () => {
    // The phase list was rebuilt every render, and the timer restarts on a new
    // list — so the countdown never got past its first second.
    vi.useFakeTimers();
    const steps = [{ type: "mobility_exercise", name: "Child Pose", duration_seconds: 10 }];
    render(<FlowPlayer title="Test" steps={steps} onClose={() => {}} />);
    act(() => { vi.advanceTimersByTime(3000); });
    expect(screen.getByText("7")).toBeInTheDocument();
  });

  it("logs a library flow with its effort when finished", async () => {
    const onLog = vi.fn().mockResolvedValue({});
    const steps = [{ type: "mobility_exercise", name: "Child Pose", duration_seconds: 10 }];
    render(<FlowPlayer title="Test" steps={steps} onClose={() => {}} onLog={onLog} />);
    fireEvent.click(screen.getByText("Skip"));
    fireEvent.click(screen.getByText("6"));
    await act(async () => { fireEvent.click(screen.getByText("Log session")); });
    expect(onLog).toHaveBeenCalledWith(6);
    expect(screen.getByText("Flow logged — nicely done.")).toBeInTheDocument();
  });
});

describe("StrengthRunner", () => {
  it("logs a saved workout against its template, with only the sets done", async () => {
    const spy = vi.spyOn(api, "logWorkoutSession").mockResolvedValue({});
    const exercises = planStrength([row("Squat", { target_sets: 2, weight_method: "fixed", weight_value: 60 })], { library: LIB });
    render(<StrengthRunner title="Legs" exercises={exercises} workoutId={7} onClose={() => {}} />);
    // Nothing ticked: there is nothing to log yet.
    expect(screen.getByText("Finish session")).toBeDisabled();
    fireEvent.click(screen.getAllByLabelText("Mark set done")[0]);
    fireEvent.click(screen.getByText("Finish session"));
    await act(async () => { fireEvent.click(screen.getByText("Finish")); });
    expect(spy).toHaveBeenCalledWith(expect.objectContaining({
      workout_id: 7, planned_workout_id: null,
      exercises: [{ exercise_name: "Squat", sets: [{ weight_kg: 60, reps: 5, rpe: null }] }],
    }));
    spy.mockRestore();
  });
});
