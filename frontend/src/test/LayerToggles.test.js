// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useLayerToggles, initialToggles, LAYER_GROUPS } from '../pages/maps/hooks/useLayerToggles';

// The map used to open on the defaults every time, so a person who always
// wanted activity tracks on switched them on at every visit.
describe('remembered layer choices', () => {
  beforeEach(() => localStorage.clear());

  it('opens a fresh browser on the defaults', () => {
    const t = initialToggles();
    for (const g of LAYER_GROUPS) expect(t[g.id]).toBe(g.default);
  });

  it('opens the way the layers were last left', () => {
    const map = { setLayoutProperty: vi.fn(), setLayerZoomRange: vi.fn() };
    const group = LAYER_GROUPS.find((g) => !g.default);
    const { result } = renderHook(() => useLayerToggles(map));
    act(() => result.current.setVisibility(group.id, true));
    const again = renderHook(() => useLayerToggles(map));
    expect(again.result.current.toggles[group.id]).toBe(true);
  });

  it('ignores a saved group that no longer exists, and a broken entry', () => {
    localStorage.setItem('tracks.map.layers', JSON.stringify({ retired_group: true }));
    expect(initialToggles().retired_group).toBeUndefined();
    localStorage.setItem('tracks.map.layers', '{not json');
    const t = initialToggles();
    for (const g of LAYER_GROUPS) expect(t[g.id]).toBe(g.default);
  });
});
