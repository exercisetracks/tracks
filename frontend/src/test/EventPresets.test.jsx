// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Every event preset, on the two things picking one does: the distance it
// fills and the name it writes — and the goal form around them (the
// recommended date, the strength slider, the subscription link that only an
// existing goal shows, no Regenerate button). The phone's GoalPresetsTest
// holds its copy of the table to the same rules.
//
// Written for: picking "10K" after "10 Mile" named the race "10 Mile",
// because the name was only filled while blank.
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { EVENT_PRESETS, presetName } from '../components/goals/constants';
import { changedFields, newDraft, pickPreset, pickSport } from '../components/goals/helpers';
import NewGoalForm from '../components/goals/NewGoalForm';
import PlanCalendarSection from '../components/PlanCalendarSection';
import { api } from '../api/client';

// The distance a preset's label names, in metres, or null when it names none.
function namedDistance(sport, label) {
  const m = label.match(/(\d+(?:\.\d+)?)\s*(K|km|m|Mile|mi)\b/);
  if (m) {
    const n = parseFloat(m[1]);
    return m[2] === 'K' || m[2] === 'km' ? n * 1000 : m[2] === 'm' ? n : n * 1609.34;
  }
  const words = [['Half Marathon', 21097.5], ['Metric Century', 100000], ['Double Century', 321869], ['Marathon', 42195]];
  const w = words.find(([k]) => label.includes(k));
  if (w) return w[1];
  if (sport === 'triathlon') {
    // The three legs summed.
    if (label.includes('Super Sprint')) return 400 + 10000 + 2500;
    if (label.includes('Sprint')) return 750 + 20000 + 5000;
    if (label.includes('Olympic')) return 1500 + 40000 + 10000;
    if (label.includes('70.3')) return 1900 + 90000 + 21100;
    if (label.includes('140.6')) return 3800 + 180000 + 42200;
  }
  return null;
}

const all = Object.entries(EVENT_PRESETS).flatMap(([sport, ps]) => ps.map(p => [sport, p]));

describe('event presets', () => {
  it.each(all.map(([s, p]) => [s, p.label, p]))('%s %s carries the distance its label names', (sport, _l, p) => {
    const named = namedDistance(sport, p.label);
    if (named == null) expect(p.distance).toBeNull();
    else expect(Math.abs(p.distance - named) / named).toBeLessThan(0.005);
  });

  it('fills the distance and name of any preset picked after any other', () => {
    for (const [sport, presets] of Object.entries(EVENT_PRESETS)) {
      for (const first of presets) for (const then of presets) {
        const d = pickPreset(pickPreset(pickSport(newDraft(), sport), first), then);
        expect(d.event_name, `${sport}: ${first.label} then ${then.label}`).toBe(presetName(then) ?? '');
        expect(d.event_distance_meters).toBe(then.distance);
      }
    }
  });

  it('makes a 10K after a 10 Mile a 10K', () => {
    const run = EVENT_PRESETS.running;
    const d = pickPreset(pickPreset(newDraft(), run.find(p => p.id === '10mile')), run.find(p => p.id === '10k'));
    expect(d.event_name).toBe('10K');
    expect(d.event_distance_meters).toBe(10000);
  });

  it('keeps a name somebody typed, and names an Olympic triathlon as one', () => {
    const half = EVENT_PRESETS.running.find(p => p.id === 'half');
    expect(pickPreset({ ...newDraft(), event_name: 'Lisbon Half' }, half).event_name).toBe('Lisbon Half');
    const oly = EVENT_PRESETS.triathlon.find(p => p.id === 'olympic');
    expect(pickPreset(pickSport(newDraft(), 'triathlon'), oly).event_name).toBe('Olympic Triathlon');
  });

  it('sends only the fields an edit changed', () => {
    expect(changedFields({ days_per_week: 4, notes: null, event_name: 'A' },
      { days_per_week: 5, notes: null, event_name: 'A' })).toEqual({ days_per_week: 4 });
  });
});

describe('the goal form', () => {
  beforeEach(() => vi.restoreAllMocks());

  const advice = { date: '2026-11-01', weeks: 4, basis: 'general', reasons: ['An event of about 30 min usually gets about 4 weeks of preparation.'] };

  it('starts a new event on the recommended date, with its reasons behind the "?"', async () => {
    const rec = vi.spyOn(api, 'recommendedEventDate').mockResolvedValue(advice);
    render(<NewGoalForm imperial={false} settings={{}} onCancel={() => {}} onSaved={() => {}} />);
    fireEvent.click(screen.getByText('5K'));
    await waitFor(() => expect(rec).toHaveBeenCalledWith('running', 5000));
    await waitFor(() => expect(screen.getByText(/Nov/)).toBeInTheDocument());
    expect(screen.queryByText(advice.reasons[0])).toBeNull();
  });

  it('says "Workout days per week" and has no subscription link on a new goal', () => {
    vi.spyOn(api, 'recommendedEventDate').mockResolvedValue(advice);
    render(<NewGoalForm imperial={false} settings={{}} onCancel={() => {}} onSaved={() => {}} />);
    expect(screen.getByText('Workout days per week')).toBeInTheDocument();
    expect(screen.queryByText('Copy subscription link')).toBeNull();
  });

  it('edits a goal with a strength slider, the subscription link, and a PATCH of what changed', async () => {
    vi.spyOn(api, 'recommendedEventDate').mockResolvedValue(advice);
    vi.spyOn(api, 'getUserIcsToken').mockResolvedValue({ ics_url: 'https://x/ics' });
    const update = vi.spyOn(api, 'updateGoal').mockResolvedValue({});
    const create = vi.spyOn(api, 'createGoal');
    const goal = {
      id: 4, goal_type: 'event', event_name: 'Half', event_sport: 'running', event_date: '2027-03-07',
      event_distance_meters: 21097, days_per_week: 4, plan_intensity: 1.0, include_strength: true, strength_tier: 2,
    };
    const onSaved = vi.fn();
    render(<NewGoalForm goal={goal} imperial={false} settings={{ strength_experience: 'intermediate' }}
      onCancel={() => {}} onSaved={onSaved} />);
    expect(await screen.findByText('Copy subscription link')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Strength focus'), { target: { value: '4' } });
    fireEvent.click(screen.getByText('Save goal'));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(create).not.toHaveBeenCalled();
    expect(update).toHaveBeenCalledWith(4, { strength_tier: 4 });
  });
});

describe('the plan section', () => {
  it('has no Regenerate button, and builds a missing plan by itself', async () => {
    vi.spyOn(api, 'getPlan').mockRejectedValue(Object.assign(new Error('nf'), { status: 404 }));
    const gen = vi.spyOn(api, 'generatePlan').mockResolvedValue({ workouts: [] });
    render(<PlanCalendarSection goalId={3} refreshKey={0} />);
    await waitFor(() => expect(gen).toHaveBeenCalledWith(3));
    expect(screen.queryByText(/Regenerate|Generate Plan/)).toBeNull();
  });
});
