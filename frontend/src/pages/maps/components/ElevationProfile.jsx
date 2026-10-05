// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Elevation profile for the route builder — a small area chart of elevation vs
// distance, drawn from the snapped route's 3-D coordinates (or DEM-sampled
// elevation for off-trail lines). Pure SVG, no chart lib.

const VW = 1000;   // viewBox width
const VH = 150;    // viewBox height
const PAD = { l: 38, r: 10, t: 12, b: 20 };

export default function ElevationProfile({ elevation, snapping, onClose }) {
  if (!elevation || !elevation.hasEle) return null;

  const pts = elevation.points.filter((p) => p.e != null);
  if (pts.length < 2) return null;

  const dist = elevation.distanceKm || pts[pts.length - 1].d || 1;
  let lo = elevation.min ?? Math.min(...pts.map((p) => p.e));
  let hi = elevation.max ?? Math.max(...pts.map((p) => p.e));
  if (hi - lo < 10) { hi += 5; lo -= 5; } // avoid a flat/zero-range chart

  const sx = (d) => PAD.l + (d / dist) * (VW - PAD.l - PAD.r);
  const sy = (e) => VH - PAD.b - ((e - lo) / (hi - lo)) * (VH - PAD.t - PAD.b);

  const line = pts.map((p, i) => `${i ? "L" : "M"}${sx(p.d).toFixed(1)},${sy(p.e).toFixed(1)}`).join(" ");
  const area = `${line} L${sx(dist).toFixed(1)},${VH - PAD.b} L${PAD.l},${VH - PAD.b} Z`;

  // A few elevation gridlines (in metres).
  const yTicks = _ticks(lo, hi, 3);

  return (
    <div className="absolute bottom-0 inset-x-0 z-20 bg-white/55 dark:bg-slate-900/55 backdrop-blur-sm border-t border-white/40 dark:border-slate-700/50 text-accent-600 dark:text-accent-400">
      <div className="flex items-center justify-between px-3.5 pt-1">
        <div className="text-[10px] font-semibold uppercase tracking-wide text-slate-500/80 dark:text-slate-400/80">
          Elevation Profile
        </div>
        <div className="flex items-center gap-2 text-[11px] text-slate-600/90 dark:text-slate-300/90">
          <span>{dist.toFixed(1)} km</span>
          <span>↑{elevation.gain} m</span>
          <span>↓{elevation.loss} m</span>
          {snapping && <span className="w-2.5 h-2.5 border-[1.5px] border-accent-500 border-t-transparent rounded-full animate-spin" />}
          {onClose && (
            <button onClick={onClose} className="text-slate-500/70 hover:text-slate-700 dark:hover:text-slate-200 leading-none text-base px-1">×</button>
          )}
        </div>
      </div>
      <svg viewBox={`0 0 ${VW} ${VH}`} preserveAspectRatio="none" className="w-full h-[110px]">
        <defs>
          <linearGradient id="elevFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="currentColor" stopOpacity="0.4" />
            <stop offset="100%" stopColor="currentColor" stopOpacity="0.04" />
          </linearGradient>
        </defs>
        {yTicks.map((t) => (
          <g key={t}>
            <line x1={PAD.l} y1={sy(t)} x2={VW - PAD.r} y2={sy(t)} stroke="currentColor" strokeOpacity="0.14" strokeWidth="1" />
            <text x={PAD.l - 5} y={sy(t) + 3} textAnchor="end" fontSize="11" className="fill-slate-500/70">{Math.round(t)}</text>
          </g>
        ))}
        <path d={area} fill="url(#elevFill)" />
        <path d={line} fill="none" stroke="currentColor" strokeOpacity="0.9" strokeWidth="2" vectorEffect="non-scaling-stroke" />
        <text x={PAD.l} y={VH - 5} textAnchor="start" fontSize="11" className="fill-slate-500/70">0</text>
        <text x={VW - PAD.r} y={VH - 5} textAnchor="end" fontSize="11" className="fill-slate-500/70">{dist.toFixed(1)} km</text>
      </svg>
    </div>
  );
}

function _ticks(lo, hi, n) {
  const out = [];
  for (let i = 0; i <= n; i++) out.push(lo + ((hi - lo) * i) / n);
  return out;
}
