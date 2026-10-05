// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Guided-player timing core. The phase-building and state transitions are pure
// functions (reducer) so they can be unit-tested without React or real timers;
// the hook is a thin setInterval wrapper on top.
import { useEffect, useReducer, useRef } from "react";

// Turn a list of `mobility_exercise` steps into a flat list of timed phases.
// Each stretch becomes one phase, or two ("left"/"right") when `each_side`,
// and repeats per `sets`. A phase carries everything the player UI needs.
export function buildFlowPhases(steps) {
  const phases = [];
  (steps || []).forEach((step, stepIndex) => {
    if (step?.type && step.type !== "mobility_exercise") return;
    const sets = Math.max(1, step.sets || 1);
    const dur = step.duration_seconds || step.duration_per_side_sec || 45;
    const sides = step.each_side ? ["left", "right"] : [null];
    for (let set = 0; set < sets; set++) {
      for (const side of sides) {
        phases.push({
          stepIndex,
          name: step.name,
          side,
          setNumber: set + 1,
          totalSets: sets,
          seconds: dur,
          breath_cue: step.breath_cue || null,
          cues: step.cues || [],
          position: step.position || null,
          muscles: step.muscles || step.primary_muscles || [],
        });
      }
    }
  });
  return phases;
}

export const initialTimerState = (phases) => ({
  phases,
  index: 0,
  remaining: phases.length ? phases[0].seconds : 0,
  running: true,
  done: phases.length === 0,
});

// Pure transition function. Actions: TICK, TOGGLE, NEXT, PREV, RESET.
export function timerReducer(state, action) {
  const { phases, index } = state;
  switch (action.type) {
    case "TICK": {
      if (!state.running || state.done) return state;
      if (state.remaining > 1) {
        return { ...state, remaining: state.remaining - 1 };
      }
      // Phase elapsed — advance, or finish.
      if (index + 1 >= phases.length) {
        return { ...state, remaining: 0, running: false, done: true };
      }
      return { ...state, index: index + 1, remaining: phases[index + 1].seconds };
    }
    case "TOGGLE":
      return state.done ? state : { ...state, running: !state.running };
    case "NEXT": {
      if (index + 1 >= phases.length) {
        return { ...state, remaining: 0, running: false, done: true };
      }
      return { ...state, index: index + 1, remaining: phases[index + 1].seconds };
    }
    case "PREV": {
      const prev = Math.max(0, index - 1);
      return { ...state, index: prev, remaining: phases[prev].seconds, done: false };
    }
    case "RESET":
      return initialTimerState(action.phases ?? phases);
    default:
      return state;
  }
}

// React hook wrapping the reducer with a 1 Hz tick. `onComplete` fires once
// when the sequence finishes.
export function usePlayerTimer(phases, { onComplete } = {}) {
  const [state, dispatch] = useReducer(
    timerReducer, phases, initialTimerState,
  );
  const completedRef = useRef(false);

  // Re-seed when the phase list identity changes (new workout opened).
  useEffect(() => {
    completedRef.current = false;
    dispatch({ type: "RESET", phases });
  }, [phases]);

  useEffect(() => {
    if (!state.running || state.done) return undefined;
    const id = setInterval(() => dispatch({ type: "TICK" }), 1000);
    return () => clearInterval(id);
  }, [state.running, state.done]);

  useEffect(() => {
    if (state.done && !completedRef.current) {
      completedRef.current = true;
      onComplete?.();
    }
  }, [state.done, onComplete]);

  const phase = state.phases[state.index] || null;
  return {
    phase,
    index: state.index,
    total: state.phases.length,
    remaining: state.remaining,
    running: state.running,
    done: state.done,
    toggle: () => dispatch({ type: "TOGGLE" }),
    next: () => dispatch({ type: "NEXT" }),
    prev: () => dispatch({ type: "PREV" }),
  };
}

// Simple standalone countdown (used for rest timers in the strength runner).
export function useCountdown() {
  const [state, dispatch] = useReducer(
    (s, a) => {
      switch (a.type) {
        case "START":  return { seconds: a.seconds, running: true };
        case "TICK":   return s.seconds > 1 ? { ...s, seconds: s.seconds - 1 } : { seconds: 0, running: false };
        case "STOP":   return { seconds: 0, running: false };
        default:       return s;
      }
    },
    { seconds: 0, running: false },
  );

  useEffect(() => {
    if (!state.running) return undefined;
    const id = setInterval(() => dispatch({ type: "TICK" }), 1000);
    return () => clearInterval(id);
  }, [state.running]);

  return {
    seconds: state.seconds,
    running: state.running,
    start: (seconds) => dispatch({ type: "START", seconds }),
    stop: () => dispatch({ type: "STOP" }),
  };
}
