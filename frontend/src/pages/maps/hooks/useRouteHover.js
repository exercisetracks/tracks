// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
import { useEffect } from "react";

// Hover feedback for long trails: as the cursor moves over a route line, the
// whole section under it lights up and the cursor becomes a pointer — signalling
// each section is individually clickable (→ its elevation/detail panel).
//
// Driven by FEATURE-STATE, not setFilter: the routes source promotes each
// feature's baked `feat_key` (route_id:section_id) to the feature id, so all of a
// section's way-features share one id. Setting {hover:true} on that id lights the
// entire section on the GPU in one call — no per-mousemove style relayout. The
// hover layer (osm_trails_route_hover) paints opacity from the feature-state, so
// it only ever draws the hovered section.

// The invisible wide line (osm_trails_route_hit) is the hover target — a ~20px
// corridor, far easier to land on than the 2-3px painted route line.
const HIT_LAYERS = ["osm_trails_route_hit"];
const SRC = { source: "routes_osm", sourceLayer: "routes" };

export function useRouteHover(map, ready) {
  useEffect(() => {
    if (!ready || !map) return;

    let hovered = null;   // feat_key currently lit

    const clear = () => {
      if (hovered === null) return;
      try { map.setFeatureState({ ...SRC, id: hovered }, { hover: false }); } catch { /* gone */ }
      hovered = null;
      map.getCanvas().style.cursor = "";
    };

    const onMove = (e) => {
      const f = e.features && e.features[0];
      const key = f && f.id != null ? f.id : null;
      map.getCanvas().style.cursor = key != null ? "pointer" : "";
      if (key === hovered) return;
      clear();
      if (key != null) {
        hovered = key;
        try { map.setFeatureState({ ...SRC, id: key }, { hover: true }); } catch { /* not ready */ }
      }
    };

    const existing = HIT_LAYERS.filter((l) => {
      try { return !!map.getLayer(l); } catch { return false; }
    });
    existing.forEach((l) => {
      map.on("mousemove", l, onMove);
      map.on("mouseleave", l, clear);
    });
    return () => {
      existing.forEach((l) => {
        map.off("mousemove", l, onMove);
        map.off("mouseleave", l, clear);
      });
      clear();
    };
  }, [map, ready]);
}
