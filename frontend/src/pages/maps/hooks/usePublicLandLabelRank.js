// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect, useRef } from "react";
import { cellKey } from "../utils/labelRank";

// Deterministic thinning for the public-land area names (publiclands_labels).
// Their baked min_zoom grade alone isn't enough: adjacent Wilderness/Study
// Areas often share a grade, so at that zoom they'd all print at once and
// overlap (collision culling is disabled style-wide — see withStablePlacement).
// The center-label points live in vector tiles, so unlike the POI GeoJSON they
// can't be re-graded before they reach the map; instead this hook queries the
// loaded tiles and grants each name a slot in the same world-aligned grid
// labelRank.js uses. Town names (basemap places) claim their cells first, so an
// area name can never sit on a city label; then candidates claim cells in
// importance order (min_zoom grade, then name as a stable tie-break).
//
// Grants are MONOTONIC within the session: once a name has won a slot at some
// zoom, tiles loaded by later panning can never steal it — a visible label
// never disappears under you. Zooming out hides grants made at deeper zooms
// (grantedZoom > zoom), which is just the density gate running in reverse.
// The layer starts filtered to nothing (see publiclands.js) and this hook owns
// its filter from then on, swapping in the granted-name list per integer zoom.
const LAYER_ID = "publiclands_labels";
const CELL_PX = 120; // ~footprint of one wrapped area name + halo, screen px
const MIN_Z = 6; // publiclands_labels minzoom
const MAX_Z = 16;
const DEF_GRADE = 9; // grade assumed when a feature lacks min_zoom
const IS_CENTER_LABEL = ["all",
  ["==", ["geometry-type"], "Point"], ["==", ["get", "label"], 1]];

export function usePublicLandLabelRank(map, ready) {
  const grantedRef = useRef(new Map()); // name -> { gz, lng, lat }
  const timerRef = useRef(null);

  useEffect(() => {
    if (!ready || !map) return;
    const granted = grantedRef.current;

    const recompute = () => {
      if (!map.getLayer(LAYER_ID)) return;
      const z = Math.max(MIN_Z, Math.min(MAX_Z, Math.floor(map.getZoom())));
      const taken = new Set();
      // Visible town names block their cells first.
      for (const f of map.querySourceFeatures("basemap", {
        sourceLayer: "places", filter: ["==", ["get", "kind"], "locality"],
      })) {
        if (f.geometry?.type !== "Point") continue;
        if ((f.properties?.min_zoom ?? 0) > z) continue;
        taken.add(cellKey(f.geometry.coordinates[0], f.geometry.coordinates[1], z, CELL_PX));
      }
      // Already-granted names keep their cells.
      for (const g of granted.values()) {
        if (g.gz <= z) taken.add(cellKey(g.lng, g.lat, z, CELL_PX));
      }
      // New candidates from the loaded tiles (dedupe tile-buffer copies by name).
      const seen = new Set();
      const cands = [];
      for (const f of map.querySourceFeatures("overlay", {
        sourceLayer: "landuse", filter: IS_CENTER_LABEL,
      })) {
        const name = f.properties?.name ?? f.properties?.land_type;
        if (name == null || seen.has(name) || granted.has(name)) continue;
        seen.add(name);
        if (f.geometry?.type !== "Point") continue;
        cands.push({ name, grade: f.properties?.min_zoom ?? DEF_GRADE, c: f.geometry.coordinates });
      }
      cands.sort((a, b) => (a.grade - b.grade) || (a.name < b.name ? -1 : 1));
      for (const cand of cands) {
        if (Math.ceil(cand.grade) > z) continue; // not important enough yet
        const key = cellKey(cand.c[0], cand.c[1], z, CELL_PX);
        if (taken.has(key)) continue;
        taken.add(key);
        granted.set(cand.name, { gz: z, lng: cand.c[0], lat: cand.c[1] });
      }
      const allowed = [];
      for (const [name, g] of granted) if (g.gz <= z) allowed.push(name);
      map.setFilter(LAYER_ID, ["all", IS_CENTER_LABEL,
        ["in", ["coalesce", ["get", "name"], ["get", "land_type"]], ["literal", allowed]]]);
    };

    const schedule = () => {
      clearTimeout(timerRef.current);
      timerRef.current = setTimeout(recompute, 250);
    };
    const onSourceData = (e) => {
      if ((e.sourceId === "overlay" || e.sourceId === "basemap") && e.isSourceLoaded) schedule();
    };
    map.on("moveend", schedule);
    map.on("zoomend", schedule);
    map.on("sourcedata", onSourceData);
    schedule();
    return () => {
      clearTimeout(timerRef.current);
      map.off("moveend", schedule);
      map.off("zoomend", schedule);
      map.off("sourcedata", onSourceData);
    };
  }, [map, ready]);
}
