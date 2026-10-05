// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
// usePointInfo — "tap the map to learn about this spot" hook. On a map click it
// drops a marker, reads any nearby trail/route features straight from the
// already-rendered vector tiles (the TRAIL_LAYERS below), and fetches point data
// (elevation, weather, place) from the backend. Returns the selected-point state
// + clear control that PointInfoPanel renders.
import { useEffect, useRef, useState, useCallback } from "react";
import maplibregl from "maplibre-gl";
import { api } from "../../../api/client";

// Trail/route line layers to read "nearby trails" from (client-side, from the
// already-rendered vector tiles). Route layers are only visible when Long Trails
// is on; the trail-way layers cover the rest.
const TRAIL_LAYERS = [
  "osm_tracks_track", "osm_tracks_service",
  "osm_trails_unpaved", "osm_trails_paved",
  "osm_trails_faint", "osm_trails_steps",
  "osm_trails_route_hit", "osm_trails_route_line", "osm_trails_route_highlight",
  "osm_trails_route_icons", "osm_trails_section_markers",
  "trails_path", "trails_footway", "trails_bridleway", "trails_cycleway",
];
const POI_LAYERS = ["poi_db_icons"];
const QUERY_PX = 14;   // click tolerance box (pixels)

// Normalize a trail name for dedupe: drop "(Segment 9)" parentheticals, the
// generic words (trail/path/national scenic), and punctuation, so a route and the
// raw way it's built from collapse to the same token.
const _normTrail = (s) => (s || "").toLowerCase()
  .replace(/\(.*?\)/g, " ")
  .replace(/\b(national scenic|trail|track|path|way|segment|section)\b/g, " ")
  .replace(/[^a-z0-9]+/g, " ").trim();

// Land type at the click, read from the already-downloaded basemap `landuse`
// tiles (no network / no OSM call). The basemap carries use/cover, not parcel
// ownership, so we report the designation it does know — public designations
// (parks/reserves/military) and developed/private use — and leave natural cover
// as "ownership unknown". Lower index = preferred when polygons overlap.
const LAND_KINDS = {
  national_park:     ["National / state park", "public"],
  nature_reserve:    ["Nature reserve", "public"],
  protected_area:    ["Protected area", "public"],
  military:          ["Military reservation", "restricted"],
  park:              ["Park", "public"],
  recreation_ground: ["Public recreation area", "public"],
  cemetery:          ["Cemetery", null],
  golf_course:       ["Golf course", "private"],
  farmland:          ["Farmland", "private"],
  residential:       ["Residential", "private"],
  commercial:        ["Commercial", "private"],
  industrial:        ["Industrial", "private"],
  forest:            ["Forest", null],
  wood:              ["Woodland", null],
  meadow:            ["Meadow", null],
  grassland:         ["Grassland", null],
  grass:             ["Grassland", null],
  scrub:             ["Scrub / heath", null],
  wetland:           ["Wetland", null],
  bare_rock:         ["Bare rock", null],
  sand:              ["Sand", null],
  glacier:           ["Glacier / snowfield", null],
};
const LAND_RANK = Object.keys(LAND_KINDS);

function classifyLand(feats) {
  let best = null, bestIdx = Infinity;
  for (const f of feats) {
    const k = f.properties?.kind;
    const idx = LAND_RANK.indexOf(k);
    if (idx >= 0 && idx < bestIdx) {
      bestIdx = idx;
      const [type, ownership] = LAND_KINDS[k];
      best = { type, ownership, name: f.properties?.name || null, kind: k };
    }
  }
  return best;
}

