// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useRef, useCallback, memo } from "react";

// ===========================================================================
// MapToolbar — the bottom-right navigation cluster, collapsed into ONE capsule.
//
// A modernized, compact take on Google Earth's nav ring:
//   • Orbit compass (hero) — drag horizontally to rotate, drag vertically to
//     tilt into 3D, tap to snap back to north. The red needle always points to
//     true north; a small pip in the corner toggles the whole way to/from 3D.
//   • Zoom rocker (footer) — −/+ with a live zoom readout between them.
//
// One drag gizmo now covers rotate + tilt (the old separate tilt slider is
// gone), and the 3D toggle lives as the pip — four controls folded into one.
// ===========================================================================

// Drag sensitivity: degrees of camera change per pixel dragged on the compass.
const DEG_PER_PX_BEARING = 0.7;
const DEG_PER_PX_PITCH = 0.6;

const GLASS =
  "bg-white/85 dark:bg-slate-800/85 backdrop-blur-md border border-slate-200/80 " +
  "dark:border-slate-700/80 shadow-lg shadow-slate-900/10";

// ── Orbit compass ────────────────────────────────────────────────────────────
// A trackball-style gizmo: horizontal drag spins the bearing, vertical drag
// tilts the pitch (drag up = look toward the horizon). A click that doesn't
// drag eases the map back to north.
const Compass = memo(function Compass({ bearing, pitch, is3D, mapReady, onOrbit, onResetNorth, onToggle3D }) {
  const ref = useRef(null);
  const drag = useRef(null);

  const onPointerDown = (e) => {
    e.stopPropagation();
    ref.current.setPointerCapture(e.pointerId);
    drag.current = { x: e.clientX, y: e.clientY, bearing, pitch, moved: false };
  };
  const onPointerMove = (e) => {
    if (!drag.current) return;
    e.stopPropagation();
    const dx = e.clientX - drag.current.x;
    const dy = e.clientY - drag.current.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) drag.current.moved = true;
    onOrbit({
      bearing: drag.current.bearing + dx * DEG_PER_PX_BEARING,
      pitch: drag.current.pitch - dy * DEG_PER_PX_PITCH,
    });
  };
  const onPointerUp = (e) => {
    if (!drag.current) return;
    e.stopPropagation();
    try { ref.current.releasePointerCapture(e.pointerId); } catch {}
    if (!drag.current.moved) onResetNorth();
    drag.current = null;
  };

  const tilted = pitch > 5;

  return (
    <div
      ref={ref}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      title="Drag to rotate · drag up to tilt · click for north"
      className={`relative w-[52px] h-[52px] rounded-full grid place-items-center cursor-grab active:cursor-grabbing touch-none select-none transition-shadow ${
        tilted ? "ring-2 ring-accent-500/50 shadow-[0_0_12px] shadow-accent-500/30" : ""
      }`}
    >
      <svg viewBox="0 0 60 60" className="w-full h-full pointer-events-none">
        {/* Dial face */}
        <circle cx="30" cy="30" r="26" className="fill-slate-100/60 dark:fill-slate-900/40 stroke-slate-300/70 dark:stroke-slate-600/70" strokeWidth="1" />
        {/* Fixed heading marker at screen-top */}
        <path d="M30 5 l3 5 h-6 z" className="fill-slate-400 dark:fill-slate-500" />
        {/* Rotating rose */}
        <g transform={`rotate(${-bearing} 30 30)`}>
          <polygon points="30,12 26,30 34,30" fill="#e11d48" />
          <polygon points="30,48 26,30 34,30" className="fill-slate-400 dark:fill-slate-500" />
          <text x="30" y="20" textAnchor="middle" fontSize="8" fontWeight="700"
                className="fill-rose-600 dark:fill-rose-500">N</text>
        </g>
        <circle cx="30" cy="30" r="3" className="fill-white dark:fill-slate-800 stroke-slate-300 dark:stroke-slate-600" strokeWidth="1" />
      </svg>

      {/* 3D / 2D pip — toggles the whole way to/from a tilted view */}
      <button
        type="button"
        onPointerDown={(e) => e.stopPropagation()}
        onClick={(e) => { e.stopPropagation(); onToggle3D(); }}
        title={is3D ? "Switch to 2D view" : "Tilt to 3D view"}
        aria-pressed={is3D}
        className={`absolute -bottom-0.5 -right-0.5 w-6 h-[18px] rounded-full text-[9px] font-bold grid place-items-center border transition-colors ${
          is3D
            ? "bg-accent-500 border-accent-600 text-white"
            : "bg-white dark:bg-slate-800 border-slate-300/80 dark:border-slate-600/80 text-slate-500 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white"
        }`}
      >
        {is3D ? "3D" : "2D"}
      </button>

      {!mapReady && (
        <div className="absolute inset-0 grid place-items-center">
          <div className="spinner w-4 h-4" />
        </div>
      )}
    </div>
  );
});

