// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// Activity heatmap: renders all of the user's activity tracks as a WebGL glow
// layer over the self-hosted MapLibre basemap, coloured by the selected mode
// (frequency / pace / heart rate / gradient). Fetches aggregated track points
// from the API and feeds them to HeatmapGlowLayer.
//
// What is shown is the dashboard's choice, not this card's: the period pills
// set `after` and the sport-breakdown pie sets `sport`, the same two filters
// every other card on the page follows. It once had its own sport picker,
// date range and "Fit to data" button, which let it disagree with the page
// around it; now the only control it owns is the colouring mode. The camera
// re-frames whenever the filter changes, so the opening view is always the
// activities in the window rather than wherever the last filter left it.
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../api/client";
import { useTheme } from "../../context/ThemeContext";
import MapLibreMap from "../map/MapLibreMap";
import { HeatmapGlowLayer, buildHeatmapVerts, glowStyleForMode, legendStops } from "../map/HeatmapGlowLayer";

const MODES = [
  { value: "frequency", label: "Frequency"  },
  { value: "pace",      label: "Pace"       },
  { value: "heartrate", label: "Heart Rate" },
  { value: "gradient",  label: "Gradient"   },
];

// ── Legend ────────────────────────────────────────────────────────────────────

function Legend({ mode, theme }) {
  if (mode === "frequency") return null;

  const isGrad = mode === "gradient";
  const labels = isGrad ? ["Descent", "Flat", "Climb"] : mode === "heartrate" ? ["Low HR", "High HR"] : ["Slow", "Fast"];
  // The colours the map is drawing in this theme, so the key matches the lines.
  const stops  = legendStops(mode, theme);

  return (
    // bottom-12: clear of the map credits' "i" button in the corner below.
    <div className="absolute bottom-12 right-2 z-[1000] pointer-events-none" style={{ minWidth: 130 }}>
      <div className="bg-slate-900/80 backdrop-blur rounded-lg px-2.5 py-1.5 text-xs text-white">
        <div className="h-2 rounded-full mb-1" style={{ background: `linear-gradient(to right, ${stops.join(", ")})` }} />
        <div className="flex justify-between gap-3">{labels.map(l => <span key={l}>{l}</span>)}</div>
      </div>
    </div>
  );
}

// ── Controls panel ──────────────────────────────────────────────────────────────

function Controls({ mode, onModeChange }) {
  const [open, setOpen] = useState(true);

  return (
    <div className="absolute top-3 right-3 z-[1000]" style={{ maxWidth: 220 }}>
      <div className="bg-slate-900/85 backdrop-blur rounded-xl shadow-lg text-white text-xs overflow-hidden">
        <button
          onClick={() => setOpen(o => !o)}
          className="w-full flex items-center justify-between px-2.5 py-1.5 font-semibold tracking-wide text-slate-200 hover:bg-slate-700/60 transition-colors"
        >
          <span>Heatmap</span>
          <span className="text-slate-400">{open ? "▲" : "▼"}</span>
        </button>

        {open && (
          <div className="px-2.5 pb-2.5 space-y-3">
            {/* Viz mode */}
            <div>
              <p className="text-slate-400 mb-1 uppercase tracking-wider" style={{ fontSize: 10 }}>Mode</p>
              <div className="flex flex-wrap gap-1">
                {MODES.map(m => (
                  <button
                    key={m.value}
                    onClick={() => onModeChange(m.value)}
                    className={`px-1.5 py-0.5 rounded-full border transition-colors ${
                      mode === m.value
                        ? "bg-orange-500 border-orange-500 text-white"
                        : "border-slate-600 text-slate-300 hover:border-slate-400"
                    }`}
                  >
                    {m.label}
                  </button>
                ))}
              </div>
            </div>

          </div>
        )}
      </div>
    </div>
  );
}

// ── Map + WebGL glow layer ────────────────────────────────────────────────────

