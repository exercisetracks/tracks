// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import {
  buildFlowPhases,
  timerReducer,
  initialTimerState,
} from '../components/player/usePlayerTimer';

describe('buildFlowPhases', () => {
  it('creates one phase per non-each-side stretch', () => {
    const phases = buildFlowPhases([
      { type: 'mobility_exercise', name: 'Child Pose', duration_seconds: 60, sets: 1, each_side: false },
    ]);
    expect(phases).toHaveLength(1);
    expect(phases[0]).toMatchObject({ name: 'Child Pose', side: null, seconds: 60 });
  });

  it('splits each-side stretches into left then right', () => {
    const phases = buildFlowPhases([
      { type: 'mobility_exercise', name: 'Pigeon Pose', duration_seconds: 45, sets: 1, each_side: true },
    ]);
    expect(phases.map(p => p.side)).toEqual(['left', 'right']);
  });

  it('repeats per set', () => {
    const phases = buildFlowPhases([
      { type: 'mobility_exercise', name: 'Cat-Cow', duration_seconds: 30, sets: 2, each_side: false },
    ]);
    expect(phases).toHaveLength(2);
  });

  it('carries breath cue, cues and position through', () => {
    const [p] = buildFlowPhases([
      { type: 'mobility_exercise', name: 'Low Lunge', duration_seconds: 40, sets: 1,
        breath_cue: 'Exhale down', cues: ['Tuck the tailbone'], position: 'kneeling' },
    ]);
    expect(p.breath_cue).toBe('Exhale down');
    expect(p.cues).toEqual(['Tuck the tailbone']);
    expect(p.position).toBe('kneeling');
  });
});

describe('timerReducer', () => {
  const phases = buildFlowPhases([
    { type: 'mobility_exercise', name: 'A', duration_seconds: 2, sets: 1, each_side: false },
    { type: 'mobility_exercise', name: 'B', duration_seconds: 3, sets: 1, each_side: false },
  ]);

  it('counts down within a phase', () => {
    let s = initialTimerState(phases);
    expect(s.remaining).toBe(2);
    s = timerReducer(s, { type: 'TICK' });
    expect(s.remaining).toBe(1);
    expect(s.index).toBe(0);
  });

  it('advances to the next phase when a phase elapses', () => {
    let s = initialTimerState(phases);
    s = timerReducer(s, { type: 'TICK' }); // 2 -> 1
    s = timerReducer(s, { type: 'TICK' }); // elapsed -> phase B
    expect(s.index).toBe(1);
    expect(s.remaining).toBe(3);
  });

  it('finishes after the last phase', () => {
    let s = initialTimerState(phases);
    for (let i = 0; i < 5; i++) s = timerReducer(s, { type: 'TICK' });
    expect(s.done).toBe(true);
    expect(s.running).toBe(false);
  });

  it('pause freezes the countdown', () => {
    let s = initialTimerState(phases);
    s = timerReducer(s, { type: 'TOGGLE' }); // pause
    const before = s.remaining;
    s = timerReducer(s, { type: 'TICK' });
    expect(s.remaining).toBe(before);
  });

  it('NEXT skips to the next phase, PREV goes back', () => {
    let s = initialTimerState(phases);
    s = timerReducer(s, { type: 'NEXT' });
    expect(s.index).toBe(1);
    s = timerReducer(s, { type: 'PREV' });
    expect(s.index).toBe(0);
    expect(s.remaining).toBe(2);
  });

  it('handles an empty phase list as immediately done', () => {
    const s = initialTimerState([]);
    expect(s.done).toBe(true);
  });
});
