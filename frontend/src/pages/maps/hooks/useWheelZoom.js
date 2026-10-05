// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect } from "react";

/**
 * useWheelZoom — direct, latency-free wheel/trackpad zoom.
 *
 * Replaces MapLibre's built-in ScrollZoomHandler, whose gesture pipeline is
 * what made touchpad zoom feel awful here:
 *   • a fresh gesture arms a ~40ms wheel-vs-trackpad detection timeout before
 *     ANY zoom is applied (and Linux/Chromium pixel deltas routinely get
 *     misclassified as a discrete wheel),
 *   • per-frame deltas pass through a sigmoid that suppresses the small early
 *     deltas of a two-finger gesture (the "starts slow, then lurches" ramp),
 *   • 'wheel'-classified input zooms through a 200ms easing that keeps the map
 *     moving well after the fingers have lifted (measured: ~650ms of tail).
 *
 * This handler applies input 1:1 instead: deltas accumulate across the events
 * of one frame and are applied synchronously in a single rAF via
 * easeTo({duration: 0}) — which fires the full movestart→zoomstart→zoom→
 * zoomend→moveend cycle, so every downstream listener (use3D LOD shedding,
 * POI refetch, tile prefetch, toolbar readout) sees the exact events the
 * built-in handler would have produced. No smoothing, no tail: when the
 * fingers stop, the map stops.
 *
 * Interception: the listener sits on the map container in the CAPTURE phase
 * and stops propagation, so the built-in handler (a bubble listener on the
 * canvas container) never sees a wheel. map.scrollZoom itself stays ENABLED
 * and untouched — it acts purely as the on/off flag other features already
 * toggle (useRegionDownload disables it while drawing), which this handler
 * honours by checking isEnabled() per event.
 */

// Zoom levels per pixel of two-finger scroll. The built-in handler works out
// to ≈1/139 at steady state; tuned much slower here (per feel-test feedback) now
// that the detection delay and easing tail are gone and every pixel of input
// registers immediately. THE feel-tuning knob.
const ZOOM_PER_PX = 1 / 500;
// ctrlKey wheel events are the browser's encoding of a pinch gesture; their
// deltas are much smaller than two-finger-scroll deltas, so amplify.
const PINCH_MULTIPLIER = 3;
// deltaMode normalization → pixels (Firefox reports lines, ×40 is the same
// constant MapLibre uses; DOM_DELTA_PAGE is a rarity but cheap to honour).
const LINE_PX = 40;
const PAGE_PX = 800;
// Shift+scroll = fine-grained zoom for precise framing.
const SHIFT_PRECISION = 0.25;
// Per-EVENT clamp (px): tames discrete mouse-wheel notches (±100-120px each)
// and libinput kinetic spikes so one notch is a step, not a leap.
const MAX_EVENT_PX = 100;
// Per-FRAME clamp (zoom levels): a hard ceiling on how far one rendered frame
// may jump, however many events piled up behind a slow frame.
const MAX_FRAME_DZ = 1.25;

export function useWheelZoom(map) {
  useEffect(() => {
    if (!map) return;
    const container = map.getContainer();
    let pendingDz = 0;
    let lastPoint = null; // cursor, canvas-relative — the zoom anchor
    let rafId = null;

    // Zoom around the cursor, except when a pitched camera puts the cursor
    // above the horizon where unproject() has no ground to hit — then fall
    // back to the screen center, exactly like the built-in handler
    // (its condition: mouse.y > height/2 - getHorizon() ⇒ cursor is on the
    // ground plane). getHorizon() is internal API — fine on our pinned
    // maplibre 4.7.1, re-verify on any upgrade; the try/catch degrades to
    // center-anchored zoom if it ever disappears.
    const aroundLngLat = () => {
      const p = lastPoint;
      if (!p) return map.getCenter();
      try {
        const tr = map.transform;
        if (!(p.y > tr.height / 2 - tr.getHorizon())) return map.getCenter();
      } catch {
        return map.getCenter();
      }
      return map.unproject([p.x, p.y]);
    };

    const applyZoom = () => {
      rafId = null;
      const dz = Math.max(-MAX_FRAME_DZ, Math.min(MAX_FRAME_DZ, pendingDz));
      // Discard any clamped-off remainder rather than carrying it into the
      // next frame — a carried remainder is inertia, the thing we're killing.
      pendingDz = 0;
      if (!dz) return;
      const current = map.getZoom();
      const target = Math.max(map.getMinZoom(), Math.min(map.getMaxZoom(), current + dz));
      if (target === current) return; // parked at a zoom limit: no event churn
      map.easeTo({ zoom: target, around: aroundLngLat(), duration: 0 });
    };

    const onWheel = (e) => {
      // Wheels over map controls/popups (siblings of the canvas container)
      // scroll normally; only intercept input aimed at the map itself.
      if (!map.getCanvasContainer().contains(e.target)) return;
      // scrollZoom is the shared "wheel zoom allowed" flag — honour it so
      // region-drawing's disable()/enable() keeps working unchanged.
      if (!map.scrollZoom.isEnabled()) return;
      e.preventDefault();
      e.stopPropagation(); // the built-in handler must never double-apply

      let px = e.deltaY;
      if (e.deltaMode === 1) px *= LINE_PX;
      else if (e.deltaMode === 2) px *= PAGE_PX;
      if (e.ctrlKey) px *= PINCH_MULTIPLIER;
      if (e.shiftKey) px *= SHIFT_PRECISION;
      px = Math.max(-MAX_EVENT_PX, Math.min(MAX_EVENT_PX, px));

      pendingDz -= px * ZOOM_PER_PX;
      const r = map.getCanvas().getBoundingClientRect();
      lastPoint = { x: e.clientX - r.left, y: e.clientY - r.top };
      if (rafId == null) rafId = requestAnimationFrame(applyZoom);
    };

    container.addEventListener("wheel", onWheel, { capture: true, passive: false });
    return () => {
      container.removeEventListener("wheel", onWheel, { capture: true });
      if (rafId != null) cancelAnimationFrame(rafId);
    };
  }, [map]);
}
