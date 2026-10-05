// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ResultBadge, StatusBadge, ResultIcon } from '../components/activity/cards/ResultBadge';

describe('ResultBadge', () => {
  it('displays send badge for result 3', () => {
    render(<ResultBadge result={3} />);
    expect(screen.getByText('Send')).toBeInTheDocument();
  });

  it('displays attempt badge for result 2', () => {
    render(<ResultBadge result={2} />);
    expect(screen.getByText('Attempt')).toBeInTheDocument();
  });

  it('returns null for other results', () => {
    const { container } = render(<ResultBadge result={0} />);
    expect(container.firstChild).toBeNull();
  });
});

describe('StatusBadge', () => {
  it('displays status text', () => {
    render(<StatusBadge status="Complete" type="success" />);
    expect(screen.getByText('Complete')).toBeInTheDocument();
  });

  it('defaults to neutral type', () => {
    render(<StatusBadge status="Pending" />);
    expect(screen.getByText('Pending')).toBeInTheDocument();
  });
});

describe('ResultIcon', () => {
  it('displays check for send', () => {
    render(<ResultIcon result={3} />);
    expect(screen.getByText('✓')).toBeInTheDocument();
  });

  it('displays circle for attempt', () => {
    render(<ResultIcon result={2} />);
    expect(screen.getByText('○')).toBeInTheDocument();
  });
});