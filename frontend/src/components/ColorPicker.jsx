// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useRef, useEffect } from "react";
import { getRecentColors, addRecentColor } from "../utils/recentColors";
import AnchoredPopover from "./ui/AnchoredPopover";

// A round rainbow icon that opens ONE popup containing an inline spectrum picker
// (saturation/value box + hue slider), a hex field, and the last 20 custom colours
// used anywhere in the app — so picking a colour is a single popup, no native
// colour dialog. Used alongside preset swatches in the track colour pickers and
// the Settings/onboarding accent picker.
//
// The popup renders through AnchoredPopover (a body portal) so it's never clipped
// by an ancestor's overflow-hidden (e.g. the Settings Section card) and auto-flips
// above/below the icon based on available space. The legacy `placement` prop is
// accepted for call-site compatibility but positioning is now automatic.

// ── hex ↔ hsv ────────────────────────────────────────────────────────────────
function hexToHsv(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex || "");
  const n = m ? m[1] : "3b82f6";
  const r = parseInt(n.slice(0, 2), 16) / 255, g = parseInt(n.slice(2, 4), 16) / 255, b = parseInt(n.slice(4, 6), 16) / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), d = max - min;
  let h = 0;
  if (d) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h = (h * 60 + 360) % 360;
  }
  return { h, s: max ? d / max : 0, v: max };
}
function hsvToHex({ h, s, v }) {
  const c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
  let r, g, b;
  if (h < 60) [r, g, b] = [c, x, 0];
  else if (h < 120) [r, g, b] = [x, c, 0];
  else if (h < 180) [r, g, b] = [0, c, x];
  else if (h < 240) [r, g, b] = [0, x, c];
  else if (h < 300) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  const to = (n) => ("0" + Math.round((n + m) * 255).toString(16)).slice(-2);
  return "#" + to(r) + to(g) + to(b);
}

export default function ColorPicker({
  onChange, value, size = "w-5 h-5", placement = "top", title = "Custom color", // eslint-disable-line no-unused-vars
}) {
  const [open, setOpen] = useState(false);
  const [recent, setRecent] = useState([]);
  const [hsv, setHsv] = useState(() => hexToHsv(value || "#3b82f6"));
  const [hexText, setHexText] = useState(value || "#3b82f6");
  const anchorRef = useRef(null);
  const svRef = useRef(null);
  const dragRef = useRef(false);
  const touchedRef = useRef(false);

  const draft = hsvToHex(hsv);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const draftRef = useRef(draft);
  draftRef.current = draft;

  // Reset the working colour to the current value each time the popup opens.
  useEffect(() => {
    if (!open) return;
    setRecent(getRecentColors());
    setHsv(hexToHsv(value || "#3b82f6"));
    setHexText(value || "#3b82f6");
    touchedRef.current = false;
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  // Close handler: record the dragged colour into "recent" if the user touched
  // the spectrum without clicking a swatch. AnchoredPopover calls this on
  // outside-click / Escape.
  const close = () => {
    if (touchedRef.current) setRecent(addRecentColor(draftRef.current));
    setOpen(false);
  };

  // Keep the hex field in sync while dragging the spectrum.
  useEffect(() => { if (touchedRef.current) setHexText(draft); }, [draft]); // eslint-disable-line react-hooks/exhaustive-deps

  const commit = (hex) => onChangeRef.current(hex);          // live commit (e.g. recolour the track)
  const applyAndClose = (hex) => {
    onChangeRef.current(hex);
    setRecent(addRecentColor(hex));
    setOpen(false);
  };

  const svFromEvent = (e) => {
    const r = svRef.current.getBoundingClientRect();
    const s = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
    const v = Math.min(1, Math.max(0, 1 - (e.clientY - r.top) / r.height));
    touchedRef.current = true;
    setHsv((prev) => ({ ...prev, s, v }));
  };

  return (
    <span className="inline-flex">
      <button
        type="button" title={title} ref={anchorRef}
        onClick={() => setOpen((o) => !o)}
        className={`${size} rounded-full ring-1 ring-black/10 hover:scale-110 transition-transform`}
        style={{ background: "conic-gradient(red, orange, yellow, lime, cyan, blue, magenta, red)" }}
      />
      <AnchoredPopover anchorRef={anchorRef} open={open} onClose={close} align="center">
        <div
          onClick={(e) => e.stopPropagation()}
          className="w-52 p-2 rounded-xl bg-white dark:bg-slate-900 shadow-xl border border-slate-200 dark:border-slate-700"
        >
          {/* Saturation / value box */}
          <div
            ref={svRef}
            onPointerDown={(e) => { dragRef.current = true; svRef.current.setPointerCapture(e.pointerId); svFromEvent(e); }}
            onPointerMove={(e) => { if (dragRef.current) svFromEvent(e); }}
            onPointerUp={() => { dragRef.current = false; commit(draftRef.current); }}
            className="relative w-full h-28 rounded-md cursor-crosshair touch-none"
            style={{
              backgroundColor: `hsl(${hsv.h} 100% 50%)`,
              backgroundImage: "linear-gradient(to top, #000, transparent), linear-gradient(to right, #fff, transparent)",
            }}
          >
            <span
              className="absolute w-3.5 h-3.5 rounded-full border-2 border-white shadow -translate-x-1/2 -translate-y-1/2 pointer-events-none"
              style={{ left: `${hsv.s * 100}%`, top: `${(1 - hsv.v) * 100}%`, backgroundColor: draft }}
            />
          </div>

          {/* Hue slider */}
          <input
            type="range" min="0" max="359" value={Math.round(hsv.h)}
            onChange={(e) => { touchedRef.current = true; setHsv((prev) => ({ ...prev, h: Number(e.target.value) })); }}
            onPointerUp={() => commit(draftRef.current)}
            onMouseUp={() => commit(draftRef.current)}
            className="w-full h-3 mt-2 rounded-full appearance-none cursor-pointer hue-slider"
            style={{ background: "linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00)" }}
          />

          {/* Hex field + live swatch */}
          <div className="flex items-center gap-2 mt-2">
            <span className="w-6 h-6 rounded ring-1 ring-black/10 shrink-0" style={{ backgroundColor: draft }} />
            <input
              type="text" value={hexText} spellCheck={false}
              onChange={(e) => {
                setHexText(e.target.value);
                if (/^#[0-9a-fA-F]{6}$/.test(e.target.value)) { touchedRef.current = true; setHsv(hexToHsv(e.target.value)); commit(e.target.value); }
              }}
              className="flex-1 min-w-0 px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-[11px] font-mono text-slate-700 dark:text-slate-200 outline-none focus:border-accent-400"
            />
          </div>

          {recent.length > 0 && (
            <>
              <div className="text-[9px] uppercase tracking-wide text-slate-400 mt-2 mb-1">Recent</div>
              <div className="flex flex-wrap gap-1">
                {recent.map((c) => (
                  <button key={c} onClick={() => applyAndClose(c)} title={c}
                    className="w-4 h-4 rounded-full ring-1 ring-black/10 hover:scale-110 transition-transform"
                    style={{ backgroundColor: c }} />
                ))}
              </div>
            </>
          )}
        </div>
      </AnchoredPopover>
    </span>
  );
}
