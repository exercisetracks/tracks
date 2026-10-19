// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// One hover query per frame for every hoverable layer on the map.
//
// Hover used to be a MapLibre per-layer `mousemove` listener in each hook —
// activity tracks, courses, saved places, long trails, fires — and MapLibre
// answers each of those with its own queryRenderedFeatures on every mouse
// event. Flat, that is a handful of cheap queries; in 3D every query is
// projected through the terrain, and five or more of them per mouse event
// (mice report at hundreds of hertz) were what made the hover glow lag far
// behind the cursor on a laptop on battery.
//
// Here the map has one `mousemove` listener. It only notes where the cursor
// is; once per animation frame, and not at all while the camera moves (the
// glow under a moving camera is not worth a frame), a single query over every
// registered layer answers all of them. Each registration then hears what
// MapLibre's per-layer events told it: `move` with the features of its own
// layers while the cursor is over one, `leave` once when it stops being.
const hubs = new WeakMap();

function hubFor(map) {
  let hub = hubs.get(map);
  if (hub) return hub;
  const regs = new Set();
  let point = null;
  let lngLat = null;
  let frame = 0;

  const run = () => {
    frame = 0;
    if (!point || !regs.size) return;
    try { if (map.isMoving()) return; } catch { return; }
    const live = new Set();
    for (const r of regs) {
      for (const id of r.layers()) {
        try { if (map.getLayer(id)) live.add(id); } catch { /* style reloading */ }
      }
    }
    let features = [];
    if (live.size) {
      try { features = map.queryRenderedFeatures(point, { layers: [...live] }); } catch { features = []; }
    }
    for (const r of regs) {
      const own = r.layers();
      const mine = features.filter((f) => own.includes(f.layer?.id));
      if (mine.length) {
        r.inside = true;
        r.move({ features: mine, point, lngLat });
      } else if (r.inside) {
        r.inside = false;
        r.leave?.();
      }
    }
  };
  const schedule = () => { if (!frame) frame = requestAnimationFrame(run); };
  const onMove = (e) => { point = e.point; lngLat = e.lngLat; schedule(); };
  const onOut = () => {
    point = null;
    for (const r of regs) if (r.inside) { r.inside = false; r.leave?.(); }
  };
  // The camera stopped under a still cursor: what is under it may have changed.
  const onSettled = () => { if (point) schedule(); };

  hub = {
    regs,
    attach() {
      map.on("mousemove", onMove);
      map.on("mouseout", onOut);
      map.on("moveend", onSettled);
    },
    detach() {
      map.off("mousemove", onMove);
      map.off("mouseout", onOut);
      map.off("moveend", onSettled);
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
      hubs.delete(map);
    },
  };
  hubs.set(map, hub);
  hub.attach();
  return hub;
}

/**
 * Hover on [layers] (ids, or a function returning them for layers that come
 * and go). [move] gets `{ features, point, lngLat }` like a MapLibre layer
 * event; [leave] runs once when the cursor is no longer over any of them.
 * Returns the unsubscribe.
 */
export function onLayerHover(map, layers, { move, leave }) {
  const hub = hubFor(map);
  const reg = { layers: typeof layers === "function" ? layers : () => layers, move, leave, inside: false };
  hub.regs.add(reg);
  return () => {
    hub.regs.delete(reg);
    if (reg.inside) reg.leave?.();
    if (!hub.regs.size) hub.detach();
  };
}
