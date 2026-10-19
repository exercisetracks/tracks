// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { onLayerHover } from '../pages/maps/hooks/hoverHub';

function fakeMap(hits) {
  const handlers = {};
  return {
    moving: false,
    on: (ev, fn) => { (handlers[ev] ??= []).push(fn); },
    off: (ev, fn) => { handlers[ev] = (handlers[ev] || []).filter((f) => f !== fn); },
    fire: (ev, e = {}) => (handlers[ev] || []).forEach((f) => f(e)),
    isMoving() { return this.moving; },
    getLayer: () => ({}),
    queryRenderedFeatures: vi.fn((pt, { layers }) => hits().filter((f) => layers.includes(f.layer.id))),
    handlers,
  };
}

// In 3D every hover query is projected through the terrain, and each hook
// asking on every mouse event made the hover glow lag behind the cursor.
describe('the shared hover query', () => {
  let frames;
  beforeEach(() => {
    frames = [];
    vi.stubGlobal('requestAnimationFrame', (fn) => { frames.push(fn); return frames.length; });
    vi.stubGlobal('cancelAnimationFrame', () => {});
  });
  afterEach(() => vi.unstubAllGlobals());
  const flush = () => { const f = frames.splice(0); f.forEach((fn) => fn()); };

  it('asks once per frame for every hoverable layer, however many mouse events', () => {
    let under = [{ layer: { id: 'a' }, id: 1 }];
    const map = fakeMap(() => under);
    const a = vi.fn(); const b = vi.fn(); const bLeave = vi.fn();
    onLayerHover(map, ['a'], { move: a, leave: vi.fn() });
    onLayerHover(map, ['b'], { move: b, leave: bLeave });
    for (let i = 0; i < 20; i++) map.fire('mousemove', { point: { x: i, y: 0 } });
    flush();
    expect(map.queryRenderedFeatures).toHaveBeenCalledTimes(1);
    expect(a).toHaveBeenCalledWith(expect.objectContaining({ features: under }));
    expect(b).not.toHaveBeenCalled();
    expect(bLeave).not.toHaveBeenCalled();
  });

  it('tells a layer once when the cursor leaves it', () => {
    let under = [{ layer: { id: 'a' }, id: 1 }];
    const map = fakeMap(() => under);
    const leave = vi.fn();
    onLayerHover(map, ['a'], { move: vi.fn(), leave });
    map.fire('mousemove', { point: { x: 0, y: 0 } }); flush();
    under = [];
    map.fire('mousemove', { point: { x: 1, y: 0 } }); flush();
    map.fire('mousemove', { point: { x: 2, y: 0 } }); flush();
    expect(leave).toHaveBeenCalledTimes(1);
  });

  it('does not query while the camera moves, and catches up when it stops', () => {
    const map = fakeMap(() => [{ layer: { id: 'a' }, id: 1 }]);
    const move = vi.fn();
    onLayerHover(map, ['a'], { move, leave: vi.fn() });
    map.moving = true;
    map.fire('mousemove', { point: { x: 0, y: 0 } }); flush();
    expect(map.queryRenderedFeatures).not.toHaveBeenCalled();
    map.moving = false;
    map.fire('moveend'); flush();
    expect(move).toHaveBeenCalledTimes(1);
  });

  it('lets go of the map when the last layer unsubscribes', () => {
    const map = fakeMap(() => []);
    const off = onLayerHover(map, ['a'], { move: vi.fn() });
    off();
    expect(map.handlers.mousemove).toEqual([]);
  });
});
