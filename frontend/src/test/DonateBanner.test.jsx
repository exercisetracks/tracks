// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import DonateBanner from '../components/DonateBanner';

const FIRST_SEEN_KEY = 'tracks:donate-first-seen';
const LAST_SHOWN_KEY = 'tracks:donate-last-dismissed';
const DAY_MS = 24 * 60 * 60 * 1000;

function daysAgo(n) {
  return String(Date.now() - n * DAY_MS);
}

describe('DonateBanner', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('shows nothing on a first-ever mount, but starts the clock', () => {
    render(<DonateBanner />);
    expect(screen.queryByText('Donate')).not.toBeInTheDocument();
    expect(localStorage.getItem(FIRST_SEEN_KEY)).not.toBeNull();
  });

  it('stays hidden in the first week after first use', () => {
    localStorage.setItem(FIRST_SEEN_KEY, daysAgo(3));
    render(<DonateBanner />);
    expect(screen.queryByText('Donate')).not.toBeInTheDocument();
  });

  it('shows once a week has passed with no dismissal', () => {
    localStorage.setItem(FIRST_SEEN_KEY, daysAgo(8));
    render(<DonateBanner />);
    expect(screen.getByText('Donate')).toBeInTheDocument();
  });

  it('hides immediately when dismissed', () => {
    localStorage.setItem(FIRST_SEEN_KEY, daysAgo(8));
    render(<DonateBanner />);
    fireEvent.click(screen.getByText('Not now'));
    expect(screen.queryByText('Donate')).not.toBeInTheDocument();
    expect(localStorage.getItem(LAST_SHOWN_KEY)).not.toBeNull();
  });

  it('stays hidden within a month of a dismissal', () => {
    localStorage.setItem(FIRST_SEEN_KEY, daysAgo(60));
    localStorage.setItem(LAST_SHOWN_KEY, daysAgo(10));
    render(<DonateBanner />);
    expect(screen.queryByText('Donate')).not.toBeInTheDocument();
  });

  it('shows again roughly a month after a dismissal', () => {
    localStorage.setItem(FIRST_SEEN_KEY, daysAgo(60));
    localStorage.setItem(LAST_SHOWN_KEY, daysAgo(31));
    render(<DonateBanner />);
    expect(screen.getByText('Donate')).toBeInTheDocument();
  });
});
