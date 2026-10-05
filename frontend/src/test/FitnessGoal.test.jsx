// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// The Fitness goal on the web: one type in place of "Build Fitness" (a target
// CTL nobody could name) and "Maintain" (a CTL range), set with a slider.
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { GOAL_TYPES } from '../components/goals/constants';
import { changedFields, draftFromGoal, hasPlan, rampColor, rampLabel, rampWord } from '../components/goals/helpers';
import { RampSlider } from '../components/goals/formControls';
import NewGoalForm from '../components/goals/NewGoalForm';
import { api } from '../api/client';

describe('goal types', () => {
  it('offers fitness and no longer asks for a CTL target or range', () => {
    const ids = GOAL_TYPES.map(g => g.id);
    expect(ids).toEqual(['event', 'fitness', 'volume_target']);
  });

  it('plans fitness goals and future events, not weekly volume', () => {
    expect(hasPlan({ goal_type: 'fitness' })).toBe(true);
    expect(hasPlan({ goal_type: 'event', event_date: '2027-05-01' })).toBe(true);
    expect(hasPlan({ goal_type: 'event', event_date: null })).toBe(false);
    expect(hasPlan({ goal_type: 'volume_target' })).toBe(false);
  });
});

describe('the ramp', () => {
  it('reads as a signed rate and a word, as on the phone', () => {
    expect(rampLabel(3)).toBe('+3 CTL / week');
    expect(rampLabel(-1.5)).toBe('−1.5 CTL / week');
    expect(rampLabel(0)).toBe('0 CTL / week');
    expect([-0.5, 0, 3, 3.5].map(rampWord)).toEqual(['Detraining', 'Maintain', 'Build', 'Aggressive']);
  });

  it('turns red only in the last stretch, past +3', () => {
    expect(rampColor(3)).not.toBe('#ef4444');
    expect(rampColor(3.5)).toBe('#ef4444');
  });

  it('keeps its explanation behind the "?" and reports half-point moves', () => {
    const onChange = vi.fn();
    render(<RampSlider value={2} onChange={onChange} />);
    expect(screen.getByText('+2 CTL / week · Build')).toBeInTheDocument();
    expect(screen.queryByText(/injury risk/)).toBeNull();
    fireEvent.mouseEnter(screen.getByText('?'));
    expect(screen.getByText(/injury risk/)).toBeInTheDocument();

    const slider = screen.getByLabelText('Fitness change per week');
    expect(slider).toHaveAttribute('min', '-2');
    // +6: the plan delivers it; past it the first week is about twice CTL.
    expect(slider).toHaveAttribute('max', '6');
    expect(slider).toHaveAttribute('step', '0.5');
    fireEvent.change(slider, { target: { value: '3.5' } });
    expect(onChange).toHaveBeenCalledWith(3.5);
  });
});

describe('creating a fitness goal', () => {
  it('sends the sport and ramp, then builds the plan', async () => {
    const created = { id: 9, goal_type: 'fitness', event_sport: 'running', ctl_ramp_per_week: 2 };
    const createGoal = vi.spyOn(api, 'createGoal').mockResolvedValue(created);
    const generatePlan = vi.spyOn(api, 'generatePlan').mockResolvedValue({});
    const onSaved = vi.fn();
    render(<NewGoalForm imperial={false} settings={{ strength_experience: 'intermediate' }}
      onCancel={() => {}} onSaved={onSaved} />);
    fireEvent.click(screen.getByText('Fitness'));
    fireEvent.click(screen.getByText('Save goal'));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    const payload = createGoal.mock.calls[0][0];
    expect(payload).toMatchObject({ goal_type: 'fitness', event_sport: 'running', ctl_ramp_per_week: 2 });
    expect(payload).not.toHaveProperty('target_ctl');
    expect(payload).not.toHaveProperty('event_date');
    expect(generatePlan).toHaveBeenCalledWith(9);
  });
});

describe('a fitness goal over several sports', () => {
  it('sends every sport picked, the first also as event_sport', async () => {
    const createGoal = vi.spyOn(api, 'createGoal').mockResolvedValue({ id: 10, goal_type: 'fitness' });
    vi.spyOn(api, 'generatePlan').mockResolvedValue({});
    const onSaved = vi.fn();
    render(<NewGoalForm imperial={false} settings={{ strength_experience: 'intermediate' }}
      onCancel={() => {}} onSaved={onSaved} />);
    fireEvent.click(screen.getByText('Fitness'));
    fireEvent.click(screen.getByText('Alpine Skiing'));
    fireEvent.click(screen.getByText('Climbing'));
    fireEvent.click(screen.getByText('Running'));   // unpicks it
    fireEvent.click(screen.getByText('Save goal'));
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalled());
    const payload = createGoal.mock.calls.at(-1)[0];
    expect(payload.fitness_sports).toEqual(['alpine_skiing', 'climbing']);
    expect(payload.event_sport).toBe('alpine_skiing');
  });

  it('keeps the last sport picked: a goal with none has nothing to plan', () => {
    render(<NewGoalForm imperial={false} settings={{ strength_experience: 'intermediate' }}
      onCancel={() => {}} onSaved={() => {}} />);
    fireEvent.click(screen.getByText('Fitness'));
    const running = screen.getByText('Running').closest('button');
    fireEvent.click(running);
    expect(running).toHaveAttribute('aria-pressed', 'true');
  });

  it('does not count an unchanged list of sports as an edit', () => {
    // A fresh array is never === the saved one; sent anyway, it would rebuild
    // the plan on every save.
    const goal = { goal_type: 'fitness', event_sport: 'running', fitness_sports: ['running', 'rowing'] };
    expect(changedFields({ fitness_sports: ['running', 'rowing'] }, goal)).toEqual({});
    expect(draftFromGoal({ goal_type: 'fitness', event_sport: 'hiking' }).fitness_sports).toEqual(['hiking']);
  });
});
