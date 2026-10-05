// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { WeightDisplay, WeightPill, WeightRange } from '../components/activity/pills/WeightDisplay';

describe('WeightDisplay', () => {
  it('displays dash when no kg provided', () => {
    render(<WeightDisplay kg={null} />);
    expect(screen.getByText('Bodyweight')).toBeInTheDocument();
  });

  it('formats kg correctly', () => {
    render(<WeightDisplay kg={50} />);
    expect(screen.getByText('50 kg')).toBeInTheDocument();
  });

  it('formats decimal kg', () => {
    render(<WeightDisplay kg={50.5} />);
    expect(screen.getByText('50.5 kg')).toBeInTheDocument();
  });

  it('formats imperial pounds', () => {
    render(<WeightDisplay kg={50} imperial={true} />);
    expect(screen.getByText('110.2 lbs')).toBeInTheDocument();
  });
});

describe('WeightPill', () => {
  it('renders weight with label', () => {
    render(<WeightPill kg={50} label="Max" />);
    expect(screen.getByText('50 kg')).toBeInTheDocument();
    expect(screen.getByText('Max')).toBeInTheDocument();
  });
});

describe('WeightRange', () => {
  it('displays range', () => {
    render(<WeightRange minKg={30} maxKg={50} />);
    expect(screen.getByText('30–50 kg')).toBeInTheDocument();
  });

  it('displays single value when min is null', () => {
    render(<WeightRange minKg={null} maxKg={50} />);
    expect(screen.getByText('50 kg')).toBeInTheDocument();
  });
});