// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Point-info panel for the map page. Renders the details usePointInfo gathered
// for a tapped location — coordinates, elevation, current weather (decoded from
// WMO codes via wmo() below), place name, and any nearby trails — with actions
// to route here or dismiss.
import { useState } from "react";

// WMO weather code → label.
function wmo(code, isDay = 1) {
  const c = Number(code);
  if (c === 0) return isDay ? "Clear" : "Clear night";
  if (c === 1) return "Mainly clear";
  if (c === 2) return "Partly cloudy";
  if (c === 3) return "Overcast";
  if (c === 45 || c === 48) return "Fog";
  if (c >= 51 && c <= 57) return "Drizzle";
  if (c >= 61 && c <= 67) return "Rain";
  if (c >= 71 && c <= 77) return "Snow";
  if (c >= 80 && c <= 82) return "Rain showers";
  if (c === 85 || c === 86) return "Snow showers";
  if (c >= 95) return "Thunderstorm";
  return "—";
}

const COMPASS = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
const compass = (deg) => (deg == null ? "" : COMPASS[Math.round(deg / 45) % 8]);

const OWNERSHIP_BADGE = {
  public:     "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300",
  private:    "bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300",
  restricted: "bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300",
};

function PointInfoPanel({ point, trails, pois, activities = [], land, info, loading, imperial, onClose, onSelectRoute, onSelectActivity, onHoverActivity }) {
  const [copied, setCopied] = useState(false);
  if (!point) return null;

  const latlng = `${point.lat.toFixed(5)}, ${point.lng.toFixed(5)}`;
  const copy = () => {
    navigator.clipboard?.writeText(latlng).then(() => {
      setCopied(true); setTimeout(() => setCopied(false), 1500);
    }).catch(() => {});
  };

  const temp = (c) => c == null ? "—" : (imperial ? `${Math.round(c * 9 / 5 + 32)}°F` : `${Math.round(c)}°C`);
  const wind = (m) => m == null ? "—" : (imperial ? `${Math.round(m * 2.23694)} mph` : `${Math.round(m)} m/s`);
  const precip = (mm) => mm == null ? "—" : (imperial ? `${(mm / 25.4).toFixed(2)} in` : `${mm.toFixed(1)} mm`);
  const elev = () => {
    if (!info || info.elevation_ft == null) return null;
    return imperial ? `${info.elevation_ft.toLocaleString()} ft`
                    : `${Math.round(info.elevation_m).toLocaleString()} m`;
  };
  const dow = (iso) => new Date(iso + "T00:00").toLocaleDateString(undefined, { weekday: "short" });

  const cur = info?.weather?.current;
  const daily = info?.weather?.daily || [];

  return (
    // Sits below the search bar (top-left geocoder); z-10 keeps it under the
    // search control + its live-results dropdown (which is raised in index.css).
    <div className="absolute top-16 left-3 z-10 w-[300px] max-h-[calc(100%-5rem)] overflow-y-auto
                    bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-slate-200 dark:border-slate-700 text-sm">
      {/* Header: coordinates + copy + close */}
      <div className="flex items-center justify-between gap-2 px-2.5 py-2 border-b border-slate-100 dark:border-slate-800">
        <button onClick={copy} title="Copy latitude, longitude"
          className="flex items-center gap-1.5 font-mono text-[12px] text-slate-700 dark:text-slate-200 hover:text-accent-600 min-w-0">
          <svg className="w-3.5 h-3.5 shrink-0 text-slate-400" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M8 7v8a2 2 0 002 2h6M8 7V5a2 2 0 012-2h4.586a1 1 0 01.707.293l4.414 4.414a1 1 0 01.293.707V15a2 2 0 01-2 2h-2M8 7H6a2 2 0 00-2 2v10a2 2 0 002 2h8a2 2 0 002-2v-2" />
          </svg>
          <span className="truncate">{copied ? "Copied!" : latlng}</span>
        </button>
        <button onClick={onClose} className="shrink-0 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300">
          <svg className="w-4 h-4" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div className="px-2.5 py-2 space-y-3">
        {/* Elevation + Land (side by side) */}
        <div className="flex gap-4">
          <div className="min-w-0">
            <div className="text-[10px] uppercase tracking-wide text-slate-400">Elevation</div>
            <div className="font-semibold text-slate-800 dark:text-slate-100">
              {loading && !info ? "…" : (elev() || "—")}
            </div>
          </div>
          {land && (
            <div className="min-w-0">
              <div className="text-[10px] uppercase tracking-wide text-slate-400">Land</div>
              <div className="flex items-center gap-1.5 flex-wrap">
                <span className="font-semibold text-slate-800 dark:text-slate-100 truncate">{land.type}</span>
                {land.ownership && (
                  <span className={`px-1 py-0.5 rounded text-[10px] font-medium ${OWNERSHIP_BADGE[land.ownership] || ""}`}>
                    {land.ownership}
                  </span>
                )}
              </div>
              {land.name && <div className="text-[11px] text-slate-500 dark:text-slate-400 truncate">{land.name}</div>}
            </div>
          )}
        </div>

        {/* POI(s) at the click */}
        {pois.length > 0 && (
          <div>
            <div className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Point of interest</div>
            {pois.map((p, i) => (
              <div key={i} className="text-slate-700 dark:text-slate-200">
                <span className="font-medium">{p.name}</span>
                {p.kind_detail && <span className="text-slate-400"> · {String(p.kind_detail).replace(/_/g, " ")}</span>}
                {p.ele_ft != null && <span className="text-slate-400"> · {Math.round(p.ele_ft).toLocaleString()} ft</span>}
              </div>
            ))}
          </div>
        )}

        {/* Nearby trails — long-distance routes are clickable (→ detail panel) */}
        {trails.length > 0 && (
          <div>
            <div className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Nearby trails</div>
            <div className="flex flex-wrap gap-1">
              {trails.map((t, i) => {
                const label = t.name || (t.kind ? String(t.kind).replace(/_/g, " ") : "trail");
                const clickable = t.route && t.route_id && onSelectRoute;
                if (clickable) {
                  return (
                    <button key={i} onClick={() => onSelectRoute(t.route_id, t.section_id)}
                      title="View trail sections, distance & elevation"
                      className="px-1 py-0.5 rounded text-[11px] font-medium inline-flex items-center gap-1
                                 bg-accent-100 text-accent-700 hover:bg-accent-200 dark:bg-accent-900/40 dark:text-accent-300 dark:hover:bg-accent-900/70 transition-colors">
                      {label}
                      <svg className="w-2.5 h-2.5 opacity-70" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                      </svg>
                    </button>
                  );
                }
                return (
                  <span key={i} className={`px-1 py-0.5 rounded text-[11px] ${t.route
                    ? "bg-accent-100 text-accent-700 dark:bg-accent-900/40 dark:text-accent-300 font-medium"
                    : "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300"}`}>
                    {label}
                  </span>
                );
              })}
            </div>
          </div>
        )}

        {/* Nearby past activities — clickable → inspect / turn into a track */}
        {activities.length > 0 && (
          <div>
            <div className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Nearby activities</div>
            <div className="flex flex-col gap-1">
              {activities.map((a) => (
                <button key={a.id} onClick={() => onSelectActivity?.(a.id)}
                  onMouseEnter={() => onHoverActivity?.(a.id)}
                  onMouseLeave={() => onHoverActivity?.(null)}
                  title="View this activity & turn it into a track"
                  className="flex items-center justify-between gap-2 px-1.5 py-1 rounded text-[11px]
                             bg-accent-100 text-accent-700 hover:bg-accent-200 dark:bg-accent-900/40 dark:text-accent-300 dark:hover:bg-accent-900/70 transition-colors">
                  <span className="truncate font-medium">{a.name || a.sport || "Activity"}</span>
                  <span className="shrink-0 inline-flex items-center gap-1 text-accent-600/80 dark:text-accent-300/80">
                    {a.distance_m != null && (imperial ? `${(a.distance_m / 1609.34).toFixed(1)} mi` : `${(a.distance_m / 1000).toFixed(1)} km`)}
                    <svg className="w-2.5 h-2.5" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                    </svg>
                  </span>
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Weather */}
        <div>
          <div className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Weather</div>
          {loading && !info ? (
            <div className="text-slate-400 text-xs">Loading forecast…</div>
          ) : info?.weather_disabled ? (
            <div className="text-slate-400 text-xs">Weather is off — turn it on in Settings → Privacy</div>
          ) : !cur ? (
            <div className="text-slate-400 text-xs">Forecast unavailable</div>
          ) : (
            <>
              <div>
                <div className="font-semibold text-slate-800 dark:text-slate-100">
                  {temp(cur.temperature_c)}
                  <span className="font-normal text-slate-400 text-xs"> feels {temp(cur.apparent_c)}</span>
                </div>
                <div className="text-[11px] text-slate-500 dark:text-slate-400">
                  {wmo(cur.weather_code, cur.is_day)} · {wind(cur.wind_mps)} {compass(cur.wind_direction)} · {cur.humidity_pct}% RH
                </div>
              </div>
              {daily.length > 0 && (
                <div className="mt-2 grid grid-cols-7 gap-1 text-center">
                  {daily.slice(0, 7).map((d, i) => (
                    <div key={i} className="text-[10px]">
                      <div className="text-slate-400">{i === 0 ? "Today" : dow(d.date)}</div>
                      <div className="text-[9px] leading-tight text-slate-500 dark:text-slate-400">{wmo(d.weather_code, 1)}</div>
                      <div className="text-slate-700 dark:text-slate-200 font-medium">{temp(d.temp_max_c)}</div>
                      <div className="text-slate-400">{temp(d.temp_min_c)}</div>
                      {d.precip_prob_pct > 0 && (
                        <div className="text-sky-500">{d.precip_prob_pct}%</div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}

export default PointInfoPanel;