// Click anywhere → gather nearby trails + POIs from the rendered tiles, drop a
// marker, and fetch elevation/weather/nearest-POI from the backend.
export function usePointInfo(map, ready, enabled, onRouteClick) {
  const [point, setPoint] = useState(null);     // {lat, lng}
  const [trails, setTrails] = useState([]);
  const [pois, setPois] = useState([]);
  const [activities, setActivities] = useState([]);
  const [land, setLand] = useState(null);
  const [info, setInfo] = useState(null);
  const [loading, setLoading] = useState(false);
  const markerRef = useRef(null);
  const reqRef = useRef(0);
  // Keep the latest onRouteClick without re-binding the map click listener.
  const onRouteClickRef = useRef(onRouteClick);
  onRouteClickRef.current = onRouteClick;

  const close = useCallback(() => {
    setPoint(null); setTrails([]); setPois([]); setActivities([]); setLand(null); setInfo(null); setLoading(false);
    if (markerRef.current) { markerRef.current.remove(); markerRef.current = null; }
  }, []);

  const _existing = useCallback((ids) => ids.filter((id) => {
    try { return !!map.getLayer(id); } catch { return false; }
  }), [map]);

  const _queryNear = useCallback((pt, layers) => {
    const box = [
      [pt.x - QUERY_PX, pt.y - QUERY_PX],
      [pt.x + QUERY_PX, pt.y + QUERY_PX],
    ];
    try { return map.queryRenderedFeatures(box, { layers: _existing(layers) }); }
    catch { return []; }
  }, [map, _existing]);

  useEffect(() => {
    if (!ready || !map) return;

    const onClick = (e) => {
      if (!enabled) return;   // suppressed while route-building / region-drawing / merging
      // Modifier clicks drive merge multi-select (useMergeSelect) — ignore them here.
      const oe = e.originalEvent;
      if (oe && (oe.ctrlKey || oe.metaKey)) return;

      // A click on a custom track / activity line / wildfire marker is handled
      // by its own selection hook (opens the matching detail overlay) — don't
      // also open the point-info panel. Wildfire hits mirror useWildfires'
      // targets: the incident dot and the perimeter EDGE, not the fill, so
      // clicking around inside a big burn area still gives point info.
      if (_queryNear(e.point, [
        "custom_tracks_hit", "activity_tracks_hit",
        "wildfire_points_core", "wildfire_points_glow", "wildfire_perimeter_line",
      ]).length > 0) return;

      const { lng, lat } = e.lngLat;
      setPoint({ lat, lng });

      // Nearby trails. Dedupe so a long-distance ROUTE chip (e.g. "Pacific
      // Crest Trail") doesn't also show the raw OSM way name it's built from
      // ("…and John Muir Trail (Section 3)"): routes dedupe by route_id, named
      // ways by name; then any non-route chip whose normalized name overlaps a
      // route chip is dropped, and remaining ways dedupe by normalized name.
      const tfeats = _queryNear(e.point, TRAIL_LAYERS);
      const byKey = new Map();
      for (const f of tfeats) {
        const p = f.properties || {};
        const name = p.name || "";
        const key = p.route && p.route_id != null ? `r:${p.route_id}`
          : name ? `n:${name.toLowerCase()}` : `k:${p.kind}:${p.use}`;
        if (byKey.has(key)) continue;
        byKey.set(key, {
          name, kind: p.kind, use: p.use, network: p.network, route: p.route,
          // route_id is the parent trail; section_id the clicked section (when the
          // click landed on a specific section's tread/marker) → opens detail.
          route_id: p.route_id != null ? String(p.route_id) : null,
          section_id: p.section_id != null ? String(p.section_id) : null,
          section: p.section || null,
        });
      }
      const routeNorms = [...byKey.values()].filter((t) => t.route).map((t) => _normTrail(t.name)).filter(Boolean);
      const seenNorm = new Set();
      const tlist = [...byKey.values()].filter((t) => {
        if (t.route) return true;
        const n = _normTrail(t.name);
        if (n && routeNorms.some((rn) => rn === n || rn.includes(n) || n.includes(rn))) return false;
        if (n) { if (seenNorm.has(n)) return false; seenNorm.add(n); }
        return true;
      });
      // Named + routes first
      tlist.sort((a, b) => (b.route ? 1 : 0) - (a.route ? 1 : 0) || (b.name ? 1 : 0) - (a.name ? 1 : 0));
      setTrails(tlist.slice(0, 12));

      // Clicking a long-trail segment auto-opens its elevation/section detail
      // panel (the top-left chip remains as an explicit affordance too).
      const clickedRoute = tlist.find((t) => t.route && t.route_id);
      if (clickedRoute && onRouteClickRef.current) {
        onRouteClickRef.current(clickedRoute.route_id, clickedRoute.section_id);
      }

      // Nearby past-activity tracks (when the Activity Tracks layer is on) — each
      // is selectable in the panel to inspect it or turn it into a custom track.
      const aById = new Map();
      for (const f of _queryNear(e.point, ["activity_tracks_hit"])) {
        const p = f.properties || {};
        const id = p.id != null ? p.id : f.id;
        if (id == null || aById.has(id)) continue;
        aById.set(id, { id, name: p.name, sport: p.sport, date: p.date, distance_m: p.distance_m });
      }
      setActivities([...aById.values()].slice(0, 6));

      // POIs at the click
      const pfeats = _queryNear(e.point, POI_LAYERS);
      const pseen = new Set();
      const plist = [];
      for (const f of pfeats) {
        const p = f.properties || {};
        if (!p.name || pseen.has(p.name)) continue;
        pseen.add(p.name);
        plist.push({ name: p.name, kind: p.kind, kind_detail: p.kind_detail, ele_ft: p.ele_ft });
      }
      setPois(plist.slice(0, 6));

      // Land type at the click (local; no network). Prefer the OSM public-land
      // overlay (real ownership: NF/BLM/NP/wilderness/state…) where a region is
      // downloaded; fall back to the basemap landuse cover/use elsewhere.
      let publand = [];
      try { publand = map.queryRenderedFeatures(e.point, { layers: _existing(["publiclands_fill"]) }); }
      catch { publand = []; }
      if (publand.length > 0) {
        const best = publand
          .map((f) => f.properties || {})
          .sort((a, b) => (a.min_zoom || 0) - (b.min_zoom || 0))[0];
        setLand({ type: best.land_type, ownership: best.ownership || null, name: best.name || null });
      } else {
        let lfeats = [];
        try { lfeats = map.queryRenderedFeatures(e.point, { layers: _existing(["landuse"]) }); }
        catch { lfeats = []; }
        setLand(classifyLand(lfeats));
      }

      // Marker
      if (markerRef.current) markerRef.current.remove();
      const el = document.createElement("div");
      el.className = "point-info-marker";
      markerRef.current = new maplibregl.Marker({ element: el, anchor: "center" })
        .setLngLat([lng, lat]).addTo(map);

      // Backend: elevation + weather + nearest POI
      const reqId = ++reqRef.current;
      setLoading(true);
      setInfo(null);
      api.getPointInfo(lat.toFixed(5), lng.toFixed(5))
        .then((data) => { if (reqId === reqRef.current) { setInfo(data); setLoading(false); } })
        .catch(() => { if (reqId === reqRef.current) setLoading(false); });
    };

    map.on("click", onClick);
    return () => map.off("click", onClick);
  }, [ready, map, enabled, _queryNear]);

  // Close + drop the marker if the panel is dismissed externally or clicks get disabled.
  useEffect(() => { if (!enabled) close(); }, [enabled, close]);

  return { point, trails, pois, activities, land, info, loading, close };
}
