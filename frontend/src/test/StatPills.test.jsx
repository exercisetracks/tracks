// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatPill, StatPillsStrip } from '../components/activity/pills/StatPills';

describe('StatPill', () => {
  it('renders value and label correctly', () => {
    render(<StatPill value="10:30" label="Duration" />);
    expect(screen.getByText('10:30')).toBeInTheDocument();
    expect(screen.getByText('Duration')).toBeInTheDocument();
  });

  it('renders with subtitle', () => {
    render(<StatPill value="5 km" label="Distance" sub="total" />);
    expect(screen.getByText('5 km')).toBeInTheDocument();
    expect(screen.getByText('total')).toBeInTheDocument();
  });

  it('returns null when value is null', () => {
    const { container } = render(<StatPill value={null} label="Test" />);
    expect(container.firstChild).toBeNull();
  });
});

describe('StatPillsStrip', () => {
  it('renders children correctly', () => {
    render(
      <StatPillsStrip>
        <StatPill value="1" label="One" />
        <StatPill value="2" label="Two" />
      </StatPillsStrip>
    );
    expect(screen.getByText('One')).toBeInTheDocument();
    expect(screen.getByText('Two')).toBeInTheDocument();
  });
});