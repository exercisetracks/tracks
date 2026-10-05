// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef, useCallback } from "react";
import { COLORS } from "../style/palette";

// Lat/lon graticule drawn as a screen-space CANVAS HUD painted directly over the
// map — NOT as a MapLibre GeoJSON source.
//
// Why a canvas instead of a vector source: the grid is a full-viewport pattern
// that has to track the camera every frame. Feeding it through a GeoJSON source
// means a `setData` → worker re-tile → GPU upload round-trip on EVERY frame of a
// pan/zoom, which made the grid clunky. A canvas HUD is the way a game draws its
// overlay: project the handful of grid-line endpoints to screen pixels with
// `map.project()` and stroke them — a few dozen lines + labels in well under a
// millisecond, no worker, no tile upload. It draws in one flat pass so it can't
// drape on a tilted terrain mesh, which is fine: the grid is force-hidden in 3D.
//
// Everything is lat/lon degrees at every zoom (no UTM) — one simple, consistent
// system. Edge-pinned "collar" labels (USGS quad style) fall out for free: a
// label is just text stroked at the screen edge where its meridian/parallel
// crosses.

// Fraction of the viewport span the lines are extended beyond each edge, so a
// fast drag never outruns the drawn line between frames.
const PAD = 0.25;

// "Nice" graticule steps in degrees (USGS quads tick at 2.5′ = 1/24°).
const DEG_STEPS = [10, 5, 2, 1, 0.5, 0.25, 1 / 6, 1 / 12, 1 / 24, 1 / 60, 1 / 120, 1 / 360];

// Above this pitch the grid is force-hidden (it's a flat HUD; a graticule over
// tilted terrain is meaningless and can't drape). Kept in sync with use3D's 3D
// threshold and with the disabled state shown in the layer panel.
const PITCH_2D_MAX = 5;

// Cartography — thicker lines and larger labels than the old vector grid.
const GRAT_COLOR = "#4A443B";
const GRAT_ALPHA = 0.62;
const GRAT_WIDTH = 1.1;
const GRAT_FONT = 13;
const HALO = COLORS.background;

function chooseStep(span, steps, divisions = 5) {
  const ideal = span / divisions;
  for (const s of steps) if (s <= ideal) return s;
  return steps[steps.length - 1];
}

function dms(value, step, isLat) {
  const hemi = isLat ? (value >= 0 ? "N" : "S") : (value >= 0 ? "E" : "W");
  const v = Math.abs(value);
  const d = Math.floor(v);
  const m = (v - d) * 60;
  const mi = Math.floor(m);
  const s = Math.round((m - mi) * 60);
  if (step >= 1) return `${d}°${hemi}`;
  if (step >= 1 / 60) return `${d}°${String(mi).padStart(2, "0")}′${hemi}`;
  return `${d}°${String(mi).padStart(2, "0")}′${String(s).padStart(2, "0")}″${hemi}`;
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

// Draw text with a cream halo (stroke under fill) so it reads over any terrain.
function label(ctx, txt, x, y) {
  ctx.lineWidth = 3;
  ctx.strokeStyle = HALO;
  ctx.strokeText(txt, x, y);
  ctx.fillStyle = GRAT_COLOR;
  ctx.fillText(txt, x, y);
}

export function useGridOverlay(map, ready, visible, canvasRef) {
  const rafRef = useRef(null);
  // Read the live toggle from inside the per-frame handler without re-subscribing.
  const visibleRef = useRef(visible);
  visibleRef.current = visible;

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !map) return;

    // Match the backing store to the CSS box at the device pixel ratio, only
    // reallocating when the size actually changed (cheap no-op most frames).
    const cssW = canvas.clientWidth, cssH = canvas.clientHeight;
    const dpr = window.devicePixelRatio || 1;
    const bw = Math.round(cssW * dpr), bh = Math.round(cssH * dpr);
    if (canvas.width !== bw || canvas.height !== bh) {
      canvas.width = bw;
      canvas.height = bh;
    }
    const ctx = canvas.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cssW, cssH);

    // Nothing to draw when off, or in 3D (flat HUD can't drape on terrain).
    if (!visibleRef.current || map.getPitch() > PITCH_2D_MAX) return;

    const b = map.getBounds();
    const w = b.getWest(), e = b.getEast(), s = b.getSouth(), n = b.getNorth();
    const cLon = (w + e) / 2, cLat = (s + n) / 2;
    const padLon = (e - w) * PAD, padLat = (n - s) * PAD;
    const wp = w - padLon, ep = e + padLon, sp = s - padLat, np = n + padLat;
    const project = (lng, lat) => map.project([lng, lat]);

    const stepLon = chooseStep(e - w, DEG_STEPS);
    const stepLat = chooseStep(n - s, DEG_STEPS);

    // Lines span a padded box so a fast drag never outruns them between frames.
    ctx.globalAlpha = GRAT_ALPHA;
    ctx.strokeStyle = GRAT_COLOR;
    ctx.lineWidth = GRAT_WIDTH;
    ctx.beginPath();
    for (let lon = Math.ceil(wp / stepLon) * stepLon; lon <= ep; lon += stepLon) {
      const a = project(lon, sp), c = project(lon, np);
      ctx.moveTo(a.x, a.y); ctx.lineTo(c.x, c.y);
    }
    for (let lat = Math.ceil(sp / stepLat) * stepLat; lat <= np; lat += stepLat) {
      const a = project(wp, lat), c = project(ep, lat);
      ctx.moveTo(a.x, a.y); ctx.lineTo(c.x, c.y);
    }
    ctx.stroke();
    ctx.globalAlpha = 1;

    // Edge-pinned labels: meridians along the top edge, parallels along the left
    // edge (the USGS collar). Only the on-screen ticks (actual bounds).
    ctx.font = `600 ${GRAT_FONT}px "Noto Sans", system-ui, sans-serif`;
    ctx.textBaseline = "top";
    for (let lon = Math.ceil(w / stepLon) * stepLon; lon <= e; lon += stepLon) {
      const x = project(lon, cLat).x;
      if (x < -20 || x > cssW + 20) continue;
      label(ctx, dms(lon, stepLon, false), clamp(x + 3, 2, cssW - 52), 4);
    }
    for (let lat = Math.ceil(s / stepLat) * stepLat; lat <= n; lat += stepLat) {
      const y = project(cLon, lat).y;
      if (y < -20 || y > cssH + 20) continue;
      label(ctx, dms(lat, stepLat, true), 4, clamp(y + 2, 2, cssH - 16));
    }
  }, [map, canvasRef]);

  // Redraw immediately when the toggle flips (so switching on paints at once, and
  // switching off clears the canvas without waiting for a pan).
  useEffect(() => {
    if (ready && map) draw();
  }, [ready, map, visible, draw]);

  // Per-frame redraw, coalesced to one paint per animation frame. `move` fires on
  // every pan/zoom/rotate/pitch frame, so it's the only camera hook we need; we
  // add resize for container changes.
  useEffect(() => {
    if (!ready || !map) return;
    const onFrame = () => {
      // Grid off ⇒ zero per-frame work (the toggle effect already cleared the
      // canvas). Only schedule paints while it's actually showing.
      if (!visibleRef.current) return;
      if (rafRef.current != null) return;
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null;
        draw();
      });
    };
    draw();
    map.on("move", onFrame);
    map.on("moveend", onFrame);
    map.on("resize", onFrame);
    window.addEventListener("resize", onFrame);
    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
      map.off("move", onFrame);
      map.off("moveend", onFrame);
      map.off("resize", onFrame);
      window.removeEventListener("resize", onFrame);
    };
  }, [ready, map, draw]);
}
