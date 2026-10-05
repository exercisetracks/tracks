// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { slugify, resolveSlug, hasAnimation } from '../animations/index';

describe('slugify', () => {
  it('normalizes names to slugs', () => {
    expect(slugify('Barbell Back Squat')).toBe('barbell_back_squat');
    expect(slugify("Farmer's Carry")).toBe('farmers_carry');
    expect(slugify('Push-Up')).toBe('push_up');
  });
});

describe('registry', () => {
  it('hasAnimation is false with no assets registered', () => {
    expect(hasAnimation('Barbell Back Squat')).toBe(false);
    expect(hasAnimation('Some Unknown Move')).toBe(false);
  });

  it('resolveSlug is null with no assets registered, even via alias or an explicit viewer slug', () => {
    expect(resolveSlug('Air Squat')).toBe(null);
    expect(resolveSlug('My Custom Lift', 'push_up')).toBe(null);
  });
});
