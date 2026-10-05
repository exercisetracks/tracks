// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// ActivityRoute — the per-activity route map. Draws one activity's GPS track on
// the self-hosted MapLibre basemap, coloured by the selected metric (heart rate
// / speed / cadence / gradient / grit / flow). Exposes an imperative handle (via
// forwardRef) so the parent activity view can drive hover/cursor sync between
// the map and the activity graphs.
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from "react";
import maplibregl from "maplibre-gl";
import { useTheme } from "../context/ThemeContext";
import MapLibreMap from "./map/MapLibreMap";

const COLOR_MODES = [
  { value: "heartrate", label: "Heart Rate" },
  { value: "speed",     label: "Speed"      },
  { value: "cadence",   label: "Cadence"    },
  { value: "gradient",  label: "Gradient"   },
  { value: "grit",      label: "Grit"       },
  { value: "flow",      label: "Flow"       },
];

// Per-mode data keys on track points
const MODE_KEY = {
  heartrate: "heart_rate",
  speed:     "speed",
  cadence:   "cadence",
  grit:      "grit",
  flow:      "flow",
};

const EMPTY_FC = { type: "FeatureCollection", features: [] };

function lerp(a,b,t) { return a+(b-a)*t; }
function lerpRgb([r0,g0,b0],[r1,g1,b1],t) { return [lerp(r0,r1,t),lerp(g0,g1,t),lerp(b0,b1,t)]; }
function toHex([r,g,b]) { return "#"+[r,g,b].map(v=>Math.round(v).toString(16).padStart(2,"0")).join(""); }
const SPEED_STOPS = [[0,[41,98,255]],[0.4,[16,185,129]],[0.7,[251,191,36]],[1,[239,68,68]]];
const GRAD_STOPS  = [[0,[16,185,129]],[0.5,[255,255,255]],[1,[139,92,246]]];
// Grit: terrain difficulty + effort. Cool→hot intensity mapping.
const GRIT_STOPS  = [[0,[34,197,94]],[0.4,[251,191,36]],[0.7,[249,115,22]],[1,[220,38,38]]];
// Flow: descent smoothness. Red (choppy) → orange → yellow → green (flowing).
const FLOW_STOPS  = [[0,[239,68,68]],[0.4,[249,115,22]],[0.7,[234,179,8]],[1,[34,197,94]]];
function multiStop(stops,t) {
  for(let i=1;i<stops.length;i++){const[t0,c0]=stops[i-1],[t1,c1]=stops[i];if(t<=t1)return toHex(lerpRgb(c0,c1,(t-t0)/(t1-t0)));}
  return toHex(stops[stops.length-1][1]);
}
function colorForMode(mode,t) {
  if (mode === "gradient") return multiStop(GRAD_STOPS, t);
  if (mode === "grit")     return multiStop(GRIT_STOPS, t);
  if (mode === "flow")     return multiStop(FLOW_STOPS, t);
  return multiStop(SPEED_STOPS, t);
}

function haversineM([lat1,lng1],[lat2,lng2]) {
  const R=6371000,f1=lat1*Math.PI/180,f2=lat2*Math.PI/180,df=(lat2-lat1)*Math.PI/180,dl=(lng2-lng1)*Math.PI/180;
  const a=Math.sin(df/2)**2+Math.cos(f1)*Math.cos(f2)*Math.sin(dl/2)**2;
  return 2*R*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
}
function pct(arr,p){const s=[...arr].sort((a,b)=>a-b);const idx=(p/100)*(s.length-1);const lo=Math.floor(idx);return s[lo]+(s[Math.ceil(idx)]-s[lo])*(idx-lo);}

function buildSegments(track, mode) {
  if (!track?.length) return [];
  const segments = [];
  if (mode === "gradient") {
    for (let i=0;i<track.length-1;i++){
      const a=track[i],b=track[i+1];
      if(a.altitude==null||b.altitude==null)continue;
      const dist=haversineM([a.lat,a.lng],[b.lat,b.lng]);
      const grade=dist<3?0:Math.max(-0.2,Math.min(0.2,(b.altitude-a.altitude)/dist));
      segments.push({pts:[[a.lat,a.lng],[b.lat,b.lng]],t:(grade+0.2)/0.4});
    }
  } else {
    const key = MODE_KEY[mode] ?? "speed";
    const vals=track.map(p=>p[key]).filter(v=>v!=null);
    if(!vals.length)return[];
    const lo=pct(vals,5),hi=pct(vals,95),range=hi-lo||1;
    for(let i=0;i<track.length-1;i++){
      const a=track[i],b=track[i+1];
      const v=((a[key]??0)+(b[key]??0))/2;
      segments.push({pts:[[a.lat,a.lng],[b.lat,b.lng]],t:Math.max(0,Math.min(1,(v-lo)/range))});
    }
  }
  return segments;
}