export default function MapToolbar({ map, mapReady, is3D, onToggle3D, orbitTo }) {
  const zoomRef = useRef(null);
  const [bearing, setBearing] = useState(0);
  const [pitch, setPitch] = useState(0);

  // Mirror the live camera so the dial reflects the true map state, however it's
  // driven (buttons, drag-gestures, or the compass). `move` fires every frame of
  // a pan/zoom, so this is on the hot path: coalesce to one update per animation
  // frame. The zoom readout is written straight to its DOM node — it changes on
  // every zoom frame, and a React commit per tick is pure overhead for one text
  // node. Bearing/pitch stay React state (the memoized Compass takes them as
  // props) but only commit when the displayed whole-degree value changes, so a
  // pure zoom never re-renders anything.
  useEffect(() => {
    if (!map) return;
    let raf = null;
    const apply = () => {
      raf = null;
      const b = map.getBearing();
      const p = map.getPitch();
      if (zoomRef.current) zoomRef.current.textContent = map.getZoom().toFixed(1);
      setBearing((prev) => (Math.round(prev) === Math.round(b) ? prev : b));
      setPitch((prev) => (Math.round(prev) === Math.round(p) ? prev : p));
    };
    const sync = () => {
      if (raf == null) raf = requestAnimationFrame(apply);
    };
    apply();
    map.on("move", sync);
    return () => {
      if (raf != null) cancelAnimationFrame(raf);
      map.off("move", sync);
    };
  }, [map]);

  const resetNorth = useCallback(() => {
    if (!map) return;
    try { map.easeTo({ bearing: 0, duration: 400 }); } catch {}
  }, [map]);

  const zBtn =
    "w-6 h-6 grid place-items-center rounded-lg text-slate-600 dark:text-slate-300 " +
    "hover:text-slate-900 dark:hover:text-white hover:bg-slate-500/10 transition-colors text-base font-bold leading-none";

  return (
    <div data-tour="map-toolbar" className={`absolute bottom-4 right-4 z-10 flex flex-col items-center gap-1.5 p-1 rounded-[1.4rem] ${GLASS}`}>
      <Compass
        bearing={bearing}
        pitch={pitch}
        is3D={is3D}
        mapReady={mapReady}
        onOrbit={orbitTo}
        onResetNorth={resetNorth}
        onToggle3D={onToggle3D}
      />

      <div className="w-8 h-px bg-slate-200/80 dark:bg-slate-700/80" />

      {/* Zoom rocker: − · readout · + */}
      <div className="flex flex-col items-center gap-0.5">
        <button className={zBtn} title="Zoom in" onClick={() => map?.zoomIn()}>+</button>
        <span ref={zoomRef} className="text-[9px] font-mono font-semibold text-slate-500 dark:text-slate-400 tabular-nums leading-none">
          –
        </span>
        <button className={zBtn} title="Zoom out" onClick={() => map?.zoomOut()}>−</button>
      </div>
    </div>
  );
}
