// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared elevation-profile chart used by the long-trail detail panel
// (RouteDetailPanel), the track detail panel and the editor.
//
// Axis labels are crisp HTML overlays (not SVG <text>): the chart path is drawn
// in a non-uniformly-stretched viewBox (preserveAspectRatio="none"), which would
// distort and clip SVG text. The SVG holds only the area/line/gridlines; the left
// + bottom gutters carry readable, unstretched labels.
//
// `grade` colours the line by steepness — green when flat, yellow around 8 %,
// red from 15 % — as the phone's editor does: "↑340 m" says nothing about
// whether that is one steady pull or four sharp ones. `onScrub` is told the
// distance (km) under the pointer, and null when it leaves, so the map can
// mark the same spot on the line.
import { useState } from "react";
import { gradeColor } from "../utils/mapItems";

const AXIS_LBL = "text-[10px] tabular-nums text-slate-600 dark:text-slate-300";

/** Steepness is read over at least this much ground; GPS heights are noise over less. */
const GRADE_RUN_KM = 0.08;

function gradeRuns(pts) {
  const runs = [];
  let start = 0;
  for (let i = 1; i < pts.length; i++) {
    const run = pts[i].d_km - pts[start].d_km;
    if (run < GRADE_RUN_KM && i < pts.length - 1) continue;
    const grade = run > 0 ? (pts[i].ele_m - pts[start].ele_m) / (run * 1000) : 0;
    runs.push({ from: start, to: i, color: gradeColor(grade) });
    start = i;
  }
  return runs;
}

export default function ElevationProfileCard({ profile, imperial, grade = false, onScrub, height = 150 }) {
  const [hover, setHover] = useState(null);   // { f, km, ele } | null
  const pts = (profile?.points || []).filter((p) => p.ele_m != null);
  if (pts.length < 2) {
    return (
      <div style={{ height }} className="flex items-center justify-center text-xs text-slate-400 px-3.5 text-center">
        Elevation profile unavailable for this track.
      </div>
    );
  }
  const dist = pts[pts.length - 1].d_km || 1;
  let lo = Math.min(...pts.map((p) => p.ele_m));
  let hi = Math.max(...pts.map((p) => p.ele_m));
  if (hi - lo < 10) { hi += 5; lo -= 5; }
  const sx = (d) => (d / dist) * 1000;
  const sy = (e) => 100 - ((e - lo) / (hi - lo)) * 100;
  const pathOf = (from, to) => pts.slice(from, to + 1)
    .map((p, i) => `${i ? "L" : "M"}${sx(p.d_km).toFixed(1)},${sy(p.ele_m).toFixed(2)}`).join(" ");
  const line = pathOf(0, pts.length - 1);
  const area = `${line} L1000,100 L0,100 Z`;
  const yticks = [1, 0.5, 0].map((f) => lo + (hi - lo) * f);
  const xticks = [0, 0.25, 0.5, 0.75, 1];
  const ftM = (m) => imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m)} m`;
  const distLabel = (km) => {
    const v = imperial ? km * 0.621371 : km;
    return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${imperial ? "mi" : "km"}`;
  };

  const eleAt = (km) => {
    const k = pts.findIndex((p) => p.d_km >= km);
    if (k <= 0) return pts[0].ele_m;
    const a = pts[k - 1], b = pts[k];
    const t = Math.max(0, Math.min(1, (km - a.d_km) / Math.max(1e-6, b.d_km - a.d_km)));
    return a.ele_m + (b.ele_m - a.ele_m) * t;
  };
  const onMove = (e) => {
    const r = e.currentTarget.getBoundingClientRect();
    const f = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
    const km = f * dist;
    setHover({ f, km, ele: eleAt(km) });
    onScrub?.(km);
  };
  const onLeave = () => { setHover(null); onScrub?.(null); };

  return (
    <div style={{ height }} className="relative w-full pl-14 pr-2 pt-2 pb-5 text-accent-600 dark:text-accent-400">
      <div className="relative w-full h-full" onMouseMove={onMove} onMouseLeave={onLeave}>
        <svg viewBox="0 0 1000 100" preserveAspectRatio="none" className="absolute inset-0 w-full h-full overflow-visible">
          <defs>
            <linearGradient id="elevCardFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="currentColor" stopOpacity="0.30" />
              <stop offset="100%" stopColor="currentColor" stopOpacity="0.02" />
            </linearGradient>
          </defs>
          {yticks.map((t) => (
            <line key={`y${t}`} x1="0" y1={sy(t)} x2="1000" y2={sy(t)} stroke="currentColor"
                  strokeOpacity="0.14" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          ))}
          {xticks.map((f) => (
            <line key={`x${f}`} x1={1000 * f} y1="0" x2={1000 * f} y2="100" stroke="currentColor"
                  strokeOpacity="0.09" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          ))}
          <path d={area} fill="url(#elevCardFill)" />
          {grade
            ? gradeRuns(pts).map((r) => (
              <path key={r.from} d={pathOf(r.from, r.to)} fill="none" stroke={r.color}
                    strokeWidth="2.5" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
            ))
            : <path d={line} fill="none" stroke="currentColor" strokeOpacity="0.9" strokeWidth="2" vectorEffect="non-scaling-stroke" />}
          {hover && (
            <line x1={hover.f * 1000} y1="0" x2={hover.f * 1000} y2="100" stroke="currentColor"
                  strokeOpacity="0.6" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          )}
        </svg>
        {hover && (
          <div style={hover.f > 0.7 ? { right: `${(1 - hover.f) * 100}%` } : { left: `${hover.f * 100}%` }}
               className="absolute top-0 mx-1.5 px-1.5 py-0.5 rounded bg-white/90 dark:bg-slate-800/90 shadow text-[10px] tabular-nums text-slate-700 dark:text-slate-200 pointer-events-none whitespace-nowrap">
            {distLabel(hover.km)} · {ftM(hover.ele)}
          </div>
        )}
        {yticks.map((t, i) => (
          <div key={`yl${t}`} style={{ top: `${i * 50}%` }}
               className={`absolute right-full mr-1.5 -translate-y-1/2 whitespace-nowrap ${AXIS_LBL}`}>
            {ftM(t)}
          </div>
        ))}
        {xticks.map((f) => (
          <div key={`xl${f}`}
               style={f === 0 ? { left: 0 } : f === 1 ? { right: 0 } : { left: `${f * 100}%` }}
               className={`absolute top-full mt-1 whitespace-nowrap ${AXIS_LBL} ${f !== 0 && f !== 1 ? "-translate-x-1/2" : ""}`}>
            {f === 0 ? "0" : distLabel(dist * f)}
          </div>
        ))}
      </div>
    </div>
  );
}
