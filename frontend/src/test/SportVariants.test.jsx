// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Which kind of swimming or skiing a goal is. The kind is stored as the sport
// itself ("alpine_skiing"), because that is what the planner and the watch
// read; these pin that the form writes it and still shows the right chip.
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { EVENT_PRESETS, SPORT_LABEL, sportChip } from '../components/goals/constants';
import { SportChooser, SportVariantChooser } from '../components/goals/formControls';

describe('sport variants', () => {
  it('file each specific sport under its chip', () => {
    expect(sportChip('alpine_skiing')).toBe('skiing');
    expect(sportChip('backcountry_skiing')).toBe('skiing');
    expect(sportChip('open_water_swimming')).toBe('swimming');
    expect(sportChip('running')).toBe('running');
  });

  it('keep the Skiing chip lit for an alpine goal', () => {
    render(<SportChooser value="alpine_skiing" onChange={() => {}} />);
    expect(screen.getByText('Skiing').closest('button').className).toMatch(/bg-accent-500/);
  });

  it('write the specific sport, with the explanation behind the "?"', () => {
    const onChange = vi.fn();
    render(<SportVariantChooser sport="skiing" onChange={onChange} />);
    // A bare "skiing" goal is the first kind, cross-country.
    expect(screen.getByText('Cross-country').className).toMatch(/bg-accent-500/);
    expect(screen.queryByText(/dry-land conditioning/)).toBeNull();
    fireEvent.click(screen.getByText('Alpine'));
    expect(onChange).toHaveBeenCalledWith('alpine_skiing');
    fireEvent.mouseEnter(screen.getByText('?'));
    expect(screen.getByText(/dry-land conditioning/)).toBeInTheDocument();
  });

  it('are offered only for sports that have kinds', () => {
    const { container } = render(<SportVariantChooser sport="running" onChange={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('climbing', () => {
  it('is a goal sport with its own presets and label', () => {
    expect(Object.keys(EVENT_PRESETS)).toContain('climbing');
    expect(SPORT_LABEL.climbing).toBe('Climbing');
  });
});