// Build a GeoJSON FeatureCollection of coloured route segments. Each segment is a
// 2-point LineString carrying a `color` prop, rendered with a data-driven
// line-color — MapLibre's equivalent of the old per-segment Leaflet polylines.
function buildRouteFC(track, mode) {
  const segs = buildSegments(track, mode);
  if (!segs.length) {
    const coords = track.map(p => [p.lng, p.lat]);
    if (coords.length < 2) return EMPTY_FC;
    return { type: "FeatureCollection", features: [
      { type: "Feature", geometry: { type: "LineString", coordinates: coords }, properties: { color: "#ff7700" } },
    ] };
  }
  return {
    type: "FeatureCollection",
    features: segs.map(({ pts, t }) => ({
      type: "Feature",
      geometry: { type: "LineString", coordinates: pts.map(([lat, lng]) => [lng, lat]) },
      properties: { color: colorForMode(mode, t) },
    })),
  };
}

// Imperative cursor API: parent calls `ref.current.setCursor([lat,lng] | null)`.
// Bypasses React state entirely so chart hovers don't trigger map re-renders.
const ActivityRoute = forwardRef(function ActivityRoute(
  { track = [], height = 480 },
  ref,
) {
  const { colorScheme } = useTheme();
  const [mode,      setMode]      = useState("heartrate");
  const [collapsed, setCollapsed] = useState(false);
  const [ready,     setReady]     = useState(false);

  const mapRef       = useRef(null);
  const markerRef    = useRef(null);   // cursor marker (DOM overlay; survives setStyle)
  const routeDataRef = useRef(EMPTY_FC);
  const didFitRef    = useRef(false);

  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  const isDark = colorScheme === "dark" || (colorScheme === "system" && systemDark);

  const gpxTrack = track.filter(p => p.lat && p.lng);

  const availableModes = COLOR_MODES.filter(m => {
    if (m.value === "gradient") return gpxTrack.some(p => p.altitude != null);
    const key = MODE_KEY[m.value];
    return key ? gpxTrack.some(p => p[key] != null) : false;
  });

  // (Re)add the route source + layer — called on first load and after every
  // theme swap (setStyle drops custom sources/layers).
  const ensureRouteLayer = (map) => {
    if (!map.getSource("route")) {
      // tolerance:0 disables geojson-vt simplification. The route is built as many
      // tiny 2-point coloured segments; with the default tolerance, low-zoom tiles
      // simplify/drop them and the line breaks up and vanishes when zoomed out.
      map.addSource("route", { type: "geojson", data: routeDataRef.current, tolerance: 0, buffer: 64 });
      map.addLayer({
        id: "route-line",
        type: "line",
        source: "route",
        layout: { "line-cap": "round", "line-join": "round" },
        paint: { "line-color": ["get", "color"], "line-width": 3, "line-opacity": 0.9 },
      });
    } else {
      map.getSource("route").setData(routeDataRef.current);
    }
  };

  const handleReady = useCallback((map) => {
    mapRef.current = map;
    if (!markerRef.current) {
      const el = document.createElement("div");
      Object.assign(el.style, {
        width: "14px", height: "14px", borderRadius: "50%",
        background: "#fff", border: "2px solid #3b82f6",
        boxShadow: "0 0 0 3px rgba(59,130,246,0.3)",
        opacity: "0", pointerEvents: "none",
      });
      markerRef.current = new maplibregl.Marker({ element: el }).setLngLat([0, 0]).addTo(map);
    }
    ensureRouteLayer(map);
    setReady(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useImperativeHandle(ref, () => ({
    setCursor: (latlng) => {
      const m = markerRef.current;
      if (!m) return;
      if (!latlng) { m.getElement().style.opacity = "0"; return; }
      const [lat, lng] = latlng;
      m.setLngLat([lng, lat]);
      m.getElement().style.opacity = "1";
    },
  }), []);

  // Rebuild + push route data whenever track or colour mode changes.
  useEffect(() => {
    routeDataRef.current = buildRouteFC(gpxTrack, mode);
    const map = mapRef.current;
    if (ready && map?.getSource("route")) map.getSource("route").setData(routeDataRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, mode, track]);

  // Fit to the track once, when it first becomes available.
  useEffect(() => {
    if (!ready || didFitRef.current || !gpxTrack.length) return;
    let minLat=90,maxLat=-90,minLng=180,maxLng=-180;
    for (const p of gpxTrack) {
      if (p.lat<minLat) minLat=p.lat; if (p.lat>maxLat) maxLat=p.lat;
      if (p.lng<minLng) minLng=p.lng; if (p.lng>maxLng) maxLng=p.lng;
    }
    didFitRef.current = true;
    mapRef.current.fitBounds([[minLng,minLat],[maxLng,maxLat]], { padding: 28, animate: false });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, track]);

  return (
    <div style={{ height }} className="relative rounded-xl overflow-hidden bg-slate-900">
      <MapLibreMap
        theme={isDark ? "dark" : "light"}
        onReady={handleReady}
        initialZoom={2}
        className="w-full h-full"
      />

      {/* Controls */}
      <div className="absolute top-3 right-3 z-[1000]">
        <div className="bg-slate-900/85 backdrop-blur rounded-xl shadow-lg text-white text-xs overflow-hidden" style={{ minWidth: collapsed ? "auto" : 180 }}>
          <div className="px-2.5 py-1.5 font-semibold text-slate-200 flex items-center justify-between gap-3"
               style={{ borderBottom: collapsed ? "none" : "1px solid rgba(51,65,85,0.5)" }}>
            {!collapsed && <span>GPS Route</span>}
            <button onClick={() => setCollapsed(c => !c)}
              className="ml-auto text-slate-400 hover:text-slate-200 transition-colors leading-none"
              title={collapsed ? "Expand controls" : "Collapse controls"}>
              {collapsed ? "⊕" : "⊖"}
            </button>
          </div>
          {!collapsed && availableModes.length > 0 && (
            <div className="px-2.5 py-1.5">
              <p className="text-slate-400 mb-1 uppercase tracking-wider" style={{ fontSize: 10 }}>Color</p>
              <div className="flex flex-wrap gap-1">
                {availableModes.map(m => (
                  <button key={m.value} onClick={() => setMode(m.value)}
                    className={`px-1.5 py-0.5 rounded-full border transition-colors ${mode === m.value ? "bg-blue-500 border-blue-500 text-white" : "border-slate-600 text-slate-300 hover:border-slate-400"}`}>
                    {m.label}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Legend */}
      {availableModes.length > 0 && (() => {
        const gradient =
          mode === "gradient" ? "linear-gradient(to right, #10b981, #ffffff, #8b5cf6)" :
          mode === "grit"     ? "linear-gradient(to right, #22c55e, #fbbf24, #f97316, #dc2626)" :
          mode === "flow"     ? "linear-gradient(to right, #ef4444, #f97316, #eab308, #22c55e)" :
          /* default (speed/HR/cadence) */ "linear-gradient(to right, #2962ff, #10b981, #fbbf24, #ef4444)";
        const labels =
          mode === "gradient" ? ["Descent", "Flat", "Climb"] :
          mode === "grit"     ? ["Easy", "Hard"] :
          mode === "flow"     ? ["Choppy", "Flowing"] :
          ["Low", "High"];
        return (
          <div className="absolute bottom-6 left-3 z-[1000] pointer-events-none">
            <div className="bg-slate-900/80 backdrop-blur rounded-lg px-2.5 py-1.5 text-xs text-white" style={{ minWidth: 130 }}>
              <div className="h-2 rounded-full mb-1" style={{ background: gradient }} />
              <div className="flex justify-between gap-3">
                {labels.map(l => <span key={l}>{l}</span>)}
              </div>
            </div>
          </div>
        );
      })()}
    </div>
  );
});

export default ActivityRoute;
