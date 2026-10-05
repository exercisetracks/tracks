// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useState, useEffect, useRef, useCallback } from "react";
import { api } from "../../../api/client";

// useWildfires — feeds the live wildfire + smoke geojson sources and owns the
// fire-detail selection. Fetching is double-gated: the account-level
// `wildfire_enabled` opt-in (third-party NIFC/CWFIS/NOAA queries) AND the
// single "Wildfires & Smoke" layer toggle being on — so no external request
// ever fires just because the map page opened. Data refreshes on an interval
// while the toggle stays on; the last payload is re-fed after style reloads
// (which reset geojson sources), matching the useCustomTracks pattern.
const FIRES_REFRESH_MS = 10 * 60_000;   // matches the backend's 10-min cache
const SMOKE_REFRESH_MS = 30 * 60_000;

// Fire click targets: the point (dot + glow) and the perimeter OUTLINE — not
// the perimeter fill, so clicking around inside a large burn area still opens
// the regular point-info panel.
export const WILDFIRE_HIT_LAYERS = [
  "wildfire_points_core", "wildfire_points_glow", "wildfire_perimeter_line",
];

export function useWildfires(map, ready, enabled, liveOn) {
  const [selected, setSelected] = useState(null);   // fire properties | null
  const firesData = useRef(null);   // {fires, perimeters}
  const smokeData = useRef(null);

  const feedFires = useCallback((data) => {
    if (data) firesData.current = data;
    if (!map || !firesData.current) return;
    try {
      map.getSource("wildfire_points")?.setData(firesData.current.fires);
      map.getSource("wildfire_perimeters")?.setData(firesData.current.perimeters);
    } catch { /* sources not ready */ }
  }, [map]);

  const feedSmoke = useCallback((data) => {
    if (data) smokeData.current = data;
    if (!map || !smokeData.current) return;
    try {
      map.getSource("smoke_plumes")?.setData(smokeData.current);
    } catch { /* source not ready */ }
  }, [map]);

  // Fires: fetch now + refresh while the toggle is on. Data is kept (not
  // cleared) when toggled off — visibility hides the layers, and flipping back
  // on shows the cached picture instantly while a fresh fetch runs.
  useEffect(() => {
    if (!ready || !map || !enabled || !liveOn) return undefined;
    let cancelled = false;
    const load = () => api.getWildfires()
      .then((d) => { if (!cancelled && d) feedFires(d); })
      .catch(() => { /* backend logs; stale/empty data stays */ });
    load();
    const t = setInterval(load, FIRES_REFRESH_MS);
    return () => { cancelled = true; clearInterval(t); };
  }, [ready, map, enabled, liveOn, feedFires]);

  // Smoke: same lifecycle, slower cadence (analyst-drawn, few updates a day).
  useEffect(() => {
    if (!ready || !map || !enabled || !liveOn) return undefined;
    let cancelled = false;
    const load = () => api.getWildfireSmoke()
      .then((d) => { if (!cancelled && d) feedSmoke(d); })
      .catch(() => { /* ignore */ });
    load();
    const t = setInterval(load, SMOKE_REFRESH_MS);
    return () => { cancelled = true; clearInterval(t); };
  }, [ready, map, enabled, liveOn, feedSmoke]);

  // Re-feed after a style reload dropped the geojson sources.
  useEffect(() => {
    if (!ready || !map) return undefined;
    const onStyle = () => { feedFires(null); feedSmoke(null); };
    map.on("styledata", onStyle);
    return () => map.off("styledata", onStyle);
  }, [ready, map, feedFires, feedSmoke]);

  // Click a fire (dot/glow/perimeter edge) → detail panel. Hidden layers never
  // match queryRenderedFeatures, so this is inert while the toggle is off.
  useEffect(() => {
    if (!ready || !map) return undefined;
    const hitLayers = () => WILDFIRE_HIT_LAYERS.filter((id) => {
      try { return !!map.getLayer(id); } catch { return false; }
    });
    const query = (pt) => {
      const box = [[pt.x - 8, pt.y - 8], [pt.x + 8, pt.y + 8]];
      try { return map.queryRenderedFeatures(box, { layers: hitLayers() }); }
      catch { return []; }
    };
    const onClick = (e) => {
      const hit = query(e.point)[0];
      if (hit) setSelected({ ...hit.properties });
    };
    let hovering = false;
    const onMove = (e) => {
      const over = query(e.point).length > 0;
      if (over === hovering) return;
      hovering = over;
      map.getCanvas().style.cursor = over ? "pointer" : "";
    };
    map.on("click", onClick);
    map.on("mousemove", onMove);
    return () => { map.off("click", onClick); map.off("mousemove", onMove); };
  }, [ready, map]);

  const close = useCallback(() => setSelected(null), []);

  return { selectedFire: selected, closeFire: close };
}
