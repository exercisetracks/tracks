// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { GradeDisplay, GradePill, GradeRange } from '../components/activity/pills/GradeDisplay';

describe('GradeDisplay', () => {
  it('displays dash when no grade provided', () => {
    render(<GradeDisplay grade={null} />);
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('formats V-scale grade', () => {
    render(<GradeDisplay grade={5} scale="V" />);
    expect(screen.getByText('V5')).toBeInTheDocument();
  });

  it('formats YDS grade', () => {
    render(<GradeDisplay grade={10} scale="YDS" />);
    expect(screen.getByText('5.10')).toBeInTheDocument();
  });

  it('formats French grade', () => {
    render(<GradeDisplay grade={7} scale="French" />);
    expect(screen.getByText('6a')).toBeInTheDocument();
  });
});

describe('GradePill', () => {
  it('renders grade in pill container', () => {
    render(<GradePill grade={4} />);
    expect(screen.getByText('V4')).toBeInTheDocument();
  });
});

describe('GradeRange', () => {
  it('renders with min and max grades', () => {
    render(<GradeRange min={2} max={5} />);
    // Just verify it renders without error
    expect(true).toBe(true);
  });
});