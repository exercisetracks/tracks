// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DurationDisplay } from '../components/activity/pills/DurationDisplay';

describe('DurationDisplay', () => {
  it('displays dash when no seconds provided', () => {
    render(<DurationDisplay seconds={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('formats seconds correctly', () => {
    render(<DurationDisplay seconds={30} format="detailed" />);
    expect(screen.getByText('30s')).toBeInTheDocument();
  });

  it('formats minutes and seconds', () => {
    render(<DurationDisplay seconds={90} format="detailed" />);
    expect(screen.getByText('1m 30s')).toBeInTheDocument();
  });

  it('formats hours, minutes, seconds', () => {
    render(<DurationDisplay seconds={3725} format="detailed" />);
    expect(screen.getByText('1h 2m 5s')).toBeInTheDocument();
  });

  it('formats compact style', () => {
    render(<DurationDisplay seconds={3725} format="compact" />);
    expect(screen.getByText('1h 2m')).toBeInTheDocument();
  });

  it('formats mmss style', () => {
    render(<DurationDisplay seconds={95} format="mmss" />);
    expect(screen.getByText('1:35')).toBeInTheDocument();
  });
});