function HeatmapMap({ tracks, mode, fitTracks, fitKey, theme }) {
  const mapRef    = useRef(null);
  const glowRef   = useRef(null);
  const vertsRef  = useRef({ verts: null, count: 0 });
  const lastFit   = useRef(-1);
  const [ready, setReady] = useState(false);
  // Read when a theme swap reloads the style and re-runs handleReady.
  const themeRef  = useRef(theme);
  themeRef.current = theme;

  // (Re)create the custom glow layer — also fires after a theme/style swap, which
  // drops custom layers, so we rebuild and re-upload the current geometry.
  const handleReady = useCallback((map) => {
    mapRef.current = map;
    // Idempotent: onReady can fire again (StrictMode, theme/style swap, which
    // drops custom layers). Only add the layer if it isn't already present.
    let layer = glowRef.current;
    if (!map.getLayer("heatmap-glow")) {
      layer = new HeatmapGlowLayer("heatmap-glow");
      const beforeId = map.getLayer("labels_place") ? "labels_place" : undefined;
      try { map.addLayer(layer, beforeId); } catch { try { map.addLayer(layer); } catch {} }
      glowRef.current = layer;
    }
    if (layer) {
      layer.setStyle(glowStyleForMode(mode));
      layer.setSubtractive(themeRef.current === "light");
      if (vertsRef.current.verts) layer.setData(vertsRef.current.verts, vertsRef.current.count);
    }
    setReady(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Rebuild the vertex buffer when the data or mode changes.
  useEffect(() => {
    const built = buildHeatmapVerts(tracks, mode, theme);
    vertsRef.current = built;
    if (ready && glowRef.current) {
      glowRef.current.setStyle(glowStyleForMode(mode));
      glowRef.current.setSubtractive(theme === "light");
      glowRef.current.setData(built.verts, built.count);
    }
  }, [ready, tracks, mode, theme]);

  // Fit to data when fitKey bumps — once per filter, see maybeAutoFit.
  useEffect(() => {
    if (!ready || fitKey === lastFit.current || !fitTracks?.length) return;
    lastFit.current = fitKey;
    let minLat=90, maxLat=-90, minLng=180, maxLng=-180;
    for (const tr of fitTracks) for (const p of tr) {
      if (p[0]<minLat) minLat=p[0]; if (p[0]>maxLat) maxLat=p[0];
      if (p[1]<minLng) minLng=p[1]; if (p[1]>maxLng) maxLng=p[1];
    }
    if (minLat <= maxLat) {
      mapRef.current.fitBounds([[minLng,minLat],[maxLng,maxLat]], { padding: 32, maxZoom: 15, animate: false });
    }
  }, [ready, fitKey, fitTracks]);

  return <MapLibreMap theme={theme} onReady={handleReady} initialZoom={2} className="absolute inset-0" />;
}

// ── Main component ────────────────────────────────────────────────────────────

export default function ActivityHeatmap({ height = 420, sport = "", after = null }) {
  const { colorScheme } = useTheme();
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const isDark = colorScheme === "dark" || (colorScheme === "system" && systemDark);

  const [mode,   setMode]   = useState("frequency");

  const [loading,       setLoading]       = useState(true);
  const [displayTracks, setDisplayTracks] = useState([]);
  const [fitTracks,     setFitTracks]     = useState([]);
  const [fitKey,        setFitKey]        = useState(0);

  // Heatmap response cache, keyed by (mode|sport|after), bounded LRU.
  const cacheRef      = useRef(new Map());
  const fittedForRef  = useRef(null);
  const fetchTokenRef = useRef(0);

  function cacheKey(m, s, a) { return `${m}|${s}|${a ?? ""}`; }
  function cacheGet(key) { return cacheRef.current.get(key); }
  function cacheSet(key, value) {
    const c = cacheRef.current;
    if (c.has(key)) c.delete(key);
    c.set(key, value);
    while (c.size > 32) c.delete(c.keys().next().value);
  }

  // Frame once per filter (period + sport), on the first data that arrives for
  // it. Not on a mode change: switching Frequency to Pace is a question about
  // the same place, and yanking the camera back would throw away the user's
  // pan. An empty result leaves the camera alone rather than flying to 0,0.
  function maybeAutoFit(tracks) {
    const filter = `${sport}|${after ?? ""}`;
    if (fittedForRef.current === filter || !tracks?.length) return;
    fittedForRef.current = filter;
    setFitTracks(tracks);
    setFitKey(k => k + 1);
  }

  function paramsFor(m) {
    const params = { mode: m };
    if (sport) params.sport = sport;
    if (after) params.after = after;
    return params;
  }

  // Active-mode fetch. Cached responses skip the network.
  useEffect(() => {
    const token  = ++fetchTokenRef.current;
    const key    = cacheKey(mode, sport, after);
    const cached = cacheGet(key);

    if (cached !== undefined) {
      setDisplayTracks(cached);
      setLoading(false);
      maybeAutoFit(cached);
      return;
    }

    setLoading(true);
    api.getHeatmap(paramsFor(mode))
      .catch(() => [])
      .then(tracks => {
        if (token !== fetchTokenRef.current) return; // stale
        cacheSet(key, tracks);
        setDisplayTracks(tracks);
        setLoading(false);
        maybeAutoFit(tracks);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, sport, after]);

  // Background-prefetch the other modes during idle time.
  useEffect(() => {
    const idle = window.requestIdleCallback ?? ((cb) => setTimeout(cb, 200));
    const cancel = window.cancelIdleCallback ?? clearTimeout;
    const handles = [];

    for (const { value: m } of MODES) {
      if (m === mode) continue;
      const key = cacheKey(m, sport, after);
      if (cacheGet(key) !== undefined) continue;
      const handle = idle(() => {
        api.getHeatmap(paramsFor(m)).then(t => cacheSet(key, t)).catch(() => cacheSet(key, []));
      });
      handles.push(handle);
    }
    return () => { handles.forEach(h => cancel(h)); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, sport, after]);

  return (
    <div style={{ height }} className="relative rounded-xl overflow-hidden">
      <HeatmapMap
        tracks={displayTracks}
        mode={mode}
        fitTracks={fitTracks}
        fitKey={fitKey}
        theme={isDark ? "dark" : "light"}
      />

      {/* Loading overlay */}
      {loading && (
        <div className="absolute inset-0 flex items-center justify-center bg-slate-900/50 z-[999] rounded-xl">
          <span className="text-sm text-slate-300 animate-pulse">Loading…</span>
        </div>
      )}

      {/* Empty-data message */}
      {!loading && !displayTracks.length && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none z-[998]">
          <span className="text-sm text-slate-400 bg-slate-900/70 px-2.5 py-1 rounded-full">
            No GPS data for this filter
          </span>
        </div>
      )}

      <Controls mode={mode} onModeChange={setMode} />
      <Legend mode={mode} theme={isDark ? "dark" : "light"} />
    </div>
  );
}
