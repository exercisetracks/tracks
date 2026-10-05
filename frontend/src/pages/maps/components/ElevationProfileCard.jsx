// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Shared elevation-profile chart used by the long-trail detail panel
// (RouteDetailPanel) and the custom-track detail panel (CustomTrackDetail).
//
// Axis labels are crisp HTML overlays (not SVG <text>): the chart path is drawn
// in a non-uniformly-stretched viewBox (preserveAspectRatio="none"), which would
// distort and clip SVG text. The SVG holds only the area/line/gridlines; the left
// + bottom gutters carry readable, unstretched labels.
const AXIS_LBL = "text-[10px] tabular-nums text-slate-600 dark:text-slate-300";

export default function ElevationProfileCard({ profile, imperial }) {
  const pts = (profile?.points || []).filter((p) => p.ele_m != null);
  if (pts.length < 2) {
    return (
      <div className="h-[150px] flex items-center justify-center text-xs text-slate-400 px-3.5 text-center">
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
  const line = pts.map((p, i) => `${i ? "L" : "M"}${sx(p.d_km).toFixed(1)},${sy(p.ele_m).toFixed(2)}`).join(" ");
  const area = `${line} L1000,100 L0,100 Z`;
  const yticks = [1, 0.5, 0].map((f) => lo + (hi - lo) * f);
  const xticks = [0, 0.25, 0.5, 0.75, 1];
  const ftM = (m) => imperial ? `${Math.round(m * 3.28084).toLocaleString()} ft` : `${Math.round(m)} m`;
  const distLabel = (km) => {
    const v = imperial ? km * 0.621371 : km;
    return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${imperial ? "mi" : "km"}`;
  };

  return (
    <div className="relative w-full h-[150px] pl-14 pr-2 pt-2 pb-5 text-accent-600 dark:text-accent-400">
      <div className="relative w-full h-full">
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
          <path d={line} fill="none" stroke="currentColor" strokeOpacity="0.9" strokeWidth="2" vectorEffect="non-scaling-stroke" />
        </svg>
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
